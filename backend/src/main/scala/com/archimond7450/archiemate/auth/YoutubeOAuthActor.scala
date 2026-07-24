package com.archimond7450.archiemate.auth

import com.archimond7450.archiemate.actors.http.HttpRequestActor
import com.archimond7450.archiemate.settings.YoutubeConfig
import com.archimond7450.archiemate.user.UserTokenRegistry
import io.circe.Decoder
import io.circe.parser.decode
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.http.scaladsl.model.HttpMethods
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.model.Uri.Query
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.MediaTypes.`application/x-www-form-urlencoded`
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.util.Timeout

import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext
import java.time.Instant
import java.util.UUID

/** Handles YouTube OAuth flow: state generation, token exchange, and
  * redirect URL construction.
  *
 * @note
  * This actor must be supervised by its parent with a resume strategy on
  * [[Throwable]] to preserve state across failures:
  * {{{supervise(YoutubeOAuthActor(...)).onFailure[Throwable](SupervisorStrategy.resume)}}}
  */
object YoutubeOAuthActor {

  private val actorName = "youtube-oauth-actor"

  // ----------------------------------------------------------------
  // Commands
  // ----------------------------------------------------------------

  sealed trait Command

  /** Internal command to signal HTTP response received. */
  private final case class HttpRequestReply(reply: StatusReply[Any]) extends Command

  /** Generate an OAuth state and return the YouTube authorization URL.
    *
    * @param scopes
    *   scopes to request (empty = default scopes)
    */
  final case class GenerateState(
      redirectUri: String,
      replyTo: ActorRef[StateGenerated],
      scopes: List[String] = Nil
  ) extends Command

  /** Exchange an authorization code for YouTube tokens. */
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

  /** Generate an OAuth state and return the YouTube authorization URL with
    * configured scopes and force_verify=true (for re-authorization). */
  final case class GenerateAuthorizeState(
      redirectUri: String,
      replyTo: ActorRef[AuthorizeStateGenerated]
  ) extends Command

  sealed trait AuthorizeStateGenerated
  final case class AuthorizeStateOk(state: String, authUrl: Uri) extends AuthorizeStateGenerated

  sealed trait TokenExchangeResponse
  final case class TokenExchangeSuccess(
      accessToken: String,
      refreshToken: String,
      expiresIn: Long,
      platformUserId: String,
      flow: String
  ) extends TokenExchangeResponse
  final case class TokenExchangeError(message: String) extends TokenExchangeResponse

  // ----------------------------------------------------------------
  // Internal state
  // ----------------------------------------------------------------

  private case class OAuthState(
      redirectUri: String,
      expiresAt: Instant,
      flow: String
  )

  private case class State(store: Map[String, OAuthState])

  // ----------------------------------------------------------------
  // Token exchange response model
  // ----------------------------------------------------------------

  private case class YoutubeTokenResponse(
      accessToken: String,
      refreshToken: String,
      expiresIn: Long,
      scope: String,
      tokenType: String
  )

  private object YoutubeTokenResponse {
    implicit val decoder: Decoder[YoutubeTokenResponse] = Decoder.forProduct5(
      "access_token", "refresh_token", "expires_in", "scope", "token_type"
    )(YoutubeTokenResponse.apply)
  }

  // ----------------------------------------------------------------
  // User info response model
  // ----------------------------------------------------------------

  private case class YoutubeUserInfo(
      id: String,
      email: String,
      verifiedEmail: Boolean,
      name: String,
      picture: Option[String]
  )

  private object YoutubeUserInfo {
    implicit val decoder: Decoder[YoutubeUserInfo] = Decoder.instance { c =>
      for {
        id <- c.downField("id").as[String]
        email <- c.downField("email").as[String]
        verifiedEmail <- c.downField("verified_email").as[Boolean]
        name <- c.downField("name").as[String]
        picture <- c.downField("picture").as[Option[String]]
      } yield YoutubeUserInfo(id, email, verifiedEmail, name, picture)
    }
  }

  // ----------------------------------------------------------------
  // Default YouTube OAuth base URL (overridable for testing)
  // ----------------------------------------------------------------

