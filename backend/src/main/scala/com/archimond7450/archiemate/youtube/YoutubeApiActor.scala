package com.archimond7450.archiemate.youtube

import com.archimond7450.archiemate.actors.http.HttpRequestActor
import com.archimond7450.archiemate.settings.YoutubeConfig
import com.archimond7450.archiemate.user.UserTokenRegistry
import com.archimond7450.archiemate.user.UserTokenRegistry.{*, given}
import io.circe.Decoder
import io.circe.parser.decode
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.http.scaladsl.model.HttpMethods
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.util.Timeout

import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext

/** Dedicated actor for YouTube API operations: token management and user
  * info retrieval.
  *
  * This actor encapsulates all YouTube API HTTP calls, including:
  *   - Token refresh (automatic on HTTP 401)
  *   - User info retrieval (by access token)
  *   - Proper header construction (Bearer)
  *
  * @note
  * This actor must be supervised by its parent with a resume strategy on
  * [[Throwable]] to preserve state across failures:
  * {{{supervise(YoutubeApiActor(...)).onFailure[Throwable](SupervisorStrategy.resume)}}}
  */
object YoutubeApiActor {

  private val actorName = "youtube-api-actor"

  // ----------------------------------------------------------------
  // Commands
  // ----------------------------------------------------------------

  sealed trait Command

  /** Internal command to signal HTTP response received. */
  private final case class HttpRequestReply(reply: StatusReply[Any]) extends Command

  /** Refresh an access token using its refresh token. */
  final case class RefreshToken(
      refreshToken: String,
      replyTo: ActorRef[TokenResponse]
  ) extends Command

  /** Get current user info by access token. */
  final case class GetCurrentUser(
      accessToken: String,
      replyTo: ActorRef[TokenResponse]
  ) extends Command

  // ----------------------------------------------------------------
  // Responses
  // ----------------------------------------------------------------

  sealed trait TokenResponse

  final case class TokenRefreshed(
      accessToken: String,
      refreshToken: String,
      expiresIn: Long
  ) extends TokenResponse

  final case class UserFound(
      id: String,
      email: String,
      verifiedEmail: Boolean,
      name: String,
      picture: Option[String]
  ) extends TokenResponse

  final case class Error(message: String) extends TokenResponse

  // ----------------------------------------------------------------
  // Internal response models
  // ----------------------------------------------------------------

  private case class YoutubeTokenResponse(
      accessToken: String,
      refreshToken: String,
      expiresIn: Long,
      scope: String
  )

  private object YoutubeTokenResponse {
    implicit val decoder: Decoder[YoutubeTokenResponse] = Decoder.forProduct4(
      "access_token",
      "refresh_token",
      "expires_in",
      "scope"
    )(YoutubeTokenResponse.apply)
  }

  private case class YoutubeUserInfoResponse(
      id: String,
      email: String,
      verifiedEmail: Boolean,
      name: String,
      picture: Option[String]
  )

  private object YoutubeUserInfoResponse {
    implicit val decoder: Decoder[YoutubeUserInfoResponse] = Decoder.instance { c =>
      for {
        id <- c.downField("id").as[String]
        email <- c.downField("email").as[String]
        verifiedEmail <- c.downField("verified_email").as[Boolean]
        name <- c.downField("name").as[String]
        picture <- c.downField("picture").as[Option[String]]
      } yield YoutubeUserInfoResponse(id, email, verifiedEmail, name, picture)
    }
  }

  // ----------------------------------------------------------------
  // Default YouTube API base URLs (overridable for testing)
  // ----------------------------------------------------------------

  val DefaultBaseUrl = "https://www.googleapis.com"
  val DefaultAuthBaseUrl = "https://oauth2.googleapis.com"

  // ----------------------------------------------------------------
  // Factory
  // ----------------------------------------------------------------

  def apply(
      config: YoutubeConfig,
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      userTokenRegistry: ActorRef[UserTokenRegistry.Command],
      baseUrl: String = DefaultBaseUrl,
      authBaseUrl: String = DefaultAuthBaseUrl
  ): Behavior[Command] =
    Behaviors.supervise[Command] {
      Behaviors.setup { ctx =>
        ctx.log.info(
          "YoutubeApiActor initialized for client {}",
          config.clientId
        )
        mainBehavior(
          config,
          httpRequestActor,
          userTokenRegistry,
          baseUrl,
          authBaseUrl
        )(using
          ctx,
          scheduler = ctx.system.scheduler,
          timeout = Timeout(5.seconds),
          execEc = scala.concurrent.ExecutionContext.global
        )
      }
    }.onFailure[Throwable](SupervisorStrategy.resume)

  // ----------------------------------------------------------------
  // Behavior
  // ----------------------------------------------------------------

