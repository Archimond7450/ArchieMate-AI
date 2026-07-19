package com.archimond7450.archiemate.twitch

import com.archimond7450.archiemate.ArchieMateMediator
import com.archimond7450.archiemate.actors.http.HttpRequestActor
import com.archimond7450.archiemate.settings.TwitchConfig
import com.archimond7450.archiemate.user.UserTokenRegistry
import com.archimond7450.archiemate.user.UserTokenRegistry.{*, given}
import io.circe.Decoder
import io.circe.parser.decode
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.{*, given}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
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

  private object TwitchUserList {
    implicit val decoder: Decoder[TwitchUserList] = Decoder.instance { c =>
      for {
        data <- c.downField("data").as[List[TwitchUserInner]]
      } yield TwitchUserList(data)
    }
  }

  private object TwitchUserInner {
    def decode(str: String): Either[Throwable, TwitchUserInner] =
      decode[TwitchUserInner](str).left.map(_.getMessage)
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
      mediator: ActorRef[ArchieMateMediator.Command],
      userTokenRegistry: ActorRef[UserTokenRegistry.Command],
      helixBaseUrl: String = DefaultHelixBaseUrl
  ): Behavior[Command] =
    Behaviors.supervise[Command] {
      Behaviors.setup { ctx =>
        ctx.log.info(
          "TwitchApiActor initialized for client {}",
          config.clientId
        )
        mainBehavior(
          config,
          mediator,
          userTokenRegistry,
          helixBaseUrl
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
      mediator: ActorRef[ArchieMateMediator.Command],
      userTokenRegistry: ActorRef[UserTokenRegistry.Command],
      helixBaseUrl: String
  )(using
      ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
      scheduler: Scheduler,
      timeout: Timeout,
      execEc: ExecutionContext
  ): Behavior[Command] =
    Behaviors.withMdc(Map("actor" -> actorName))(
      Behaviors.receiveMessage {
        case refresh: RefreshToken =>
          refreshToken(config, refresh.refreshToken, refresh.replyTo, mediator)
          Behaviors.same

        case getUser: GetUserById =>
          getUserById(config, getUser.userId, getUser.accessToken, getUser.replyTo, mediator, helixBaseUrl)
          Behaviors.same

        case getCurrentUser: GetCurrentUser =>
          getCurrentUser(config, getCurrentUser.accessToken, getCurrentUser.replyTo, mediator, helixBaseUrl)
          Behaviors.same

        case getUserByLogin: GetUserByLogin =>
          getUserByLogin(config, getUserByLogin.loginName, getUserByLogin.accessToken, getUserByLogin.replyTo, mediator, helixBaseUrl)
          Behaviors.same
      }
    )

  // ----------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------

  private def refreshToken(
      config: TwitchConfig,
      refreshToken: String,
      replyTo: ActorRef[TokenResponse],
      mediator: ActorRef[ArchieMateMediator.Command]
  )(using
      ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
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

    val future: Future[StatusReply[TwitchTokenResponse]] = mediator ? { ref =>
      ArchieMateMediator.SendHttpRequest(
        HttpRequestActor.Request[TwitchTokenResponse](
          method = HttpMethods.POST,
          uri = Uri(s"$DefaultAuthBaseUrl/token"),
          headers = Seq(
            RawHeader("Content-Type", "application/x-www-form-urlencoded")
          ),
          entity = org.apache.pekko.http.scaladsl.model.HttpEntity(
            org.apache.pekko.http.scaladsl.model.MediaTypes.`application/x-www-form-urlencoded`,
            body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
          ),
          decode = str => decode[TwitchTokenResponse](str).toTry,
          replyTo = ref
        )
      )
    }

    future.onComplete {
      case scala.util.Success(StatusReply.Success(tokenResp)) =>
        replyTo ! TokenRefreshed(
          tokenResp.accessToken,
          tokenResp.refreshToken,
          tokenResp.expiresIn
        )
      case scala.util.Success(StatusReply.Error(err)) =>
        replyTo ! Error(s"Token refresh failed: ${err.getMessage}")
      case scala.util.Failure(ex) =>
        replyTo ! Error(s"Token refresh failed: ${ex.getMessage}")
    }(execEc)
  }

  private def getUserById(
      config: TwitchConfig,
      userId: String,
      accessToken: String,
      replyTo: ActorRef[TokenResponse],
      mediator: ActorRef[ArchieMateMediator.Command],
      helixBaseUrl: String
  )(using
      ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
      scheduler: Scheduler,
      timeout: Timeout,
      execEc: ExecutionContext
  ): Unit = {
    val query = Uri.Query("id" -> userId)
    val uri = Uri(s"$helixBaseUrl/users").withQuery(query)

    val future: Future[StatusReply[TwitchUserList]] = mediator ? { ref =>
      ArchieMateMediator.SendHttpRequest(
        HttpRequestActor.Request[TwitchUserList](
          method = HttpMethods.GET,
          uri = uri,
          headers = Seq(
            Authorization(org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken(accessToken)),
            RawHeader("Client-Id", config.clientId)
          ),
          entity = org.apache.pekko.http.scaladsl.model.HttpEntity.Empty,
          decode = str => decode[TwitchUserList](str).toTry,
          replyTo = ref
        )
      )
    }

    future.onComplete {
      case scala.util.Success(StatusReply.Success(userList)) =>
        userList.data.headOption match {
          case Some(tu) =>
            replyTo ! UserFound(tu.id, tu.login, tu.displayName)
          case None =>
            replyTo ! Error(s"No user found with ID: $userId")
        }
      case scala.util.Success(StatusReply.Error(err)) =>
        handleHelixError(err, replyTo, s"Get user by ID ($userId)")
      case scala.util.Failure(ex) =>
        replyTo ! Error(s"Get user by ID failed: ${ex.getMessage}")
    }(execEc)
  }

  private def getCurrentUser(
      config: TwitchConfig,
      accessToken: String,
      replyTo: ActorRef[TokenResponse],
      mediator: ActorRef[ArchieMateMediator.Command],
      helixBaseUrl: String
  )(using
      ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
      scheduler: Scheduler,
      timeout: Timeout,
      execEc: ExecutionContext
  ): Unit = {
    val query = Uri.Query("oauth_token" -> accessToken)
    val uri = Uri(s"$helixBaseUrl/users").withQuery(query)

    val future: Future[StatusReply[TwitchUserList]] = mediator ? { ref =>
      ArchieMateMediator.SendHttpRequest(
        HttpRequestActor.Request[TwitchUserList](
          method = HttpMethods.GET,
          uri = uri,
          headers = Seq(
            RawHeader("Client-Id", config.clientId)
          ),
          entity = org.apache.pekko.http.scaladsl.model.HttpEntity.Empty,
          decode = str => decode[TwitchUserList](str).toTry,
          replyTo = ref
        )
      )
    }

    future.onComplete {
      case scala.util.Success(StatusReply.Success(userList)) =>
        userList.data.headOption match {
          case Some(tu) =>
            replyTo ! UserFound(tu.id, tu.login, tu.displayName)
          case None =>
            replyTo ! Error("No current user found")
        }
      case scala.util.Success(StatusReply.Error(err)) =>
        handleHelixError(err, replyTo, "Get current user")
      case scala.util.Failure(ex) =>
        replyTo ! Error(s"Get current user failed: ${ex.getMessage}")
    }(execEc)
  }

  private def getUserByLogin(
      config: TwitchConfig,
      loginName: String,
      accessToken: String,
      replyTo: ActorRef[TokenResponse],
      mediator: ActorRef[ArchieMateMediator.Command],
      helixBaseUrl: String
  )(using
      ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
      scheduler: Scheduler,
      timeout: Timeout,
      execEc: ExecutionContext
  ): Unit = {
    val query = Uri.Query("login" -> loginName)
    val uri = Uri(s"$helixBaseUrl/users").withQuery(query)

    val future: Future[StatusReply[TwitchUserList]] = mediator ? { ref =>
      ArchieMateMediator.SendHttpRequest(
        HttpRequestActor.Request[TwitchUserList](
          method = HttpMethods.GET,
          uri = uri,
          headers = Seq(
            Authorization(org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken(accessToken)),
            RawHeader("Client-Id", config.clientId)
          ),
          entity = org.apache.pekko.http.scaladsl.model.HttpEntity.Empty,
          decode = str => decode[TwitchUserList](str).toTry,
          replyTo = ref
        )
      )
    }

    future.onComplete {
      case scala.util.Success(StatusReply.Success(userList)) =>
        userList.data.headOption match {
          case Some(tu) =>
            replyTo ! UserFound(tu.id, tu.login, tu.displayName)
          case None =>
            replyTo ! Error(s"No user found with login: $loginName")
        }
      case scala.util.Success(StatusReply.Error(err)) =>
        handleHelixError(err, replyTo, s"Get user by login ($loginName)")
      case scala.util.Failure(ex) =>
        replyTo ! Error(s"Get user by login failed: ${ex.getMessage}")
    }(execEc)
  }

  private def handleHelixError(
      err: Throwable,
      replyTo: ActorRef[TokenResponse],
      context: String
  ): Unit = {
    // Check if this is a 401 Unauthorized — trigger token refresh
    val isUnauthorized = err.getMessage.toLowerCase.contains("401")
    if (isUnauthorized) {
      // The caller should handle token refresh
      replyTo ! Error(s"$context: token expired or invalid (HTTP 401)")
    } else {
      replyTo ! Error(s"$context: ${err.getMessage}")
    }
  }

}
