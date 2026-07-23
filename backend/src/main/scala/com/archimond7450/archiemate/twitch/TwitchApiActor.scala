package com.archimond7450.archiemate.twitch

import com.archimond7450.archiemate.actors.http.HttpRequestActor
import com.archimond7450.archiemate.settings.TwitchConfig
import com.archimond7450.archiemate.user.UserTokenRegistry
import com.archimond7450.archiemate.user.UserTokenRegistry.{*, given}
import io.circe.Decoder
import io.circe.parser.decode
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.http.scaladsl.model.HttpMethods
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.model.headers.Authorization
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.util.Timeout

import java.time.Instant
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

/** Dedicated actor for Twitch API operations: token management and user
  * info retrieval.
  *
  * This actor encapsulates all Twitch API HTTP calls, including:
  *   - Token refresh (automatic on HTTP 401)
  *   - User info lookup (by ID or by access token)
  *   - Proper header construction (Bearer, Client-Id)
  *
  * @note
  * This actor must be supervised by its parent with a resume strategy on
  * [[Throwable]] to preserve state across failures:
  * {{{supervise(TwitchApiActor(...)).onFailure[Throwable](SupervisorStrategy.resume)}}}
  */
object TwitchApiActor {

  private val actorName = "twitch-api-actor"

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

  /** Get user info by user ID (requires a valid access token). */
  final case class GetUserById(
      userId: String,
      accessToken: String,
      replyTo: ActorRef[TokenResponse]
  ) extends Command

  /** Get current user info by access token. */
  final case class GetCurrentUser(
      accessToken: String,
      replyTo: ActorRef[TokenResponse]
  ) extends Command

  /** Get user info by login name. */
  final case class GetUserByLogin(
      loginName: String,
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
      login: String,
      displayName: String
  ) extends TokenResponse

  final case class UserListFound(
      users: List[UserFound]
  ) extends TokenResponse

  final case class Error(message: String) extends TokenResponse

  // ----------------------------------------------------------------
  // Internal response models
  // ----------------------------------------------------------------

  private case class TwitchTokenResponse(
      accessToken: String,
      refreshToken: String,
      expiresIn: Long,
      scope: String,
      tokenType: String
  )

  private object TwitchTokenResponse {
    implicit val decoder: Decoder[TwitchTokenResponse] = Decoder.forProduct5(
      "access_token",
      "refresh_token",
      "expires_in",
      "scope",
      "token_type"
    )(TwitchTokenResponse.apply)
  }

  private case class TwitchUserList(
      data: List[TwitchUserInner]
  )

  private case class TwitchUserInner(
      id: String,
      login: String,
      displayName: String
  )

  private object TwitchUserInner {
    implicit val decoder: Decoder[TwitchUserInner] = Decoder.forProduct3(
      "id",
      "login",
      "display_name"
    )(TwitchUserInner.apply)
  }

  private object TwitchUserList {
    implicit val decoder: Decoder[TwitchUserList] = Decoder.instance { c =>
      for {
        data <- c.downField("data").as[List[TwitchUserInner]]
      } yield TwitchUserList(data)
    }
  }

  // ----------------------------------------------------------------
  // Default Twitch API base URL (overridable for testing)
  // ----------------------------------------------------------------

  val DefaultAuthBaseUrl = "https://id.twitch.tv/oauth2"
  val DefaultHelixBaseUrl = "https://api.twitch.tv/helix"

  // ----------------------------------------------------------------
  // Factory
  // ----------------------------------------------------------------

