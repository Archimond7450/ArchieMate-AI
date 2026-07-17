package com.archimond7450.archiemate.auth

import com.archimond7450.archiemate.ArchieMateMediator
import com.archimond7450.archiemate.http.HttpClientActor
import com.archimond7450.archiemate.settings.TwitchConfig
import io.circe.Decoder
import io.circe.parser.decode
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.{*, given}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.model.HttpMethods
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.MediaTypes.`application/x-www-form-urlencoded`
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.headers.Authorization
import org.apache.pekko.http.scaladsl.model.Uri.Query
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.util.Timeout

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

/** Handles Twitch OAuth flow: state generation, token exchange, and
  * redirect URL construction.
  *
 * @note
  * This actor must be supervised by its parent with a resume strategy on
  * [[Throwable]] to preserve state across failures:
  * {{{supervise(TwitchOAuthActor(...)).onFailure[Throwable](SupervisorStrategy.resume)}}}
  */
object TwitchOAuthActor {

  private val actorName = "twitch-oauth-actor"

  // ----------------------------------------------------------------
  // Commands
  // ----------------------------------------------------------------

  sealed trait Command

  /** Generate an OAuth state and return the Twitch authorization URL. */
  final case class GenerateState(
      redirectUri: String,
      replyTo: ActorRef[StateGenerated]
  ) extends Command

  /** Exchange an authorization code for Twitch tokens. */
  final case class ExchangeCode(
      code: String,
      replyTo: ActorRef[TokenExchangeResponse]
  ) extends Command

  // ----------------------------------------------------------------
  // Responses
  // ----------------------------------------------------------------

  sealed trait StateGenerated
  final case class StateOk(state: String, authUrl: Uri) extends StateGenerated
  final case class StateError(message: String) extends StateGenerated

  sealed trait TokenExchangeResponse
  final case class TokenExchangeSuccess(
      accessToken: String,
      refreshToken: String,
      expiresIn: Long,
      platformUserId: String
  ) extends TokenExchangeResponse
  final case class TokenExchangeError(message: String) extends TokenExchangeResponse

  // ----------------------------------------------------------------
  // Internal state
  // ----------------------------------------------------------------

  private case class OAuthState(
      redirectUri: String,
      expiresAt: Instant
  )

  private case class State(store: Map[String, OAuthState])

