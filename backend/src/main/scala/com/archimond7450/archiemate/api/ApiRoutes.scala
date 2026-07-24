package com.archimond7450.archiemate.api

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.{*, given}
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.model.headers.HttpCookie
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.Authorization
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import com.archimond7450.archiemate.ReadinessTracker
import com.archimond7450.archiemate.auth.AuthDirectives
import com.archimond7450.archiemate.auth.JwtActor
import com.archimond7450.archiemate.settings.*
import com.archimond7450.archiemate.kick.KickApiActor
import com.archimond7450.archiemate.twitch.TwitchApiActor
import com.archimond7450.archiemate.twitch.eventsub.EventSubWebhookRoutes
import com.archimond7450.archiemate.youtube.YoutubeApiActor
import com.archimond7450.archiemate.auth.YoutubeOAuthActor
import com.archimond7450.archiemate.user.{UserConfigRegistry, UserTokenRegistry}
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.EncoderOps

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

/** Name of the cookie holding the JWT token. */
private val JwtCookieName = "archiemate_jwt"

final case class AuthResponse(userId: String, isAdmin: Boolean)

object AuthResponse {
  given Encoder[AuthResponse] = new Encoder[AuthResponse] {
    def apply(a: AuthResponse): io.circe.Json = {
      var fields = List[(String, io.circe.Json)]("user_id" -> io.circe.Json.fromString(a.userId))
      if (a.isAdmin) {
        fields = "is_admin" -> io.circe.Json.True :: fields
      }
      io.circe.Json.obj(fields*)
    }
  }
}

final case class TwitchProfileResponse(
    display_name: String,
    profile_image_url: String
)

object TwitchProfileResponse {
  given Encoder[TwitchProfileResponse] = deriveEncoder
}

final case class VideoResponse(
    video_id: String,
    title: String,
    published_at: String,
    thumbnail_url: String
)

object VideoResponse {
  given Encoder[VideoResponse] = deriveEncoder
}

final case class VideoListResponse(
    videos: List[VideoResponse]
)

object VideoListResponse {
  given Encoder[VideoListResponse] = deriveEncoder
}

