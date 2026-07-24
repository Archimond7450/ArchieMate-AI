package com.archimond7450.archiemate.auth

import com.archimond7450.archiemate.actors.http.HttpRequestActor
import com.archimond7450.archiemate.ArchieMateMediator
import com.archimond7450.archiemate.http.HttpClientActor
import com.archimond7450.archiemate.settings.TwitchConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.MediaTypes.`application/json`
import org.apache.pekko.http.scaladsl.model.{HttpEntity, StatusCodes, Uri}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.*

class TwitchOAuthActorSpec
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
    callbackPath = "/auth/twitch/callback",
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

  private def spawnTwitchOAuthActor(
      mediator: ActorRef[ArchieMateMediator.Command],
      authBaseUrl: String = TwitchOAuthActor.DefaultAuthBaseUrl,
      helixBaseUrl: String = TwitchOAuthActor.DefaultHelixBaseUrl
  ): ActorRef[TwitchOAuthActor.Command] = {
    testKit.spawn(TwitchOAuthActor(twitchConfig, mediator, authBaseUrl, helixBaseUrl), s"twitch-oauth-${java.util.UUID.randomUUID().toString.take(8)}")
  }

  /** Helper to bind a test server that simulates the Twitch API. */
  private def bindTwitchTestServer(): (Int, Http.ServerBinding) = {
    val route: Route =
      path("oauth2" / "token") {
        post {
          entity(as[String]) { body =>
            val params = body.split("&").map { pair =>
              val Array(k, v) = pair.split("=", 2)
              k -> v
            }.toMap
            val tokenResp = params.get("grant_type") match {
              case Some("authorization_code") =>
                """{"access_token":"test-access-token","refresh_token":"test-refresh-token","expires_in":3600,"scope":"chat:read chat:edit","token_type":"bearer"}"""
              case _ =>
                """{"error":"invalid_grant"}"""
            }
            complete(StatusCodes.OK, tokenResp)
          }
        }
      } ~
      path("helix" / "users") {
        get {
          complete(StatusCodes.OK, """{"data":[{"id":"test-user-id-123","login":"testuser","display_name":"Test User"}]}""")
        }
      }

    val binding = Http().newServerAt("localhost", 0).bind(route).futureValue
    val port = binding.localAddress.getPort
    (port, binding)
  }

  "TwitchOAuthActor" must {

    "generate a valid OAuth state and authorization URL" in {
      val httpClient = spawnHttpClient()
      val httpRequestActor = spawnHttpRequestActor(httpClient)
      val mediator = spawnMediator(httpClient, httpRequestActor)
      val actor = spawnTwitchOAuthActor(mediator)

      val probe = testKit.createTestProbe[TwitchOAuthActor.StateGenerated]()
      val redirectUri = "http://localhost:8080/auth/twitch/callback"

      actor ! TwitchOAuthActor.GenerateState(redirectUri, probe.ref, scopes = List("chat:read", "chat:edit"))

      probe.receiveMessage() match {
        case TwitchOAuthActor.StateOk(state, authUrl) =>
          state should not be empty
          authUrl.toString should startWith("https://id.twitch.tv/oauth2/authorize")
          authUrl.query().toMap.get("client_id") should contain("test-client-id")
          authUrl.query().toMap.get("redirect_uri") should contain(redirectUri)
          authUrl.query().toMap.get("response_type") should contain("code")
          authUrl.query().toMap.get("scope") should contain("chat:read,chat:edit")
          authUrl.query().toMap.get("state") should contain(state)
        case TwitchOAuthActor.StateError(msg) =>
          fail(s"Expected StateOk but got StateError: $msg")
      }
    }

    "generate an authorization URL with configured scopes and force_verify" in {
      val httpClient = spawnHttpClient()
      val httpRequestActor = spawnHttpRequestActor(httpClient)
      val mediator = spawnMediator(httpClient, httpRequestActor)
      val actor = spawnTwitchOAuthActor(mediator)

      val probe = testKit.createTestProbe[TwitchOAuthActor.AuthorizeStateGenerated]()
      val redirectUri = "http://localhost:8080/auth/twitch/callback"

      actor ! TwitchOAuthActor.GenerateAuthorizeState(redirectUri, probe.ref)

      probe.receiveMessage() match {
        case TwitchOAuthActor.AuthorizeStateOk(state, authUrl) =>
          state should not be empty
          authUrl.toString should startWith("https://id.twitch.tv/oauth2/authorize")
          authUrl.query().toMap.get("client_id") should contain("test-client-id")
          authUrl.query().toMap.get("redirect_uri") should contain(redirectUri)
          authUrl.query().toMap.get("response_type") should contain("code")
          authUrl.query().toMap.get("scope") should contain("chat:read,chat:edit")
          authUrl.query().toMap.get("force_verify") should contain("true")
          authUrl.query().toMap.get("state") should contain(state)
        case other =>
          fail(s"Expected AuthorizeStateOk but got: $other")
      }
    }

    "exchange a valid code for tokens" in {
      val (port, binding) = bindTwitchTestServer()
      val httpClient = spawnHttpClient()
      val httpRequestActor = spawnHttpRequestActor(httpClient)
      val mediator = spawnMediator(httpClient, httpRequestActor)
      val actor = spawnTwitchOAuthActor(
        mediator,
        authBaseUrl = s"http://localhost:$port/oauth2",
        helixBaseUrl = s"http://localhost:$port/helix"
      )

      // First generate a state
      val stateProbe = testKit.createTestProbe[TwitchOAuthActor.StateGenerated]()
      val redirectUri = s"http://localhost:$port/auth/twitch/callback"

      actor ! TwitchOAuthActor.GenerateState(redirectUri, stateProbe.ref)
      val stateOk = stateProbe.receiveMessage().asInstanceOf[TwitchOAuthActor.StateOk]
      val state = stateOk.state

      // Exchange the code (use the state as the code for testing)
      val exchangeProbe = testKit.createTestProbe[TwitchOAuthActor.TokenExchangeResponse]()
      actor ! TwitchOAuthActor.ExchangeCode(state, exchangeProbe.ref)

      exchangeProbe.receiveMessage() match {
        case TwitchOAuthActor.TokenExchangeSuccess(accessToken, refreshToken, expiresIn, platformUserId, flow) =>
          accessToken should not be empty
          refreshToken should not be empty
          expiresIn shouldBe 3600
          platformUserId shouldBe "test-user-id-123"
          flow shouldBe "login"
        case TwitchOAuthActor.TokenExchangeError(msg) =>
          fail(s"Expected TokenExchangeSuccess but got TokenExchangeError: $msg")
      }

      binding.unbind().futureValue
    }

    "reject exchange with invalid state" in {
      val httpClient = spawnHttpClient()
      val httpRequestActor = spawnHttpRequestActor(httpClient)
      val mediator = spawnMediator(httpClient, httpRequestActor)
      val actor = spawnTwitchOAuthActor(mediator)

      val probe = testKit.createTestProbe[TwitchOAuthActor.TokenExchangeResponse]()

      actor ! TwitchOAuthActor.ExchangeCode("invalid-state", probe.ref)

      probe.receiveMessage() match {
        case TwitchOAuthActor.TokenExchangeError(msg) =>
          msg should include("Invalid or expired OAuth state")
        case other =>
          fail(s"Expected TokenExchangeError but got: $other")
      }
    }
  }
}