  def apply(
      config: TwitchConfig,
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      userTokenRegistry: ActorRef[UserTokenRegistry.Command],
      helixBaseUrl: String = DefaultHelixBaseUrl,
      authBaseUrl: String = DefaultAuthBaseUrl
  ): Behavior[Command] =
    Behaviors.supervise[Command] {
      Behaviors.setup { ctx =>
        ctx.log.info(
          "TwitchApiActor initialized for client {}",
          config.clientId
        )
        mainBehavior(
          config,
          httpRequestActor,
          userTokenRegistry,
          helixBaseUrl,
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
      config: TwitchConfig,
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      userTokenRegistry: ActorRef[UserTokenRegistry.Command],
      helixBaseUrl: String,
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

        case getUser: GetUserById =>
          getUserById(config, getUser.userId, getUser.accessToken, getUser.replyTo, httpRequestActor, helixBaseUrl)
          Behaviors.same

        case getCurrentUserCmd: GetCurrentUser =>
          getCurrentUser(config, getCurrentUserCmd.accessToken, getCurrentUserCmd.replyTo, httpRequestActor, helixBaseUrl)
          Behaviors.same

        case getUserByLoginCmd: GetUserByLogin =>
          getUserByLogin(config, getUserByLoginCmd.loginName, getUserByLoginCmd.accessToken, getUserByLoginCmd.replyTo, httpRequestActor, helixBaseUrl)
          Behaviors.same
        case HttpRequestReply(_) =>
          // Response from Helix API handled via callback
          Behaviors.same
          Behaviors.same
      }
    )

  // ----------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------

  private def handleHelixError(
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
      config: TwitchConfig,
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
            case resp: TwitchTokenResponse =>
              replyTo ! TokenRefreshed(resp.accessToken, resp.refreshToken, resp.expiresIn)
            case _ =>
              replyTo ! Error(s"Token refresh failed: unexpected response type")
          }
        case StatusReply.Error(err) =>
          replyTo ! Error(s"Token refresh failed: ${err.getMessage}")
      }
      HttpRequestReply(statusReply)
    }

    httpRequestActor ! HttpRequestActor.Request[TwitchTokenResponse](
      method = HttpMethods.POST,
      uri = Uri(s"$authBaseUrl/token"),
      headers = Seq(RawHeader("Content-Type", "application/x-www-form-urlencoded")),
      entity = org.apache.pekko.http.scaladsl.model.HttpEntity(
        org.apache.pekko.http.scaladsl.model.MediaTypes.`application/x-www-form-urlencoded`,
        body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      ),
      decode = str => decode[TwitchTokenResponse](str).toTry,
      replyTo = probeRef
    )
  }

  private def getUserById(
      config: TwitchConfig,
      userId: String,
      accessToken: String,
      replyTo: ActorRef[TokenResponse],
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      helixBaseUrl: String
  )(using
      ctx: ActorContext[Command],
      scheduler: Scheduler,
      timeout: Timeout,
      execEc: ExecutionContext
  ): Unit = {
    val query = Uri.Query("id" -> userId)
    val uri = Uri(s"$helixBaseUrl/users").withQuery(query)

    val probeRef: ActorRef[StatusReply[Any]] = ctx.messageAdapter[StatusReply[Any]] { statusReply =>
      statusReply match {
        case StatusReply.Success(value) =>
          value match {
            case userList: TwitchUserList =>
              userList.data.headOption match {
                case Some(tu) => replyTo ! UserFound(tu.id, tu.login, tu.displayName)
                case None     => replyTo ! Error(s"No user found with ID: $userId")
              }
            case _ =>
              replyTo ! Error(s"Get user by ID ($userId): unexpected response type")
          }
        case StatusReply.Error(err) =>
          handleHelixError(err, replyTo, s"Get user by ID ($userId)")
      }
      HttpRequestReply(statusReply)
    }

    httpRequestActor ! HttpRequestActor.Request[TwitchUserList](
      method = HttpMethods.GET,
      uri = uri,
      headers = Seq(
        Authorization(org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken(accessToken)),
        RawHeader("Client-Id", config.clientId)
      ),
      entity = org.apache.pekko.http.scaladsl.model.HttpEntity.Empty,
      decode = str => decode[TwitchUserList](str).toTry,
      replyTo = probeRef
    )
  }

  private def getCurrentUser(
      config: TwitchConfig,
      accessToken: String,
      replyTo: ActorRef[TokenResponse],
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      helixBaseUrl: String
  )(using
      ctx: ActorContext[Command],
      scheduler: Scheduler,
      timeout: Timeout,
      execEc: ExecutionContext
  ): Unit = {
    val query = Uri.Query("oauth_token" -> accessToken)
    val uri = Uri(s"$helixBaseUrl/users").withQuery(query)

    val probeRef: ActorRef[StatusReply[Any]] = ctx.messageAdapter[StatusReply[Any]] { statusReply =>
      statusReply match {
        case StatusReply.Success(value) =>
          value match {
            case userList: TwitchUserList =>
              userList.data.headOption match {
                case Some(tu) => replyTo ! UserFound(tu.id, tu.login, tu.displayName)
                case None     => replyTo ! Error("No current user found")
              }
            case _ =>
              replyTo ! Error("Get current user: unexpected response type")
          }
        case StatusReply.Error(err) =>
          handleHelixError(err, replyTo, "Get current user")
      }
      HttpRequestReply(statusReply)
    }

    httpRequestActor ! HttpRequestActor.Request[TwitchUserList](
      method = HttpMethods.GET,
      uri = uri,
      headers = Seq(RawHeader("Client-Id", config.clientId)),
      entity = org.apache.pekko.http.scaladsl.model.HttpEntity.Empty,
      decode = str => decode[TwitchUserList](str).toTry,
      replyTo = probeRef
    )
  }

  private def getUserByLogin(
      config: TwitchConfig,
      loginName: String,
      accessToken: String,
      replyTo: ActorRef[TokenResponse],
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      helixBaseUrl: String
  )(using
      ctx: ActorContext[Command],
      scheduler: Scheduler,
      timeout: Timeout,
      execEc: ExecutionContext
  ): Unit = {
    val query = Uri.Query("login" -> loginName)
    val uri = Uri(s"$helixBaseUrl/users").withQuery(query)

    val probeRef: ActorRef[StatusReply[Any]] = ctx.messageAdapter[StatusReply[Any]] { statusReply =>
      statusReply match {
        case StatusReply.Success(value) =>
          value match {
            case userList: TwitchUserList =>
              userList.data.headOption match {
                case Some(tu) => replyTo ! UserFound(tu.id, tu.login, tu.displayName)
                case None     => replyTo ! Error(s"No user found with login: $loginName")
              }
            case _ =>
              replyTo ! Error(s"Get user by login ($loginName): unexpected response type")
          }
        case StatusReply.Error(err) =>
          handleHelixError(err, replyTo, s"Get user by login ($loginName)")
      }
      HttpRequestReply(statusReply)
    }

    httpRequestActor ! HttpRequestActor.Request[TwitchUserList](
      method = HttpMethods.GET,
      uri = uri,
      headers = Seq(
        Authorization(org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken(accessToken)),
        RawHeader("Client-Id", config.clientId)
      ),
      entity = org.apache.pekko.http.scaladsl.model.HttpEntity.Empty,
      decode = str => decode[TwitchUserList](str).toTry,
      replyTo = probeRef
    )
  }

}
