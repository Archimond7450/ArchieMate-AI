package com.archimond7450.archiemate.youtube

import com.archimond7450.archiemate.actors.http.HttpRequestActor
import com.archimond7450.archiemate.http.HttpClientActor
import com.archimond7450.archiemate.settings.YoutubeConfig
import com.archimond7450.archiemate.user.UserTokenRegistry
import com.archimond7450.archiemate.user.UserTokenRegistry.{*, given}
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
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

class YoutubeApiActorSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers {

  private val testConfig: Config = ConfigFactory.parseString("""
    archiemate.http-client {
      max-connections = 10
      max-idle-timeout = 60s
    }
    archiemate.youtube {
      client-id = "test-youtube-client-id"
      client-secret = "test-youtube-client-secret"
      redirect-uri-postfix = "/auth/youtube/callback"
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

  private val youtubeConfig: YoutubeConfig = YoutubeConfig(
    clientId = "test-youtube-client-id",
    clientSecret = "test-youtube-client-secret",
    callbackPath = "/auth/youtube/callback",
    scopes = List.empty
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

  private def spawnUserRegistry(): ActorRef[UserTokenRegistry.Command] = {
    testKit.spawn(UserTokenRegistry(), s"user-token-registry-${java.util.UUID.randomUUID().toString.take(8)}")
  }

  private def spawnYoutubeApiActor(
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      userTokenRegistry: ActorRef[UserTokenRegistry.Command],
      baseUrl: String = YoutubeApiActor.DefaultBaseUrl,
      authBaseUrl: String = YoutubeApiActor.DefaultAuthBaseUrl
  ): ActorRef[YoutubeApiActor.Command] = {
    testKit.spawn(
      Behaviors.supervise(YoutubeApiActor(youtubeConfig, httpRequestActor, userTokenRegistry, baseUrl, authBaseUrl))
        .onFailure[Throwable](org.apache.pekko.actor.typed.SupervisorStrategy.resume),
      s"youtube-api-${java.util.UUID.randomUUID().toString.take(8)}"
    )
  }

  /** Helper to bind a test server that simulates the YouTube API. */
  private def bindYoutubeTestServer(): (Int, Http.ServerBinding) = {
    val route: Route =
      path("oauth2" / "token") {
        post {
          complete(StatusCodes.OK, """{"access_token":"refreshed-youtube-access","refresh_token":"refreshed-youtube-refresh","expires_in":3600,"scope":"https://www.googleapis.com/auth/userinfo.profile"}""")
        }
      } ~
      path("oauth2" / "v3" / "userinfo") {
        get {
          extract(_.request.uri) { uri =>
            val token = uri.query().toMap.get("access_token")
            token match {
              case Some(tok) if tok == "valid-youtube-token" =>
                complete(StatusCodes.OK, """{"id":"youtube-user-123","email":"youtubeuser@example.com","verified_email":true,"name":"YouTube User","picture":"https://example.com/youtube-avatar.png"}""")
              case Some(tok) if tok == "expired-youtube-token" =>
                complete(StatusCodes.Unauthorized, """{"error":"unauthorized","error_description":"Invalid Authentication"}""")
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

  "YoutubeApiActor" must {

    "refresh a token successfully" in {
      val (port, binding) = bindYoutubeTestServer()
      val httpClient = spawnHttpClient()
      val httpRequestActor = spawnHttpRequestActor(httpClient)
      val userTokenRegistry = spawnUserRegistry()
      val actor = spawnYoutubeApiActor(httpRequestActor, userTokenRegistry, s"http://localhost:$port", s"http://localhost:$port")

      val probe = testKit.createTestProbe[YoutubeApiActor.TokenResponse]()
      actor ! YoutubeApiActor.RefreshToken("test-youtube-refresh", probe.ref)

      probe.receiveMessage() match {
        case YoutubeApiActor.TokenRefreshed(accessToken, refreshToken, expiresIn) =>
          accessToken shouldEqual "refreshed-youtube-access"
          refreshToken shouldEqual "refreshed-youtube-refresh"
          expiresIn shouldEqual 3600
        case other =>
          fail(s"Expected TokenRefreshed but got: $other")
      }

      binding.unbind().futureValue
    }

    "get current user by access token" in {
      val (port, binding) = bindYoutubeTestServer()
      val httpClient = spawnHttpClient()
      val httpRequestActor = spawnHttpRequestActor(httpClient)
      val userTokenRegistry = spawnUserRegistry()
      val actor = spawnYoutubeApiActor(httpRequestActor, userTokenRegistry, s"http://localhost:$port")

      val probe = testKit.createTestProbe[YoutubeApiActor.TokenResponse]()
      actor ! YoutubeApiActor.GetCurrentUser("valid-youtube-token", probe.ref)

      probe.receiveMessage() match {
        case YoutubeApiActor.UserFound(id, email, verifiedEmail, name, picture) =>
          id shouldEqual "youtube-user-123"
          email shouldEqual "youtubeuser@example.com"
          verifiedEmail shouldEqual true
          name shouldEqual "YouTube User"
          picture shouldEqual Some("https://example.com/youtube-avatar.png")
        case other =>
          fail(s"Expected UserFound but got: $other")
      }

      binding.unbind().futureValue
    }

    "return error for expired token" in {
      val (port, binding) = bindYoutubeTestServer()
      val httpClient = spawnHttpClient()
      val httpRequestActor = spawnHttpRequestActor(httpClient)
      val userTokenRegistry = spawnUserRegistry()
      val actor = spawnYoutubeApiActor(httpRequestActor, userTokenRegistry, s"http://localhost:$port")

      val probe = testKit.createTestProbe[YoutubeApiActor.TokenResponse]()
      actor ! YoutubeApiActor.GetCurrentUser("expired-youtube-token", probe.ref)

      probe.receiveMessage() match {
        case YoutubeApiActor.Error(msg) =>
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
      val actor = spawnYoutubeApiActor(httpRequestActor, userTokenRegistry, "http://localhost:9999", "http://localhost:9999")

      val probe = testKit.createTestProbe[YoutubeApiActor.TokenResponse]()
      actor ! YoutubeApiActor.GetCurrentUser("any-token", probe.ref)

      probe.receiveMessage(5.seconds) match {
        case YoutubeApiActor.Error(msg) =>
          val lower = msg.toLowerCase
          assert(lower.contains("failed") || lower.contains("connection") || lower.contains("refused"))
        case other =>
          fail(s"Expected Error but got: $other")
      }
    }
  }
}