  // ----------------------------------------------------------------
  // Token exchange response model
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
      "access_token", "refresh_token", "expires_in", "scope", "token_type"
    )(TwitchTokenResponse.apply)
  }

  private case class TwitchHelixUserList(
      data: List[TwitchUser]
  )

  private case class TwitchUser(
      id: String,
      login: String,
      displayName: String
  )

  private object TwitchHelixUserList {
    implicit val decoder: Decoder[TwitchHelixUserList] = Decoder.instance { c =>
      for {
        data <- c.downField("data").as[List[TwitchUser]]
      } yield TwitchHelixUserList(data)
    }
  }

  private object TwitchUser {
    implicit val decoder: Decoder[TwitchUser] = Decoder.forProduct3("id", "login", "display_name")(TwitchUser.apply)
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
      authBaseUrl: String = DefaultAuthBaseUrl,
      helixBaseUrl: String = DefaultHelixBaseUrl
  ): Behavior[Command] =
    Behaviors.supervise[Command] {
      Behaviors.setup { ctx =>
        ctx.log.info("TwitchOAuthActor initialized for client {}", config.clientId)
        mainBehavior(State(Map.empty), config, mediator, authBaseUrl, helixBaseUrl)(using ctx, scheduler = ctx.system.scheduler, timeout = Timeout(5.seconds), execEc = scala.concurrent.ExecutionContext.global)
      }
    }.onFailure[Throwable](SupervisorStrategy.resume)

  // ----------------------------------------------------------------
  // Behavior
  // ----------------------------------------------------------------

  private def mainBehavior(
      state: State,
      config: TwitchConfig,
      mediator: ActorRef[ArchieMateMediator.Command],
      authBaseUrl: String,
      helixBaseUrl: String
  )(using ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command], scheduler: Scheduler, timeout: Timeout, execEc: ExecutionContext): Behavior[Command] =
    Behaviors.withMdc {
      Map("actor" -> actorName)
    }(
      Behaviors.receiveMessage {
        case GenerateState(redirectUri, replyTo) =>
          val oauthState = UUID.randomUUID().toString
          val expiresAt = Instant.now().plusSeconds(300) // 5 min TTL
          val newState = state.copy(store = state.store + (oauthState -> OAuthState(redirectUri, expiresAt)))
          val authUrl = buildAuthUrl(config, oauthState, redirectUri)
          replyTo ! StateOk(oauthState, authUrl)
          mainBehavior(newState, config, mediator, authBaseUrl, helixBaseUrl)

        case ExchangeCode(code, replyTo) =>
          state.store.get(code) match {
            case None =>
              replyTo ! TokenExchangeError("Invalid or expired OAuth state")
              Behaviors.same
            case Some(oauthState) if oauthState.expiresAt.isBefore(Instant.now()) =>
              replyTo ! TokenExchangeError("OAuth state has expired")
              Behaviors.same
            case Some(oauthState) =>
              // Valid state — remove it (one-time use) and exchange the code
              val newState = state.copy(store = state.store - code)
              exchangeCode(config, oauthState.redirectUri, code, mediator, replyTo, authBaseUrl, helixBaseUrl)
              mainBehavior(newState, config, mediator, authBaseUrl, helixBaseUrl)
          }
      }
    )

  // ----------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------

  private def buildAuthUrl(
      config: TwitchConfig,
      state: String,
      redirectUri: String
  ): Uri = {
    val scopes = config.scopes.mkString(",")
    val query = Query(
      "client_id" -> config.clientId,
      "redirect_uri" -> redirectUri,
      "response_type" -> "code",
      "scope" -> scopes,
      "state" -> state
    )
    Uri("https://id.twitch.tv/oauth2/authorize").withQuery(query)
  }

  private def exchangeCode(
      config: TwitchConfig,
      redirectUri: String,
      code: String,
      mediator: ActorRef[ArchieMateMediator.Command],
      replyTo: ActorRef[TokenExchangeResponse],
      authBaseUrl: String,
      helixBaseUrl: String
  )(using ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command], scheduler: Scheduler, timeout: Timeout, execEc: ExecutionContext): Unit = {
    // Build form-encoded body for token exchange
    val body = Seq(
      s"client_id=${config.clientId}",
      s"client_secret=${config.clientSecret}",
      s"code=${code}",
      s"grant_type=authorization_code",
      s"redirect_uri=${redirectUri}"
    ).mkString("&")

    // Use ask pattern to send the request and receive the response
    val future: Future[StatusReply[HttpClientActor.Response]] = mediator ? { ref =>
      ArchieMateMediator.SendHttpClientRequest(
        HttpClientActor.SendRequest(
          method = HttpMethods.POST,
          uri = Uri(s"$authBaseUrl/token"),
          headers = Seq(
            RawHeader("Content-Type", "application/x-www-form-urlencoded")
          ),
          entity = HttpEntity(`application/x-www-form-urlencoded`, body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          replyTo = ref
        )
      )
    }

    future.onComplete {
      case scala.util.Success(resp) =>
        resp match {
          case StatusReply.Success(inner: HttpClientActor.Response) =>
            parseTokenResponse(inner.entityString, config.clientId, replyTo, mediator, helixBaseUrl)
          case StatusReply.Error(err) =>
            replyTo ! TokenExchangeError(s"Token exchange failed: ${err.getMessage}")
        }
      case scala.util.Failure(ex) =>
        replyTo ! TokenExchangeError(s"Token exchange failed: ${ex.getMessage}")
    }(execEc)
  }

  private def parseTokenResponse(
      entityString: String,
      clientId: String,
      replyTo: ActorRef[TokenExchangeResponse],
      mediator: ActorRef[ArchieMateMediator.Command],
      helixBaseUrl: String
  )(using ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command], scheduler: Scheduler, timeout: Timeout, execEc: ExecutionContext): Unit = {
    decode[TwitchTokenResponse](entityString) match {
      case Right(tokenResp) =>
        // Fetch user info with the access token
        fetchTwitchUser(tokenResp, clientId, replyTo, mediator, helixBaseUrl)
      case Left(err) =>
        replyTo ! TokenExchangeError(s"Failed to parse token response: ${err.getMessage}")
    }
  }

  private def fetchTwitchUser(
      tokenResp: TwitchTokenResponse,
      clientId: String,
      replyTo: ActorRef[TokenExchangeResponse],
      mediator: ActorRef[ArchieMateMediator.Command],
      helixBaseUrl: String
  )(using ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command], scheduler: Scheduler, timeout: Timeout, execEc: ExecutionContext): Unit = {
    val future: Future[StatusReply[HttpClientActor.Response]] = mediator ? { ref =>
      ArchieMateMediator.SendHttpClientRequest(
        HttpClientActor.SendRequest(
          method = HttpMethods.GET,
          uri = Uri(s"$helixBaseUrl/users"),
          headers = Seq(
            Authorization(org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken(tokenResp.accessToken)),
            RawHeader("Client-Id", clientId)
          ),
          entity = HttpEntity.Empty,
          replyTo = ref
        )
      )
    }

    future.onComplete {
      case scala.util.Success(resp) =>
        resp match {
          case StatusReply.Success(inner: HttpClientActor.Response) =>
            decode[TwitchHelixUserList](inner.entityString) match {
              case Right(userList) if userList.data.nonEmpty =>
                val user = userList.data.head
                replyTo ! TokenExchangeSuccess(
                  accessToken = tokenResp.accessToken,
                  refreshToken = tokenResp.refreshToken,
                  expiresIn = tokenResp.expiresIn,
                  platformUserId = user.id
                )
              case Right(_) =>
                replyTo ! TokenExchangeError("No user data returned from Twitch")
              case Left(err) =>
                replyTo ! TokenExchangeError(s"Failed to parse user response: ${err.getMessage}")
            }
          case StatusReply.Error(err) =>
            replyTo ! TokenExchangeError(s"Failed to get user info: ${err.getMessage}")
        }
      case scala.util.Failure(ex) =>
        replyTo ! TokenExchangeError(s"Failed to get user info: ${ex.getMessage}")
    }(execEc)
  }

}