class ApiRoutes(
    config: AppConfig,
    readinessTracker: ActorRef[ReadinessTracker.Command],
    jwtActor: ActorRef[JwtActor.Command],
    twitchApiActor: ActorRef[TwitchApiActor.Command],
    kickApiActor: ActorRef[KickApiActor.Command],
    youtubeApiActor: ActorRef[YoutubeApiActor.Command],
    youtubeOAuthActor: ActorRef[YoutubeOAuthActor.Command],
    eventSubActor: ActorRef[com.archimond7450.archiemate.twitch.eventsub.EventSubActor.Command],
    userTokenRegistry: ActorRef[UserTokenRegistry.Command],
    userConfigRegistry: ActorRef[UserConfigRegistry.Command],
    classicActorSystem: ActorSystem
) {

  private val connectionRoutes = new ConnectionRoutes(
    config,
    jwtActor,
    userTokenRegistry,
    userConfigRegistry,
    classicActorSystem.toTyped.scheduler,
    Timeout(config.askTimeout),
    scala.concurrent.ExecutionContext.global,
    classicActorSystem
  ).connectionRoutes

  private val eventSubRoutes = new EventSubWebhookRoutes(
    config.eventSub,
    scala.concurrent.ExecutionContext.global,
    classicActorSystem
  ).webhookRoutes

  private val apiVersion = config.server.apiVersion

  private def encodeVideoList(videos: List[YoutubeApiActor.VideoInfo]): String = {
    val encoder = deriveEncoder[VideoListResponse]
    VideoListResponse(
      videos.map { v =>
        VideoResponse(v.videoId, v.title, v.publishedAt, v.thumbnailUrl)
      }
    ).asJson.noSpaces
  }

  def apiRoutes: Route = {
    pathPrefix("api") {
      pathPrefix(apiVersion) {
        // Health check endpoints
        path("live") {
          get {
            complete(StatusCodes.NoContent)
          }
        } ~
        path("ready") {
          get {
            given Scheduler = classicActorSystem.toTyped.scheduler
            given Timeout = Timeout(config.askTimeout)
            given ExecutionContext = scala.concurrent.ExecutionContext.global
            onSuccess(
              readinessTracker.ask[ReadinessTracker.ReadinessResponse](ref =>
                ReadinessTracker.CheckReadiness(ref)
              )
            ) {
              case ReadinessTracker.ReadyResponse    => complete(StatusCodes.NoContent)
              case ReadinessTracker.NotReadyResponse => complete(StatusCodes.ServiceUnavailable)
            }
          }
        } ~
        // Authenticated endpoints
        path("me") {
          get {
            given Scheduler = classicActorSystem.toTyped.scheduler
            given Timeout = Timeout(config.askTimeout)
            given ExecutionContext = scala.concurrent.ExecutionContext.global
            extractRequest { request =>
              // First try cookie-based auth (HTTP-only cookie)
              request.cookies.find(_.name == JwtCookieName) match {
                case Some(cookie) =>
                  onComplete(AuthDirectives.authenticateToken(cookie.value, jwtActor)) {
                    case Success(Right(userId)) =>
                      complete(StatusCodes.OK -> AuthResponse(userId, config.adminUserId == userId).asJson.noSpaces)
                    case Success(Left(_))       => complete(StatusCodes.Unauthorized -> "Invalid token")
                    case Failure(_)             => complete(StatusCodes.Unauthorized -> "Token authentication failed")
                  }
                case None =>
                  // Fall back to Authorization header for backwards compatibility
                  request.header[Authorization] match {
                    case Some(Authorization(credentials)) =>
                      val token = credentials.value
                      onComplete(AuthDirectives.authenticateToken(token, jwtActor)) {
                        case Success(Right(userId)) =>
                          complete(StatusCodes.OK -> AuthResponse(userId, config.adminUserId == userId).asJson.noSpaces)
                        case Success(Left(_))       => complete(StatusCodes.Unauthorized -> "Invalid token")
                        case Failure(_)             => complete(StatusCodes.Unauthorized -> "Token authentication failed")
                      }
                    case None =>
                      complete(StatusCodes.Unauthorized -> "Missing authentication")
                  }
              }
            }
          }
        } ~
        // Logout endpoint
        path("logout") {
          post {
            val cookie = HttpCookie(
              JwtCookieName,
              "",
              httpOnly = true,
              secure = true,
              maxAge = Some(0L)
            )
            setCookie(cookie) {
              complete(StatusCodes.OK)
            }
          }
        } ~
        // Twitch user info
        path("twitch" / "me") {
          get {
            given Scheduler = classicActorSystem.toTyped.scheduler
            given Timeout = Timeout(config.askTimeout)
            given ExecutionContext = scala.concurrent.ExecutionContext.global
            extractRequest { request =>
              request.cookies.find(_.name == JwtCookieName) match {
                case Some(cookie) =>
                  onComplete(AuthDirectives.authenticateToken(cookie.value, jwtActor)) {
                    case Success(Right(userId)) =>
                      // Get the user's Twitch access token from the registry
                      onComplete(
                        userTokenRegistry.ask[UserTokenRegistry.GetResponse](ref =>
                          UserTokenRegistry.GetAllTwitchAuthTokens(userId, ref)
                        )
                      ) {
                        case Success(UserTokenRegistry.AllTokensFound(tokens)) =>
                          val token = tokens.head
                          onComplete(
                            twitchApiActor.ask[TwitchApiActor.TokenResponse](ref =>
                              TwitchApiActor.GetCurrentUser(token.accessToken, ref)
                            )
                          ) {
                            case Success(TwitchApiActor.UserFound(_, _, displayName)) =>
                              complete(StatusCodes.OK -> TwitchProfileResponse(displayName, "").asJson.noSpaces)
                            case Success(TwitchApiActor.TokenRefreshed(_, _, _)) =>
                              complete(StatusCodes.InternalServerError -> "Unexpected token refresh response")
                            case Success(TwitchApiActor.UserListFound(_)) =>
                              complete(StatusCodes.InternalServerError -> "Unexpected user list response")
                            case Success(TwitchApiActor.Error(msg)) =>
                              complete(StatusCodes.InternalServerError -> msg)
                            case Failure(ex) =>
                              complete(StatusCodes.InternalServerError -> ex.getMessage)
                          }
                        case Success(UserTokenRegistry.AllTokensFoundEmpty) =>
                          complete(StatusCodes.NotFound -> "No Twitch auth found")
                        case Success(UserTokenRegistry.TokenNotFound) =>
                          complete(StatusCodes.NotFound -> "No Twitch token found")
                        case Success(UserTokenRegistry.TokenFound(_)) =>
                          complete(StatusCodes.InternalServerError -> "Unexpected single token response")
                        case Success(UserTokenRegistry.Error(msg)) =>
                          complete(StatusCodes.InternalServerError -> msg)
                        case Failure(ex) =>
                          complete(StatusCodes.InternalServerError -> ex.getMessage)
                      }
                    case Success(Left(_))       => complete(StatusCodes.Unauthorized -> "Invalid token")
                    case Failure(_)             => complete(StatusCodes.Unauthorized -> "Token authentication failed")
                  }
                case None =>
                  complete(StatusCodes.Unauthorized -> "Missing authentication")
              }
            }
          }
        } ~
        // Public auth endpoints
        path("auth") {
          get {
            complete(StatusCodes.OK -> "Auth endpoints available")
          }
        } ~
        // YouTube videos endpoint
        path("youtube" / "videos") {
          get {
            given Scheduler = classicActorSystem.toTyped.scheduler
            given Timeout = Timeout(config.askTimeout)
            given ExecutionContext = scala.concurrent.ExecutionContext.global
            extractRequest { request =>
              request.cookies.find(_.name == JwtCookieName) match {
                case Some(cookie) =>
                  onComplete(AuthDirectives.authenticateToken(cookie.value, jwtActor)) {
                    case Success(Right(userId)) =>
                      // Extract query parameters
                      val params = request.uri.query()
                      val channelId = params.get("channelId").getOrElse("")
                      val maxResults = params.get("maxResults").map(_.toInt).getOrElse(10)

                      if (channelId.isEmpty) {
                        complete(StatusCodes.BadRequest -> "Missing channelId parameter")
                      } else {
                        // Get the YouTube connection for this channel
                        onComplete(
                          userTokenRegistry.ask[UserTokenRegistry.YoutubeSecondaryResponse](ref =>
                            UserTokenRegistry.GetYoutubeSecondaryConnections(userId, ref)
                          )
                        ) {
                          case Success(UserTokenRegistry.YoutubeSecondaryConnectionsFound(connections)) =>
                            connections.find(_.channelId == channelId) match {
                              case Some(conn) =>
                                onComplete(
                                  youtubeApiActor.ask[YoutubeApiActor.TokenResponse](ref =>
                                    YoutubeApiActor.GetLatestVideos(channelId, conn.accessToken, maxResults, ref)
                                  )
                                ) {
                                  case Success(YoutubeApiActor.VideoList(videos)) =>
                                    complete(StatusCodes.OK -> encodeVideoList(videos))
                                  case Success(YoutubeApiActor.TokenRefreshed(_, _, _)) =>
                                    complete(StatusCodes.InternalServerError -> "Unexpected token refresh response")
                                  case Success(YoutubeApiActor.UserFound(_, _, _, _, _)) =>
                                    complete(StatusCodes.InternalServerError -> "Unexpected user found response")
                                  case Success(YoutubeApiActor.Error(msg)) =>
                                    complete(StatusCodes.InternalServerError -> msg)
                                  case Failure(ex) =>
                                    complete(StatusCodes.InternalServerError -> ex.getMessage)
                                }
                              case None =>
                                complete(StatusCodes.NotFound -> s"No YouTube connection found for channel: $channelId")
                            }
                          case Success(UserTokenRegistry.YoutubeSecondaryError(msg)) =>
                            complete(StatusCodes.InternalServerError -> msg)
                          case Success(other) =>
                            complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
                          case Failure(ex) =>
                            complete(StatusCodes.InternalServerError -> ex.getMessage)
                        }
                      }
                    case Success(Left(_)) =>
                      complete(StatusCodes.Unauthorized -> "Invalid token")
                    case Failure(_) =>
                      complete(StatusCodes.Unauthorized -> "Token authentication failed")
                  }
                case None =>
                  complete(StatusCodes.Unauthorized -> "Missing authentication")
              }
            }
          }
        }
      }
    } ~
    // Connection routes
    connectionRoutes ~
    // EventSub webhook routes
    eventSubRoutes ~
    // Serve static frontend files
    pathEndOrSingleSlash {
      getFromResource("public/index.html")
    } ~
    pathPrefix("static") {
      getFromResourceDirectory("public")
    } ~
    // Catch-all for SPA routing — serve index.html for any unknown path
    get {
      getFromResource("public/index.html")
    }
  }
}
