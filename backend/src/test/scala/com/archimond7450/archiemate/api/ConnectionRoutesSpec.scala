package com.archimond7450.archiemate.api

import com.archimond7450.archiemate.auth.JwtActor
import com.archimond7450.archiemate.settings.{AppConfig, DatabaseConfig, HttpClientConfig, JwtConfig, KickConfig, ServerConfig, TwitchConfig, YoutubeConfig}
import com.archimond7450.archiemate.user.UserTokenRegistry
import com.archimond7450.archiemate.user.UserTokenRegistry.{*, given}
import com.archimond7450.archiemate.user.UserTokenActor
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import org.apache.pekko.actor.typed.ActorSystem as TypedActorSystem
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.Cookie
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtCirce}

import scala.concurrent.duration._

class ConnectionRoutesSpec
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
      clientId = "",
      clientSecret = "",
      callbackPath = "",
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
    httpClient = HttpClientConfig(
      maxConnections = 10,
      maxIdleTimeoutMinutes = 60
    ),
    callbackBaseUrl = "http://localhost",
    adminUserId = "",
    askTimeout = 5.seconds
  )

  private val testClassicSystem: ActorSystem = ActorSystem("test-classic-connroutes")
  private given ClassicActorSystemProvider = new ClassicActorSystemProvider {
    def classicSystem: ActorSystem = testClassicSystem
  }
  private val typedSystem: TypedActorSystem[Nothing] = testClassicSystem.toTyped
  private given TypedActorSystem[Nothing] = typedSystem
  private val scheduler: Scheduler = typedSystem.scheduler
  private given Timeout = Timeout(10.seconds)

  private val jwtProbe: TestProbe[JwtActor.Command] =
    TestProbe[JwtActor.Command]("jwt-actor")
  private val jwtActor = jwtProbe.ref

  private val userTokenRegistryProbe: TestProbe[UserTokenRegistry.Command] =
    TestProbe[UserTokenRegistry.Command]("user-token-registry")
  private val userTokenRegistry = userTokenRegistryProbe.ref

  private val connectionRoutes = new ConnectionRoutes(
    testConfig,
    jwtActor,
    userTokenRegistry,
    scheduler,
    Timeout(10.seconds),
    scala.concurrent.ExecutionContext.global,
    testClassicSystem
  ).connectionRoutes

  private def makeValidJwt(userId: String): String = {
    val now = java.time.Instant.now()
    val expiresAt = now.plusSeconds(900)
    val claim = JwtClaim(subject = Some(userId))
      .issuedAt(now.getEpochSecond)
      .expiresAt(expiresAt.getEpochSecond)
    JwtCirce.encode(claim, testConfig.jwt.secret, JwtAlgorithm.HS256)
  }

  /** Respond to a JWT DecodeAndValidate command from the probe.
    */
  private def respondToJwtDecode(probe: TestProbe[JwtActor.Command], userId: String, expectedToken: String): Unit = {
    probe.receiveMessage() match {
      case JwtActor.DecodeAndValidate(token, replyTo) =>
        if (token == expectedToken) {
          replyTo ! JwtActor.DecodeAndValidateSuccess(userId, java.time.Instant.now().plusSeconds(900))
        } else {
          replyTo ! JwtActor.Error("Invalid token")
        }
      case other => fail(s"Expected DecodeAndValidate, got $other")
    }
  }

  /** Respond to a GetAllPlatformConnections command from the probe.
    */
  private def respondToGetAllConnections(
      probe: TestProbe[UserTokenRegistry.Command],
      userId: String,
      platform: String
  ): AllPlatformConnectionsFound = {
    probe.receiveMessage() match {
      case UserTokenRegistry.GetAllPlatformConnections(`userId`, `platform`, replyTo) =>
        val response = AllPlatformConnectionsFound(List(
          UserTokenActor.PlatformConnection(
            platform, "channel-1", "access-1", "refresh-1",
            java.time.Instant.now().plusSeconds(3600)
          )
        ))
        replyTo ! response
        response
      case other => fail(s"Expected GetAllPlatformConnections($userId, $platform), got $other")
    }
  }

  /** Respond to a RegisterPlatformConnection command from the probe.
    */
  private def respondToRegisterConnection(
      probe: TestProbe[UserTokenRegistry.Command],
      userId: String,
      platform: String,
      channelId: String
  ): ConnectionRegistered = {
    probe.receiveMessage() match {
      case UserTokenRegistry.RegisterPlatformConnection(`userId`, `platform`, `channelId`, _, _, _, replyTo) =>
        val response = ConnectionRegistered(platform, channelId)
        replyTo ! response
        response
      case other => fail(s"Expected RegisterPlatformConnection($userId, $platform, $channelId), got $other")
    }
  }

  /** Respond to a RevokePlatformConnection command from the probe.
    */
  private def respondToRevokeConnection(
      probe: TestProbe[UserTokenRegistry.Command],
      userId: String,
      platform: String,
      channelId: String
  ): ConnectionRevoked = {
    probe.receiveMessage() match {
      case UserTokenRegistry.RevokePlatformConnection(`userId`, `platform`, `channelId`, replyTo) =>
        val response = ConnectionRevoked(platform, channelId)
        replyTo ! response
        response
      case other => fail(s"Expected RevokePlatformConnection($userId, $platform, $channelId), got $other")
    }
  }

  "GET /api/v1/connections" should {

    "return 401 when no cookie is present" in {
      Get("/api/v1/connections") ~> connectionRoutes ~> check {
        status shouldEqual StatusCodes.Unauthorized
      }
    }

    "return 401 when JWT is invalid" in {
      val invalidToken = "invalid-token"
      val test = Get("/api/v1/connections") ~>
        addHeader(Cookie("archiemate_jwt", invalidToken)) ~>
        connectionRoutes

      // Respond with JWT error
      jwtProbe.receiveMessage() match {
        case JwtActor.DecodeAndValidate(token, replyTo) =>
          replyTo ! JwtActor.Error("Invalid token")
        case other => fail(s"Expected DecodeAndValidate, got $other")
      }

      test ~> check {
        status shouldEqual StatusCodes.Unauthorized
      }
    }

    "return 200 with all connections when user has connections" in {
      val userId = "test-user-123"
      val jwt = makeValidJwt(userId)

      val test = Get("/api/v1/connections") ~>
        addHeader(Cookie("archiemate_jwt", jwt)) ~>
        connectionRoutes

      // Respond to JWT decode
      respondToJwtDecode(jwtProbe, userId, jwt)

      // Respond to registry command
      respondToGetAllConnections(userTokenRegistryProbe, userId, "*")

      test ~> check {
        status shouldEqual StatusCodes.OK
      }
    }
  }

  "GET /api/v1/connections/{platform}" should {

    "return 401 when no cookie is present" in {
      Get("/api/v1/connections/twitch") ~> connectionRoutes ~> check {
        status shouldEqual StatusCodes.Unauthorized
      }
    }

    "return 200 with platform connections" in {
      val userId = "test-user-456"
      val jwt = makeValidJwt(userId)
      val platform = "twitch"

      val test = Get(s"/api/v1/connections/$platform") ~>
        addHeader(Cookie("archiemate_jwt", jwt)) ~>
        connectionRoutes

      // Respond to JWT decode
      respondToJwtDecode(jwtProbe, userId, jwt)

      // Respond to registry command
      respondToGetAllConnections(userTokenRegistryProbe, userId, platform)

      test ~> check {
        status shouldEqual StatusCodes.OK
      }
    }
  }

  "POST /api/v1/connections/{platform}" should {

    "return 401 when no cookie is present" in {
      Post("/api/v1/connections/twitch") ~> connectionRoutes ~> check {
        status shouldEqual StatusCodes.Unauthorized
      }
    }

    "register a platform connection" in {
      val userId = "test-user-789"
      val jwt = makeValidJwt(userId)
      val platform = "twitch"
      val channelId = "channel-1"
      val body = s"""{"channelId":"$channelId","accessToken":"access-1","refreshToken":"refresh-1","expiresIn":3600}"""

      val test = Post(s"/api/v1/connections/$platform", body) ~>
        addHeader(Cookie("archiemate_jwt", jwt)) ~>
        connectionRoutes

      // Respond to JWT decode
      respondToJwtDecode(jwtProbe, userId, jwt)

      // Respond to registry command
      respondToRegisterConnection(userTokenRegistryProbe, userId, platform, channelId)

      test ~> check {
        status shouldEqual StatusCodes.Created
      }
    }

    "reject duplicate registration" in {
      val userId = "test-user-789"
      val jwt = makeValidJwt(userId)
      val platform = "twitch"
      val channelId = "channel-1"
      val body = s"""{"channelId":"$channelId","accessToken":"access-1","refreshToken":"refresh-1","expiresIn":3600}"""

      val test = Post(s"/api/v1/connections/$platform", body) ~>
        addHeader(Cookie("archiemate_jwt", jwt)) ~>
        connectionRoutes

      // Respond to JWT decode
      respondToJwtDecode(jwtProbe, userId, jwt)

      // Respond with duplicate error
      userTokenRegistryProbe.receiveMessage() match {
        case UserTokenRegistry.RegisterPlatformConnection(`userId`, `platform`, `channelId`, _, _, _, replyTo) =>
          replyTo ! Error(s"Connection for channel $channelId on $platform already registered")
        case other => fail(s"Expected RegisterPlatformConnection, got $other")
      }

      test ~> check {
        status shouldEqual StatusCodes.Conflict
      }
    }
  }

  "DELETE /api/v1/connections/{platform}" should {

    "return 401 when no cookie is present" in {
      Delete("/api/v1/connections/twitch") ~> connectionRoutes ~> check {
        status shouldEqual StatusCodes.Unauthorized
      }
    }

    "revoke a platform connection" in {
      val userId = "test-user-789"
      val jwt = makeValidJwt(userId)
      val platform = "twitch"
      val channelId = "channel-789"

      val test = Delete(s"/api/v1/connections/$platform") ~>
        addHeader(Cookie("archiemate_jwt", jwt)) ~>
        connectionRoutes

      // Respond to JWT decode
      respondToJwtDecode(jwtProbe, userId, jwt)

      // Respond to registry command
      respondToRevokeConnection(userTokenRegistryProbe, userId, platform, channelId)

      test ~> check {
        status shouldEqual StatusCodes.OK
      }
    }
  }
}