  private def mainBehavior(
      config: YoutubeConfig,
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      userTokenRegistry: ActorRef[UserTokenRegistry.Command],
      baseUrl: String,
      authBaseUrl: String
  )(using
      ctx: ActorContext[Command],
      scheduler: Scheduler,
      timeout: Timeout,
      execEc: ExecutionContext
  ): Behavior[Command] =
    Behaviors.withMdc(Map("actor" -> actorName))(
      Behaviors.receiveMessage {
        case refresh: RefreshToken =>
          refreshToken(config, refresh.refreshToken, refresh.replyTo, httpRequestActor, authBaseUrl)
          Behaviors.same

        case getCurrentUserCmd: GetCurrentUser =>
          getCurrentUser(config, getCurrentUserCmd.accessToken, getCurrentUserCmd.replyTo, httpRequestActor, baseUrl)
          Behaviors.same

        case HttpRequestReply(_) =>
          // Internal reply from message adapter — should not reach here
          Behaviors.same
      }
    )

  // ----------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------

  private def handleApiError(
      err: Throwable,
      replyTo: ActorRef[TokenResponse],
      context: String
  )(using
      ctx: ActorContext[Command]
  ): Unit = {
    val isUnauthorized = err.getMessage.toLowerCase.contains("401")
    if (isUnauthorized) {
      replyTo ! Error(s"$context: token expired or invalid (HTTP 401)")
    } else {
      replyTo ! Error(s"$context: ${err.getMessage}")
    }
  }

  // ----------------------------------------------------------------
  // HTTP Request Handlers
  // ----------------------------------------------------------------

  private def refreshToken(
      config: YoutubeConfig,
      refreshToken: String,
      replyTo: ActorRef[TokenResponse],
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      authBaseUrl: String
  )(using
      ctx: ActorContext[Command],
      scheduler: Scheduler,
      timeout: Timeout,
      execEc: ExecutionContext
  ): Unit = {
    val body = Seq(
      s"client_id=${config.clientId}",
      s"client_secret=${config.clientSecret}",
      s"grant_type=refresh_token",
      s"refresh_token=${refreshToken}"
    ).mkString("&")

    val probeRef: ActorRef[StatusReply[Any]] = ctx.messageAdapter[StatusReply[Any]] { statusReply =>
      statusReply match {
        case StatusReply.Success(value) =>
          value match {
            case resp: YoutubeTokenResponse =>
              replyTo ! TokenRefreshed(resp.accessToken, resp.refreshToken, resp.expiresIn)
            case _ =>
              replyTo ! Error(s"Token refresh failed: unexpected response type")
          }
        case StatusReply.Error(err) =>
          replyTo ! Error(s"Token refresh failed: ${err.getMessage}")
      }
      HttpRequestReply(statusReply)
    }

    httpRequestActor ! HttpRequestActor.Request[YoutubeTokenResponse](
      method = HttpMethods.POST,
      uri = Uri(s"$authBaseUrl/oauth2/token"),
      headers = Seq(RawHeader("Content-Type", "application/x-www-form-urlencoded")),
      entity = org.apache.pekko.http.scaladsl.model.HttpEntity(
        org.apache.pekko.http.scaladsl.model.MediaTypes.`application/x-www-form-urlencoded`,
        body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      ),
      decode = str => decode[YoutubeTokenResponse](str).toTry,
      replyTo = probeRef
    )
  }

  private def getCurrentUser(
      config: YoutubeConfig,
      accessToken: String,
      replyTo: ActorRef[TokenResponse],
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      baseUrl: String
  )(using
      ctx: ActorContext[Command],
      scheduler: Scheduler,
      timeout: Timeout,
      execEc: ExecutionContext
  ): Unit = {
    val query = Uri.Query("access_token" -> accessToken)
    val uri = Uri(s"$baseUrl/oauth2/v3/userinfo").withQuery(query)

    val probeRef: ActorRef[StatusReply[Any]] = ctx.messageAdapter[StatusReply[Any]] { statusReply =>
      statusReply match {
        case StatusReply.Success(value) =>
          value match {
            case user: YoutubeUserInfoResponse =>
              replyTo ! UserFound(user.id, user.email, user.verifiedEmail, user.name, user.picture)
            case _ =>
              replyTo ! Error("Get current user: unexpected response type")
          }
        case StatusReply.Error(err) =>
          handleApiError(err, replyTo, "Get current user")
      }
      HttpRequestReply(statusReply)
    }

    httpRequestActor ! HttpRequestActor.Request[YoutubeUserInfoResponse](
      method = HttpMethods.GET,
      uri = uri,
      headers = Seq(
        RawHeader("Authorization", s"Bearer $accessToken")
      ),
      entity = org.apache.pekko.http.scaladsl.model.HttpEntity.Empty,
      decode = str => decode[YoutubeUserInfoResponse](str).toTry,
      replyTo = probeRef
    )
  }

}