  val DefaultAuthBaseUrl = "https://accounts.google.com/o/oauth2/v2/auth"
  val DefaultTokenBaseUrl = "https://oauth2.googleapis.com/token"
  val DefaultUserInfoBaseUrl = "https://www.googleapis.com/oauth2/v3/userinfo"

  // ----------------------------------------------------------------
  // Default YouTube scopes for chatbot integration
  // ----------------------------------------------------------------

  val DefaultScopes = List(
    "https://www.googleapis.com/auth/youtube.force-ssl",
    "https://www.googleapis.com/auth/youtube.upload",
    "https://www.googleapis.com/auth/youtubepartner",
    "openid",
    "email"
  )

  // ----------------------------------------------------------------
  // Factory
  // ----------------------------------------------------------------

  def apply(
      config: YoutubeConfig,
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      userTokenRegistry: ActorRef[UserTokenRegistry.Command],
      authBaseUrl: String = DefaultAuthBaseUrl,
      tokenBaseUrl: String = DefaultTokenBaseUrl,
      userInfoBaseUrl: String = DefaultUserInfoBaseUrl
  ): Behavior[Command] =
    Behaviors.supervise[Command] {
      Behaviors.setup { ctx =>
        ctx.log.info("YoutubeOAuthActor initialized for client {}", config.clientId)
        mainBehavior(
          State(Map.empty),
          config,
          httpRequestActor,
          userTokenRegistry,
          authBaseUrl,
          tokenBaseUrl,
          userInfoBaseUrl
        )(using ctx, scheduler = ctx.system.scheduler, timeout = Timeout(5.seconds), execEc = scala.concurrent.ExecutionContext.global)
      }
    }.onFailure[Throwable](SupervisorStrategy.resume)

  // ----------------------------------------------------------------
  // Behavior
  // ----------------------------------------------------------------

