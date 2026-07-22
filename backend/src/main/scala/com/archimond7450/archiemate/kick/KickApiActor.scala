package com.archimond7450.archiemate.kick

import com.archimond7450.archiemate.actors.http.HttpRequestActor
import com.archimond7450.archiemate.settings.KickConfig
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

/** Dedicated actor for Kick API operations: token management and user
  * info retrieval.
  *
  * This actor encapsulates all Kick API HTTP calls, including:
  *   - Token refresh (automatic on HTTP 401)
  *   - User info retrieval (by access token)
  *   - Proper header construction (Client-ID)
  *
  * @note
  * This actor must be supervised by its parent with a resume strategy on
  * [[Throwable]] to preserve state across failures:
  * {{{supervise(KickApiActor(...)).onFailure[Throwable](SupervisorStrategy.resume)}}}
  */
object KickApiActor {

  private val actorName = "kick-api-actor"

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
      name: String,
      bio: Option[String],
      profileImage: Option[String]
  ) extends TokenResponse

  final case class Error(message: String) extends TokenResponse

  // ----------------------------------------------------------------
  // Internal response models
  // ----------------------------------------------------------------

  private case class KickTokenResponse(
      accessToken: String,
      refreshToken: String,
      expiresIn: Long
  )

  private object KickTokenResponse {
    implicit val decoder: Decoder[KickTokenResponse] = Decoder.forProduct3(
      "access_token",
      "refresh_token",
      "expires_in"
    )(KickTokenResponse.apply)
  }

  private case class KickUserResponse(
      id: String,
      name: String,
      bio: Option[String],
      profileImage: Option[String]
  )

  private object KickUserResponse {
    implicit val decoder: Decoder[KickUserResponse] = Decoder.instance { c =>
      for {
        id <- c.downField("id").as[String]
        name <- c.downField("name").as[String]
        bio <- c.downField("bio").as[Option[String]]
        profileImage <- c.downField("profile_image").as[Option[String]]
      } yield KickUserResponse(id, name, bio, profileImage)
    }
  }

  // ----------------------------------------------------------------
  // Default Kick API base URL (overridable for testing)
  // ----------------------------------------------------------------

  val DefaultBaseUrl = "https://api.kick.com"
  val DefaultAuthBaseUrl = "https://auth.kick.com"

  // ----------------------------------------------------------------
  // Factory
  // ----------------------------------------------------------------

  def apply(
      config: KickConfig,
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      userTokenRegistry: ActorRef[UserTokenRegistry.Command],
      baseUrl: String = DefaultBaseUrl,
      authBaseUrl: String = DefaultAuthBaseUrl
  ): Behavior[Command] =
    Behaviors.supervise[Command] {
      Behaviors.setup { ctx =>
        ctx.log.info(
          "KickApiActor initialized for client {}",
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
      config: KickConfig,
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
      config: KickConfig,
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
            case resp: KickTokenResponse =>
              replyTo ! TokenRefreshed(resp.accessToken, resp.refreshToken, resp.expiresIn)
            case _ =>
              replyTo ! Error(s"Token refresh failed: unexpected response type")
          }
        case StatusReply.Error(err) =>
          replyTo ! Error(s"Token refresh failed: ${err.getMessage}")
      }
      HttpRequestReply(statusReply)
    }

    httpRequestActor ! HttpRequestActor.Request[KickTokenResponse](
      method = HttpMethods.POST,
      uri = Uri(s"$authBaseUrl/oauth2/token"),
      headers = Seq(RawHeader("Content-Type", "application/x-www-form-urlencoded")),
      entity = org.apache.pekko.http.scaladsl.model.HttpEntity(
        org.apache.pekko.http.scaladsl.model.MediaTypes.`application/x-www-form-urlencoded`,
        body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      ),
      decode = str => decode[KickTokenResponse](str).toTry,
      replyTo = probeRef
    )
  }

  private def getCurrentUser(
      config: KickConfig,
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
    val uri = Uri(s"$baseUrl/user/me").withQuery(query)

    val probeRef: ActorRef[StatusReply[Any]] = ctx.messageAdapter[StatusReply[Any]] { statusReply =>
      statusReply match {
        case StatusReply.Success(value) =>
          value match {
            case user: KickUserResponse =>
              replyTo ! UserFound(user.id, user.name, user.bio, user.profileImage)
            case _ =>
              replyTo ! Error("Get current user: unexpected response type")
          }
        case StatusReply.Error(err) =>
          handleApiError(err, replyTo, "Get current user")
      }
      HttpRequestReply(statusReply)
    }

    httpRequestActor ! HttpRequestActor.Request[KickUserResponse](
      method = HttpMethods.GET,
      uri = uri,
      headers = Seq(
        RawHeader("Client-ID", config.clientId)
      ),
      entity = org.apache.pekko.http.scaladsl.model.HttpEntity.Empty,
      decode = str => decode[KickUserResponse](str).toTry,
      replyTo = probeRef
    )
  }

}
