package com.archimond7450.archiemate.auth

import com.archimond7450.archiemate.actors.http.HttpRequestActor
import com.archimond7450.archiemate.settings.KickConfig
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

/** Handles Kick OAuth flow: state generation, token exchange, and
  * redirect URL construction.
  *
 * @note
  * This actor must be supervised by its parent with a resume strategy on
  * [[Throwable]] to preserve state across failures:
  * {{{supervise(KickOAuthActor(...)).onFailure[Throwable](SupervisorStrategy.resume)}}}
  */
object KickOAuthActor {

  private val actorName = "kick-oauth-actor"

  // ----------------------------------------------------------------
  // Commands
  // ----------------------------------------------------------------

  sealed trait Command

  /** Internal command to signal HTTP response received. */
  private final case class HttpRequestReply(reply: StatusReply[Any]) extends Command

  /** Generate an OAuth state and return the Kick authorization URL.
    *
    * @param scopes
    *   scopes to request (empty = default scopes)
    */
  final case class GenerateState(
      redirectUri: String,
      replyTo: ActorRef[StateGenerated],
      scopes: List[String] = Nil
  ) extends Command

  /** Exchange an authorization code for Kick tokens. */
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

  /** Generate an OAuth state and return the Kick authorization URL with
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
      username: String,
      profileImage: Option[String],
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

  private case class KickTokenResponse(
      accessToken: String,
      refreshToken: String,
      expiresIn: Long,
      scope: String,
      tokenType: String
  )

  private object KickTokenResponse {
    implicit val decoder: Decoder[KickTokenResponse] = Decoder.forProduct5(
      "access_token", "refresh_token", "expires_in", "scope", "token_type"
    )(KickTokenResponse.apply)
  }

  // ----------------------------------------------------------------
  // User info response model
  // ----------------------------------------------------------------

  private case class KickUserInfo(
      id: String,
      username: String,
      profileImage: Option[KickProfileImage]
  )

  private case class KickProfileImage(url: String)

  private object KickProfileImage {
    implicit val decoder: Decoder[KickProfileImage] = Decoder.forProduct1("url")(KickProfileImage.apply)
  }

  private object KickUserInfo {
    implicit val decoder: Decoder[KickUserInfo] = Decoder.instance { c =>
      for {
        id <- c.downField("id").as[String]
        username <- c.downField("username").as[String]
        profileImage <- c.downField("profile_image").as[Option[KickProfileImage]]
      } yield KickUserInfo(id, username, profileImage)
    }
  }

  // ----------------------------------------------------------------
  // Default Kick OAuth base URL (overridable for testing)
  // ----------------------------------------------------------------

  val DefaultAuthBaseUrl = "https://kick.com/oauth2"
  val DefaultUserInfoBaseUrl = "https://kick.com/oauth2"

  // ----------------------------------------------------------------
  // Default Kick scopes
  // ----------------------------------------------------------------

  val DefaultScopes = List("channels.read", "offline_access")

  // ----------------------------------------------------------------
  // Factory
  // ----------------------------------------------------------------

  def apply(
      config: KickConfig,
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      userTokenRegistry: ActorRef[UserTokenRegistry.Command],
      authBaseUrl: String = DefaultAuthBaseUrl,
      userInfoBaseUrl: String = DefaultUserInfoBaseUrl
  ): Behavior[Command] =
    Behaviors.supervise[Command] {
      Behaviors.setup { ctx =>
        ctx.log.info("KickOAuthActor initialized for client {}", config.clientId)
        mainBehavior(
          State(Map.empty),
          config,
          httpRequestActor,
          userTokenRegistry,
          authBaseUrl,
          userInfoBaseUrl
        )(using ctx, scheduler = ctx.system.scheduler, timeout = Timeout(5.seconds), execEc = scala.concurrent.ExecutionContext.global)
      }
    }.onFailure[Throwable](SupervisorStrategy.resume)

  // ----------------------------------------------------------------
  // Behavior
  // ----------------------------------------------------------------

  private def mainBehavior(
      state: State,
      config: KickConfig,
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      userTokenRegistry: ActorRef[UserTokenRegistry.Command],
      authBaseUrl: String,
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
          mainBehavior(newState, config, httpRequestActor, userTokenRegistry, authBaseUrl, userInfoBaseUrl)

        case GenerateAuthorizeState(redirectUri, replyTo) =>
          val oauthState = UUID.randomUUID().toString
          val expiresAt = Instant.now().plusSeconds(300) // 5 min TTL
          val effectiveScopes = if (config.scopes.nonEmpty) config.scopes else DefaultScopes
          val newState = state.copy(store = state.store + (oauthState -> OAuthState(redirectUri, expiresAt, "authorize")))
          val authUrl = buildAuthUrl(config, oauthState, redirectUri, effectiveScopes, forceVerify = true)
          replyTo ! AuthorizeStateOk(oauthState, authUrl)
          mainBehavior(newState, config, httpRequestActor, userTokenRegistry, authBaseUrl, userInfoBaseUrl)

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
              exchangeCode(config, oauthState.redirectUri, code, httpRequestActor, replyTo, authBaseUrl, userInfoBaseUrl, oauthState.flow)
              mainBehavior(newState, config, httpRequestActor, userTokenRegistry, authBaseUrl, userInfoBaseUrl)
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
      config: KickConfig,
      state: String,
      redirectUri: String,
      scopes: List[String],
      forceVerify: Boolean = false
  ): Uri = {
    val baseParams = Seq(
      "client_id" -> config.clientId,
      "redirect_uri" -> redirectUri,
      "response_type" -> "code",
      "state" -> state,
      "scope" -> scopes.mkString(",")
    )
    val finalParams = if (forceVerify) {
      baseParams :+ ("force_verify" -> "true")
    } else {
      baseParams
    }
    Uri("https://kick.com/oauth2/authorize").withQuery(Query(finalParams*))
  }

  private def exchangeCode(
      config: KickConfig,
      redirectUri: String,
      code: String,
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      replyTo: ActorRef[TokenExchangeResponse],
      authBaseUrl: String,
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
            case resp: KickTokenResponse =>
              fetchKickUser(config.clientId, resp.accessToken, replyTo, httpRequestActor, userInfoBaseUrl, flow)
            case _ =>
              replyTo ! TokenExchangeError("Token exchange failed: unexpected response type")
          }
        case StatusReply.Error(err) =>
          replyTo ! TokenExchangeError(s"Token exchange failed: ${err.getMessage}")
      }
      HttpRequestReply(statusReply)
    }

    httpRequestActor ! HttpRequestActor.Request[KickTokenResponse](
      method = HttpMethods.POST,
      uri = Uri(s"$authBaseUrl/token"),
      headers = Seq(RawHeader("Content-Type", "application/x-www-form-urlencoded")),
      entity = HttpEntity(`application/x-www-form-urlencoded`, body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
      decode = str => decode[KickTokenResponse](str).toTry,
      replyTo = probeRef
    )
  }

  private def fetchKickUser(
      clientId: String,
      accessToken: String,
      replyTo: ActorRef[TokenExchangeResponse],
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      userInfoBaseUrl: String,
      flow: String
  )(using ctx: ActorContext[Command], scheduler: Scheduler, timeout: Timeout, execEc: ExecutionContext): Unit = {
    val query = Uri.Query("access_token" -> accessToken)
    val uri = Uri(s"$userInfoBaseUrl/userinfo").withQuery(query)

    val probeRef: ActorRef[StatusReply[Any]] = ctx.messageAdapter[StatusReply[Any]] { statusReply =>
      statusReply match {
        case StatusReply.Success(value) =>
          value match {
            case user: KickUserInfo =>
              val profileImageUrl = user.profileImage.map(_.url)
              replyTo ! TokenExchangeSuccess(
                accessToken = accessToken,
                refreshToken = "",
                expiresIn = 0,
                platformUserId = user.id,
                username = user.username,
                profileImage = profileImageUrl,
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

    httpRequestActor ! HttpRequestActor.Request[KickUserInfo](
      method = HttpMethods.GET,
      uri = uri,
      headers = Seq(RawHeader("X-Client-ID", clientId)),
      entity = HttpEntity.Empty,
      decode = str => decode[KickUserInfo](str).toTry,
      replyTo = probeRef
    )
  }

}
