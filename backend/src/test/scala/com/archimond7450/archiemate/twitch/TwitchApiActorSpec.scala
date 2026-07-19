package com.archimond7450.archiemate.twitch

import com.archimond7450.archiemate.ArchieMateMediator
import com.archimond7450.archiemate.actors.http.HttpRequestActor
import com.archimond7450.archiemate.http.HttpClientActor
import com.archimond7450.archiemate.settings.TwitchConfig
import com.archimond7450.archiemate.user.UserTokenRegistry
import com.archimond7450.archiemate.user.UserTokenRegistry.{*, given}
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{StatusCodes, Uri}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.*

class TwitchApiActorSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers {

  private val testConfig: Config = ConfigFactory.parseString("""
    archiemate.http-client {
      max-connections = 10
      max-idle-timeout = 60s
    }
    archiemate.twitch {
      client-id = "test-client-id"
      client-secret = "test-client-secret"
      redirect-uri-postfix = "/auth/twitch/callback"
      scopes = ["chat:read", "chat:edit"]
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

  private val twitchConfig: TwitchConfig = TwitchConfig(
    clientId = "test-client-id",
    clientSecret = "test-client-secret",
    redirectUriPostfix = "/auth/twitch/callback",
    scopes = List("chat:read", "chat:edit")
  )

  private def spawnHttpClient(): ActorRef[HttpClientActor.Command] = {
    testKit.spawn(
      org.apache.pekko.actor.typed.scaladsl.Behaviors.supervise(HttpClientActor(classicProvider, testConfig))
        .onFailure[Throwable](org.apache.pekko.actor.typed.SupervisorStrategy.resume),
      s"http-client-${java.util.UUID.randomUUID().toString.take(8)}"
    )
  }

  private def spawnHttpRequestActor(httpClient: ActorRef[HttpClientActor.Command]): ActorRef[HttpRequestActor.Command] = {
    testKit.spawn(
      org.apache.pekko.actor.typed.scaladsl.Behaviors.supervise(HttpRequestActor(httpClient))
        .onFailure[Throwable](org.apache.pekko.actor.typed.SupervisorStrategy.resume),
      s"http-request-${java.util.UUID.randomUUID().toString.take(8)}"
    )
  }

  private def spawnMediator(
      httpClient: ActorRef[HttpClientActor.Command],
      httpRequestActor: ActorRef[HttpRequestActor.Command]
  ): ActorRef[ArchieMateMediator.Command] = {
    testKit.spawn(
      ArchieMateMediator.supervised(httpClient, httpRequestActor),
      s"mediator-${java.util.UUID.randomUUID().toString.take(8)}"
    )
  }

  private def spawnUserTokenRegistry(): ActorRef[UserTokenRegistry.Command] = {
    testKit.spawn(UserTokenRegistry(), "user-token-registry")
  }

  private def spawnTwitchApiActor(
      mediator: ActorRef[ArchieMateMediator.Command],
      userTokenRegistry: ActorRef[UserTokenRegistry.Command],
      helixBaseUrl: String = TwitchApiActor.DefaultHelixBaseUrl
  ): ActorRef[TwitchApiActor.Command] = {
    testKit.spawn(TwitchApiActor(twitchConfig, mediator, userTokenRegistry, helixBaseUrl), s"twitch-api-${java.util.UUID.randomUUID().toString.take(8)}")
  }

  /** Helper to bind a test server that simulates the Twitch Helix API. */
  private def bindHelixTestServer(): (Int, Http.ServerBinding) = {
    val route: Route =
      path("oauth2" / "token") {
        post {
          complete(StatusCodes.OK, """{"access_token":"refreshed-access","refresh_token":"refreshed-refresh","expires_in":3600,"scope":"chat:read","token_type":"bearer"}""")
        }
      } ~
      path("helix" / "users") {
        get {
          val userId = uri.query().toMap.get("id")
          val login = uri.query().toMap.get("login")
          val token = uri.query().toMap.get("oauth_token")

          (token, userId, login) match {
            case (Some(tok), _, _) if tok == "valid-token" =>
              complete(StatusCodes.OK, """{"data":[{"id":"helix-user-123","login":"helixuser","display_name":"Helix User"}]}""")
            case (Some(tok), _, _) if tok == "expired-token" =>
              complete(StatusCodes.Unauthorized, """{"error":"Unauthorized","status":401,"message":"Invalid access token"}""")
            case (_, Some(id), _) =>
              complete(StatusCodes.OK, s"""{"data":[{"id":"$id","login":"user_$id","display_name":"User $id"}]}""")
            case (_, _, Some(login)) =>
              complete(StatusCodes.OK, s"""{"data":[{"id":"login-$login","login":"$login","display_name":"Login $login"}]}""")
            case _ =>
              complete(StatusCodes.BadRequest, """{"error":"Bad Request","status":400,"message":"Missing required parameters"}""")
          }
        }
      }

    val binding = Http().newServerAt("localhost", 0).bind(route).futureValue
    val port = binding.localAddress.getPort
    (port, binding)
  }

  "TwitchApiActor" must {

    "refresh a token successfully" in {
      val (port, binding) = bindHelixTestServer()
      val httpClient = spawnHttpClient()
      val httpRequestActor = spawnHttpRequestActor(httpClient)
      val mediator = spawnMediator(httpClient, httpRequestActor)
      val userTokenRegistry = spawnUserTokenRegistry()
      val actor = spawnTwitchApiActor(
        mediator,
        userTokenRegistry,
        helixBaseUrl = s"http://localhost:$port/oauth2"
      )

      val probe = testKit.createTestProbe[TwitchApiActor.TokenResponse]()
      actor ! TwitchApiActor.RefreshToken("test-refresh-token", probe.ref)

      probe.receiveMessage() match {
        case TwitchApiActor.TokenRefreshed(accessToken, refreshToken, expiresIn) =>
          accessToken shouldEqual "refreshed-access"
          refreshToken shouldEqual "refreshed-refresh"
          expiresIn shouldEqual 3600
        case TwitchApiActor.Error(msg) =>
          fail(s"Expected TokenRefreshed but got Error: $msg")
      }

      binding.unbind().futureValue
    }

    "get user by ID" in {
      val (port, binding) = bindHelixTestServer()
      val httpClient = spawnHttpClient()
      val httpRequestActor = spawnHttpRequestActor(httpClient)
      val mediator = spawnMediator(httpClient, httpRequestActor)
      val userTokenRegistry = spawnUserTokenRegistry()
      val actor = spawnTwitchApiActor(
        mediator,
        userTokenRegistry,
        helixBaseUrl = s"http://localhost:$port/helix"
      )

      val probe = testKit.createTestProbe[TwitchApiActor.TokenResponse]()
      actor ! TwitchApiActor.GetUserById("user-123", "valid-token", probe.ref)

      probe.receiveMessage() match {
        case TwitchApiActor.UserFound(id, login, displayName) =>
          id shouldEqual "user-123"
          login shouldEqual "user_user-123"
          displayName shouldEqual "User user-123"
        case TwitchApiActor.Error(msg) =>
          fail(s"Expected UserFound but got Error: $msg")
      }

      binding.unbind().futureValue
    }

    "get current user by access token" in {
      val (port, binding) = bindHelixTestServer()
      val httpClient = spawnHttpClient()
      val httpRequestActor = spawnHttpRequestActor(httpClient)
      val mediator = spawnMediator(httpClient, httpRequestActor)
      val userTokenRegistry = spawnUserTokenRegistry()
      val actor = spawnTwitchApiActor(
        mediator,
        userTokenRegistry,
        helixBaseUrl = s"http://localhost:$port/helix"
      )

      val probe = testKit.createTestProbe[TwitchApiActor.TokenResponse]()
      actor ! TwitchApiActor.GetCurrentUser("valid-token", probe.ref)

      probe.receiveMessage() match {
        case TwitchApiActor.UserFound(id, login, displayName) =>
          id shouldEqual "helix-user-123"
          login shouldEqual "helixuser"
          displayName shouldEqual "Helix User"
        case TwitchApiActor.Error(msg) =>
          fail(s"Expected UserFound but got Error: $msg")
      }

      binding.unbind().futureValue
    }

    "get user by login name" in {
      val (port, binding) = bindHelixTestServer()
      val httpClient = spawnHttpClient()
      val httpRequestActor = spawnHttpRequestActor(httpClient)
      val mediator = spawnMediator(httpClient, httpRequestActor)
      val userTokenRegistry = spawnUserTokenRegistry()
      val actor = spawnTwitchApiActor(
        mediator,
        userTokenRegistry,
        helixBaseUrl = s"http://localhost:$port/helix"
      )

      val probe = testKit.createTestProbe[TwitchApiActor.TokenResponse]()
      actor ! TwitchApiActor.GetUserByLogin("testlogin", "valid-token", probe.ref)

      probe.receiveMessage() match {
        case TwitchApiActor.UserFound(id, login, displayName) =>
          id shouldEqual "login-testlogin"
          login shouldEqual "testlogin"
          displayName shouldEqual "Login testlogin"
        case TwitchApiActor.Error(msg) =>
          fail(s"Expected UserFound but got Error: $msg")
      }

      binding.unbind().futureValue
    }

    "return error for expired token" in {
      val (port, binding) = bindHelixTestServer()
      val httpClient = spawnHttpClient()
      val httpRequestActor = spawnHttpRequestActor(httpClient)
      val mediator = spawnMediator(httpClient, httpRequestActor)
      val userTokenRegistry = spawnUserTokenRegistry()
      val actor = spawnTwitchApiActor(
        mediator,
        userTokenRegistry,
        helixBaseUrl = s"http://localhost:$port/helix"
      )

      val probe = testKit.createTestProbe[TwitchApiActor.TokenResponse]()
      actor ! TwitchApiActor.GetCurrentUser("expired-token", probe.ref)

      probe.receiveMessage() match {
        case TwitchApiActor.Error(msg) =>
          msg.toLowerCase should include("401")
        case other =>
          fail(s"Expected Error with 401 but got: $other")
      }

      binding.unbind().futureValue
    }

    "handle connection errors gracefully" in {
      val httpClient = spawnHttpClient()
      val httpRequestActor = spawnHttpRequestActor(httpClient)
      val mediator = spawnMediator(httpClient, httpRequestActor)
      val userTokenRegistry = spawnUserTokenRegistry()
      // Use a non-existent port to trigger connection error
      val actor = spawnTwitchApiActor(
        mediator,
        userTokenRegistry,
        helixBaseUrl = "http://localhost:9999/helix"
      )

      val probe = testKit.createTestProbe[TwitchApiActor.TokenResponse]()
      actor ! TwitchApiActor.GetCurrentUser("any-token", probe.ref)

      probe.receiveMessage() match {
        case TwitchApiActor.Error(msg) =>
          msg should include("failed")
        case other =>
          fail(s"Expected Error but got: $other")
      }
    }
  }
}
