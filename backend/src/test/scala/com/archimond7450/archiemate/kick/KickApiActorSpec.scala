package com.archimond7450.archiemate.kick

import com.archimond7450.archiemate.actors.http.HttpRequestActor
import com.archimond7450.archiemate.http.HttpClientActor
import com.archimond7450.archiemate.kick.KickApiActor.{*, given}
import com.archimond7450.archiemate.settings.KickConfig
import com.archimond7450.archiemate.user.UserTokenRegistry
import com.archimond7450.archiemate.user.UserTokenRegistry.{*, given}
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{StatusCodes, Uri}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.*

class KickApiActorSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers {

  private val testConfig: Config = ConfigFactory.parseString("""
    archiemate.http-client {
      max-connections = 10
      max-idle-timeout = 60s
    }
    archiemate.kick {
      client-id = "test-kick-client-id"
      client-secret = "test-kick-client-secret"
      redirect-uri-postfix = "/auth/kick/callback"
    }
    pekko.http.host-connection-pool.client.connect-timeout = 1s
    pekko.test.single-expect-default = 10s
  """).withFallback(ConfigFactory.load())

  private val classicProvider: ClassicActorSystemProvider = new ClassicActorSystemProvider {
    def classicSystem = org.apache.pekko.actor.ActorSystem(
      "test-classic",
      testConfig
    )
  }

  private val kickConfig: KickConfig = KickConfig(
    clientId = "test-kick-client-id",
    clientSecret = "test-kick-client-secret",
    callbackPath = "/auth/kick/callback",
    scopes = List("channels.read", "offline_access")
  )

  private def spawnHttpClient(): ActorRef[HttpClientActor.Command] = {
    testKit.spawn(
      Behaviors.supervise(HttpClientActor(classicProvider, testConfig))
        .onFailure[Throwable](org.apache.pekko.actor.typed.SupervisorStrategy.resume),
      s"http-client-${java.util.UUID.randomUUID().toString.take(8)}"
    )
  }

  private def spawnHttpRequestActor(httpClient: ActorRef[HttpClientActor.Command]): ActorRef[HttpRequestActor.Command] = {
    testKit.spawn(
      Behaviors.supervise(HttpRequestActor(httpClient))
        .onFailure[Throwable](org.apache.pekko.actor.typed.SupervisorStrategy.resume),
      s"http-request-${java.util.UUID.randomUUID().toString.take(8)}"
    )
  }

  private def spawnUserTokenRegistry(): ActorRef[UserTokenRegistry.Command] = {
    testKit.spawn(UserTokenRegistry(), "user-token-registry")
  }

  private def spawnKickApiActor(
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      userTokenRegistry: ActorRef[UserTokenRegistry.Command],
      baseUrl: String = KickApiActor.DefaultBaseUrl,
      authBaseUrl: String = KickApiActor.DefaultAuthBaseUrl
  ): ActorRef[KickApiActor.Command] = {
    testKit.spawn(
      Behaviors.supervise(KickApiActor(kickConfig, httpRequestActor, userTokenRegistry, baseUrl, authBaseUrl))
        .onFailure[Throwable](org.apache.pekko.actor.typed.SupervisorStrategy.resume),
      s"kick-api-${java.util.UUID.randomUUID().toString.take(8)}"
    )
  }

  /** Helper to bind a test server that simulates the Kick API. */
  private def bindKickTestServer(): (Int, Http.ServerBinding) = {
    val route: Route =
      path("oauth2" / "token") {
        post {
          complete(StatusCodes.OK, """{"access_token":"refreshed-kick-access","refresh_token":"refreshed-kick-refresh","expires_in":3600}""")
        }
      } ~
      path("user" / "me") {
        get {
          extract(_.request.uri) { uri =>
            val token = uri.query().toMap.get("access_token")
            token match {
              case Some(tok) if tok == "valid-kick-token" =>
                complete(StatusCodes.OK, """{"id":"kick-user-123","name":"kickuser","bio":"Test bio","profile_image":"https://example.com/avatar.png"}""")
              case Some(tok) if tok == "expired-kick-token" =>
                complete(StatusCodes.Unauthorized, """{"error":"invalid_token","error_description":"Access token has expired"}""")
              case _ =>
                complete(StatusCodes.BadRequest, """{"error":"invalid_request","error_description":"Missing access_token"}""")
            }
          }
        }
      }

    val binding = Http().newServerAt("localhost", 0).bind(route).futureValue
    val port = binding.localAddress.getPort
    (port, binding)
  }

