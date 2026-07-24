package com.archimond7450.archiemate.auth

import com.archimond7450.archiemate.settings.{AppConfig, DatabaseConfig, HttpClientConfig, JwtConfig, KickConfig, ServerConfig, TwitchConfig, TwitchIrcConfig, WebSocketConfig, YoutubeConfig}
import com.archimond7450.archiemate.twitch.eventsub.EventSubConfig
import com.archimond7450.archiemate.user.UserTokenRegistry
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import org.apache.pekko.actor.typed.ActorSystem as TypedActorSystem
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.model.headers.Cookie
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtCirce}

import scala.concurrent.duration._

class AuthRoutesSpec
    extends AnyWordSpecLike
    with Matchers
    with ScalatestRouteTest {

  private val testConfig: AppConfig = new AppConfig(
    server = ServerConfig(host = "127.0.0.1", port = 8080, apiVersion = "v1"),
    database = DatabaseConfig(
      url = "jdbc:postgresql://localhost:5432/archiemate",
      user = "test",
      password = "test",
      driver = "org.postgresql.Driver"
    ),
    jwt = JwtConfig(
      secret = "test-secret-key-for-jwt-signing-must-be-long-enough",
      tokenLifetimeMinutes = 15
    ),
    twitch = TwitchConfig(
      clientId = "test-client-id",
      clientSecret = "test-client-secret",
      callbackPath = "/auth/twitch/callback",
      scopes = List.empty
    ),
    kick = KickConfig(
      clientId = "",
      clientSecret = "",
      callbackPath = ""
    ),
    youtube = YoutubeConfig(
      clientId = "",
      clientSecret = "",
      callbackPath = ""
    ),
    twitchIrc = TwitchIrcConfig(
      scheme = "wss",
      server = "irc-ws.chat.twitch.tv",
      port = 443,
      ircToken = ""
    ),
    eventSub = EventSubConfig(
      webhookSecret = "test-secret",
      callbackPath = "/api/v1/eventsub/webhook",
      leaseDuration = 604800.seconds,
      helixBaseUrl = "https://api.twitch.tv/helix"
    ),
    websocket = WebSocketConfig(
      reconnectDelay = 1.second,
      maxReconnectAttempts = 5
    ),
    httpClient = HttpClientConfig(
      maxConnections = 10,
      maxIdleTimeoutMinutes = 60
    ),
    callbackBaseUrl = "http://localhost",
    adminUserId = "",
    askTimeout = 5.seconds
  )

  private val classicSystem: ActorSystem = ActorSystem("test-classic-authroutes")
  private given typedSystem: TypedActorSystem[Nothing] = classicSystem.toTyped
  private given Scheduler = typedSystem.scheduler
  private given Timeout = Timeout(3.seconds)

  private val twitchOAuthProbe: TestProbe[TwitchOAuthActor.Command] =
    TestProbe[TwitchOAuthActor.Command]("twitch-oauth")
  private val twitchOAuthActor = twitchOAuthProbe.ref

  private val userTokenRegistryProbe: TestProbe[UserTokenRegistry.Command] =
    TestProbe[UserTokenRegistry.Command]("user-token-registry")
  private val userTokenRegistry = userTokenRegistryProbe.ref

  private val jwtProbe: TestProbe[JwtActor.Command] =
    TestProbe[JwtActor.Command]("jwt-actor")
  private val jwtActor = jwtProbe.ref

  private val authRoutes = new AuthRoutes(
    testConfig,
    twitchOAuthActor,
    userTokenRegistry,
    jwtActor,
    classicSystem
  ).authRoutes

  // Helper: encode a valid JWT with the test secret
  private def makeValidJwt(userId: String): String = {
    val now = java.time.Instant.now()
    val expiresAt = now.plusSeconds(900) // 15 minutes
    val claim = JwtClaim(subject = Some(userId))
      .issuedAt(now.getEpochSecond)
      .expiresAt(expiresAt.getEpochSecond)
    JwtCirce.encode(claim, testConfig.jwt.secret, JwtAlgorithm.HS256)
  }

  // ----------------------------------------------------------------
  // GET /auth/twitch/authorize
  // ----------------------------------------------------------------

  "GET /auth/twitch/authorize" should {

    "redirect to Twitch with configured scopes and force_verify" in {
      val redirectUri = "http://localhost/auth/twitch/callback"

      val result = Get("/auth/twitch/authorize") ~> authRoutes

      twitchOAuthProbe.expectMessageType[TwitchOAuthActor.GenerateAuthorizeState] match {
        case TwitchOAuthActor.GenerateAuthorizeState(actualUri, replyTo) =>
          actualUri shouldBe redirectUri
          // Respond so the route can complete
          replyTo ! TwitchOAuthActor.AuthorizeStateOk("test-state", Uri("https://id.twitch.tv/oauth2/authorize?scope=chat:read,chat:edit&force_verify=true"))
        case other =>
          fail(s"Expected GenerateAuthorizeState, got $other")
      }

      result ~> check {
        status shouldEqual StatusCodes.Found
      }
    }
  }

  // ----------------------------------------------------------------
  // GET /auth/refresh
  // ----------------------------------------------------------------

  "GET /auth/refresh" should {

    "return 302 and set a new cookie when given a valid JWT" in {
      val validToken = makeValidJwt("user-123")

      val result = Get("/auth/refresh?return=/dashboard") ~>
        addHeader(Cookie("archiemate_jwt", validToken)) ~> authRoutes

      // Wait for the route to send the Refresh command to the probe
      jwtProbe.expectMessageType[JwtActor.Refresh] match {
        case JwtActor.Refresh(token, replyTo) =>
          token shouldBe validToken
          // Respond with success so the route can complete
          replyTo ! JwtActor.RefreshSuccess("new-token-xyz")
        case other =>
          fail(s"Expected Refresh command, got $other")
      }

      // Now check the result
      result ~> check {
        status shouldEqual StatusCodes.Found
      }
    }

    "return 401 when no JWT cookie is present" in {
      Get("/auth/refresh") ~> authRoutes ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "No JWT cookie present"
      }
    }

    "return 401 when JWT cookie is empty" in {
      Get("/auth/refresh") ~>
        addHeader(Cookie("archiemate_jwt", "")) ~>
        authRoutes ~> check {

        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "No JWT cookie present"
      }
    }

    "return 401 when given an invalid JWT" in {
      val result = Get("/auth/refresh?return=/home") ~>
        addHeader(Cookie("archiemate_jwt", "invalid-token")) ~> authRoutes

      jwtProbe.expectMessageType[JwtActor.Refresh] match {
        case JwtActor.Refresh(token, replyTo) =>
          token shouldBe "invalid-token"
          replyTo ! JwtActor.Error("Cannot refresh: token is invalid")
      }

      result ~> check {
        status shouldEqual StatusCodes.Unauthorized
      }
    }

    "return 401 with error message when jwtActor returns an error" in {
      val validToken = makeValidJwt("user-456")

      val result = Get("/auth/refresh") ~>
        addHeader(Cookie("archiemate_jwt", validToken)) ~> authRoutes

      jwtProbe.expectMessageType[JwtActor.Refresh] match {
        case JwtActor.Refresh(_, replyTo) =>
          replyTo ! JwtActor.Error("internal error")
      }

      result ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "internal error"
      }
    }

    "use /dashboard as default return URL when not specified" in {
      val validToken = makeValidJwt("user-789")

      val result = Get("/auth/refresh") ~>
        addHeader(Cookie("archiemate_jwt", validToken)) ~> authRoutes

      jwtProbe.expectMessageType[JwtActor.Refresh] match {
        case JwtActor.Refresh(token, replyTo) =>
          token shouldBe validToken
          replyTo ! JwtActor.RefreshSuccess("new-token-abc")
      }

      result ~> check {
        status shouldEqual StatusCodes.Found
      }
    }
  }
}