  private def mainBehavior(
      state: State,
      config: YoutubeConfig,
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      userTokenRegistry: ActorRef[UserTokenRegistry.Command],
      authBaseUrl: String,
      tokenBaseUrl: String,
      userInfoBaseUrl: String
  )(using ctx: ActorContext[Command], scheduler: Scheduler, timeout: Timeout, execEc: ExecutionContext): Behavior[Command] =
    Behaviors.withMdc {
      Map("actor" -> actorName)
    }(
      Behaviors.receiveMessage {
        case GenerateState(redirectUri, replyTo, scopes) =>
          val oauthState = UUID.randomUUID().toString
          val expiresAt = Instant.now().plusSeconds(300) // 5 min TTL
          val effectiveScopes = if (scopes.nonEmpty) scopes else DefaultScopes
          val newState = state.copy(store = state.store + (oauthState -> OAuthState(redirectUri, expiresAt, "login")))
          val authUrl = buildAuthUrl(config, oauthState, redirectUri, effectiveScopes)
          replyTo ! StateOk(oauthState, authUrl)
          mainBehavior(newState, config, httpRequestActor, userTokenRegistry, authBaseUrl, tokenBaseUrl, userInfoBaseUrl)

        case GenerateAuthorizeState(redirectUri, replyTo) =>
          val oauthState = UUID.randomUUID().toString
          val expiresAt = Instant.now().plusSeconds(300) // 5 min TTL
          val effectiveScopes = if (config.scopes.nonEmpty) config.scopes else DefaultScopes
          val newState = state.copy(store = state.store + (oauthState -> OAuthState(redirectUri, expiresAt, "authorize")))
          val authUrl = buildAuthUrl(config, oauthState, redirectUri, effectiveScopes, includeLoginHint = false)
          replyTo ! AuthorizeStateOk(oauthState, authUrl)
          mainBehavior(newState, config, httpRequestActor, userTokenRegistry, authBaseUrl, tokenBaseUrl, userInfoBaseUrl)

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
              exchangeCode(config, oauthState.redirectUri, code, httpRequestActor, replyTo, tokenBaseUrl, userInfoBaseUrl, oauthState.flow)
              mainBehavior(newState, config, httpRequestActor, userTokenRegistry, authBaseUrl, tokenBaseUrl, userInfoBaseUrl)
          }

        case HttpRequestReply(_) =>
          // Internal reply from message adapter — should not reach here
          Behaviors.same
      }
    )

  // ----------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------

  private def buildAuthUrl(
      config: YoutubeConfig,
      state: String,
      redirectUri: String,
      scopes: List[String],
      includeLoginHint: Boolean = false
  ): Uri = {
    val baseParams = Seq(
      "client_id" -> config.clientId,
      "redirect_uri" -> redirectUri,
      "response_type" -> "code",
      "state" -> state,
      "scope" -> scopes.mkString(" "),
      "access_type" -> "offline",
      "prompt" -> "consent"
    )
    val finalParams = if (includeLoginHint) {
      baseParams :+ ("include_granted_scopes" -> "true")
    } else {
      baseParams
    }
    Uri(DefaultAuthBaseUrl).withQuery(Query(finalParams*))
  }

  private def exchangeCode(
      config: YoutubeConfig,
      redirectUri: String,
      code: String,
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      replyTo: ActorRef[TokenExchangeResponse],
      tokenBaseUrl: String,
      userInfoBaseUrl: String,
      flow: String
  )(using ctx: ActorContext[Command], scheduler: Scheduler, timeout: Timeout, execEc: ExecutionContext): Unit = {
    // Build form-encoded body for token exchange
    val body = Seq(
      s"client_id=${config.clientId}",
      s"client_secret=${config.clientSecret}",
      s"code=${code}",
      s"grant_type=authorization_code",
      s"redirect_uri=${redirectUri}"
    ).mkString("&")

    val probeRef: ActorRef[StatusReply[Any]] = ctx.messageAdapter[StatusReply[Any]] { statusReply =>
      statusReply match {
        case StatusReply.Success(value) =>
          value match {
            case resp: YoutubeTokenResponse =>
              fetchYoutubeUser(config.clientId, resp.accessToken, replyTo, httpRequestActor, userInfoBaseUrl, flow)
            case _ =>
              replyTo ! TokenExchangeError("Token exchange failed: unexpected response type")
          }
        case StatusReply.Error(err) =>
          replyTo ! TokenExchangeError(s"Token exchange failed: ${err.getMessage}")
      }
      HttpRequestReply(statusReply)
    }

    httpRequestActor ! HttpRequestActor.Request[YoutubeTokenResponse](
      method = HttpMethods.POST,
      uri = Uri(tokenBaseUrl),
      headers = Seq(RawHeader("Content-Type", "application/x-www-form-urlencoded")),
      entity = HttpEntity(`application/x-www-form-urlencoded`, body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
      decode = str => decode[YoutubeTokenResponse](str).toTry,
      replyTo = probeRef
    )
  }

  private def fetchYoutubeUser(
      clientId: String,
      accessToken: String,
      replyTo: ActorRef[TokenExchangeResponse],
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      userInfoBaseUrl: String,
      flow: String
  )(using ctx: ActorContext[Command], scheduler: Scheduler, timeout: Timeout, execEc: ExecutionContext): Unit = {
    val query = Uri.Query("access_token" -> accessToken)
    val uri = Uri(userInfoBaseUrl).withQuery(query)

    val probeRef: ActorRef[StatusReply[Any]] = ctx.messageAdapter[StatusReply[Any]] { statusReply =>
      statusReply match {
        case StatusReply.Success(value) =>
          value match {
            case user: YoutubeUserInfo =>
              replyTo ! TokenExchangeSuccess(
                accessToken = accessToken,
                refreshToken = "",
                expiresIn = 0,
                platformUserId = user.id,
                flow = flow
              )
            case _ =>
              replyTo ! TokenExchangeError("Get user info: unexpected response type")
          }
        case StatusReply.Error(err) =>
          replyTo ! TokenExchangeError(s"Failed to get user info: ${err.getMessage}")
      }
      HttpRequestReply(statusReply)
    }

    httpRequestActor ! HttpRequestActor.Request[YoutubeUserInfo](
      method = HttpMethods.GET,
      uri = uri,
      headers = Seq(RawHeader("Authorization", s"Bearer $accessToken")),
      entity = HttpEntity.Empty,
      decode = str => decode[YoutubeUserInfo](str).toTry,
      replyTo = probeRef
    )
  }

}