  "KickApiActor" must {

    "refresh a token successfully" in {
      val (port, binding) = bindKickTestServer()
      val httpClient = spawnHttpClient()
      val httpRequestActor = spawnHttpRequestActor(httpClient)
      val userTokenRegistry = spawnUserRegistry()
      val actor = spawnKickApiActor(httpRequestActor, userTokenRegistry, s"http://localhost:$port", s"http://localhost:$port")

      val probe = testKit.createTestProbe[KickApiActor.TokenResponse]()
      actor ! KickApiActor.RefreshToken("test-kick-refresh", probe.ref)

      probe.receiveMessage() match {
        case KickApiActor.TokenRefreshed(accessToken, refreshToken, expiresIn) =>
          accessToken shouldEqual "refreshed-kick-access"
          refreshToken shouldEqual "refreshed-kick-refresh"
          expiresIn shouldEqual 3600
        case other =>
          fail(s"Expected TokenRefreshed but got: $other")
      }

      binding.unbind().futureValue
    }

    "get current user by access token" in {
      val (port, binding) = bindKickTestServer()
      val httpClient = spawnHttpClient()
      val httpRequestActor = spawnHttpRequestActor(httpClient)
      val userTokenRegistry = spawnUserRegistry()
      val actor = spawnKickApiActor(httpRequestActor, userTokenRegistry, s"http://localhost:$port")

      val probe = testKit.createTestProbe[KickApiActor.TokenResponse]()
      actor ! KickApiActor.GetCurrentUser("valid-kick-token", probe.ref)

      probe.receiveMessage() match {
        case KickApiActor.UserFound(id, name, bio, profileImage) =>
          id shouldEqual "kick-user-123"
          name shouldEqual "kickuser"
          bio shouldEqual Some("Test bio")
          profileImage shouldEqual Some("https://example.com/avatar.png")
        case other =>
          fail(s"Expected UserFound but got: $other")
      }

      binding.unbind().futureValue
    }

    "return error for expired token" in {
      val (port, binding) = bindKickTestServer()
      val httpClient = spawnHttpClient()
      val httpRequestActor = spawnHttpRequestActor(httpClient)
      val userTokenRegistry = spawnUserRegistry()
      val actor = spawnKickApiActor(httpRequestActor, userTokenRegistry, s"http://localhost:$port")

      val probe = testKit.createTestProbe[KickApiActor.TokenResponse]()
      actor ! KickApiActor.GetCurrentUser("expired-kick-token", probe.ref)

      probe.receiveMessage() match {
        case KickApiActor.Error(msg) =>
          msg.toLowerCase should include("401")
        case other =>
          fail(s"Expected Error with 401 but got: $other")
      }

      binding.unbind().futureValue
    }

    "handle connection errors gracefully" in {
      val httpClient = spawnHttpClient()
      val httpRequestActor = spawnHttpRequestActor(httpClient)
      val userTokenRegistry = spawnUserRegistry()
      val actor = spawnKickApiActor(httpRequestActor, userTokenRegistry, "http://localhost:9999")

      val probe = testKit.createTestProbe[KickApiActor.TokenResponse]()
      actor ! KickApiActor.GetCurrentUser("any-token", probe.ref)

      probe.receiveMessage(5.seconds) match {
        case KickApiActor.Error(msg) =>
          val lower = msg.toLowerCase
          assert(lower.contains("failed") || lower.contains("connection") || lower.contains("refused"))
        case other =>
          fail(s"Expected Error but got: $other")
      }
    }
  }

  private def spawnUserRegistry(): ActorRef[UserTokenRegistry.Command] = {
    testKit.spawn(UserTokenRegistry(), s"user-token-registry-${java.util.UUID.randomUUID().toString.take(8)}")
  }
}
