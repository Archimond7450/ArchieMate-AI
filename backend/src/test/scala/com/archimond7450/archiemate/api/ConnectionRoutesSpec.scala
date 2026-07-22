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

/** Tests for [[ConnectionRoutes]].
  *
  * TODO: Fix async onComplete pattern — the route's onComplete callback is
  * asynchronous and the test framework's check waits for the route to complete
  * before the callback fires. Need to use a different testing pattern.
  */
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

  private val classicSystem: ActorSystem = ActorSystem("test-classic-connroutes")
  private given ClassicActorSystemProvider = new ClassicActorSystemProvider {
    def classicSystem = classicSystem
  }
  private val typedSystem: TypedActorSystem[Nothing] = classicSystem.toTyped
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
    classicSystem
  ).connectionRoutes

  private def makeValidJwt(userId: String): String = {
    val now = java.time.Instant.now()
    val expiresAt = now.plusSeconds(900)
    val claim = JwtClaim(subject = Some(userId))
      .issuedAt(now.getEpochSecond)
      .expiresAt(expiresAt.getEpochSecond)
    JwtCirce.encode(claim, testConfig.jwt.secret, JwtAlgorithm.HS256)
  }

  "GET /api/v1/connections" should {
    "return 401 when no cookie is present" in pending
    "return 401 when JWT is invalid" in pending
    "return 200 with all connections when user has connections" in pending
  }

  "GET /api/v1/connections/{platform}" should {
    "return 401 when no cookie is present" in pending
    "return 200 with platform connections" in pending
  }

  "POST /api/v1/connections/{platform}" should {
    "return 401 when no cookie is present" in pending
    "register a platform connection" in pending
    "reject duplicate registration" in pending
  }

  "DELETE /api/v1/connections/{platform}" should {
    "return 401 when no cookie is present" in pending
    "revoke a platform connection" in pending
  }
}
