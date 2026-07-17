package com.archimond7450.archiemate

import com.archimond7450.archiemate.http.HttpClientActor
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.HttpMethods
import org.apache.pekko.http.scaladsl.model.MediaTypes.`application/json`
import org.apache.pekko.http.scaladsl.model.{HttpEntity, StatusCode, StatusCodes, Uri}
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.pattern.StatusReply
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Promise
import scala.concurrent.duration.*

class ArchieMateMediatorIntegrationSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers {

  private val testConfig: Config = ConfigFactory.parseString("""
    archiemate.http-client {
      max-connections = 10
      max-idle-timeout = 60s
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

  private def spawnMediator(
      httpClient: ActorRef[HttpClientActor.Command],
      name: String = "archie-mate-mediator"
  ): ActorRef[ArchieMateMediator.Command] =
    testKit.spawn(
      ArchieMateMediator(httpClient),
      s"$name-${java.util.UUID.randomUUID().toString.take(8)}"
    )

  private def spawnHttpClient(): ActorRef[HttpClientActor.Command] = {
    testKit.spawn(
      HttpClientActor(classicProvider, testConfig),
      s"http-client-${java.util.UUID.randomUUID().toString.take(8)}"
    )
  }

  private def bindTestServer(): Http.ServerBinding = {
    val route: Route =
      path("echo") {
        get {
          extractRequest { req =>
            complete(s"method=${req.method.name()},path=${req.uri.path}")
          }
        } ~
        post {
          entity(as[String]) { body =>
            extractRequest { req =>
              complete(s"method=${req.method.name()},path=${req.uri.path},body=$body")
            }
          }
        }
      } ~
      path("status") {
        get {
          complete(StatusCodes.TooManyRequests, "rate limited")
        }
      }

    Http().newServerAt("localhost", 0).bind(route).futureValue
  }

  private def unbind(serverBinding: Http.ServerBinding): Unit = {
    val p = Promise[Unit]()
    serverBinding.unbind().onComplete { _ => p.success(()) }(scala.concurrent.ExecutionContext.global)
    scala.concurrent.Await.result(p.future, 10.seconds)
  }

  private def expectSuccess(probe: TestProbe[StatusReply[HttpClientActor.Response]]): HttpClientActor.Response = {
    probe.receiveMessage() match {
      case StatusReply.Success(value) => value.asInstanceOf[HttpClientActor.Response]
      case StatusReply.Error(message) => fail(s"Expected success, got error: $message")
    }
  }

  private def expectError(probe: TestProbe[StatusReply[HttpClientActor.Response]]): String = {
    probe.receiveMessage(10.seconds) match {
      case StatusReply.Success(_) => fail("Expected error")
      case StatusReply.Error(ex) => ex.getMessage
    }
  }

  "ArchieMateMediator" should {

    "route a GET request through to HttpClientActor and receive a response" in {
      val httpActor = spawnHttpClient()
      val mediator = spawnMediator(httpActor)
      val serverBinding = bindTestServer()
      val port = serverBinding.localAddress.getPort

      val responseProbe = testKit.createTestProbe[StatusReply[HttpClientActor.Response]]("response")
      mediator ! ArchieMateMediator.SendHttpClientRequest(
        HttpClientActor.SendRequest(
          method = HttpMethods.GET,
          uri = Uri(s"http://localhost:$port/echo"),
          replyTo = responseProbe.ref
        )
      )

      val resp = expectSuccess(responseProbe)
      resp.response.status shouldEqual StatusCodes.OK
      resp.entityString shouldEqual "method=GET,path=/echo"

      unbind(serverBinding)
    }

    "route a POST request with entity through to HttpClientActor" in {
      val httpActor = spawnHttpClient()
      val mediator = spawnMediator(httpActor)
      val serverBinding = bindTestServer()
      val port = serverBinding.localAddress.getPort

      val responseProbe = testKit.createTestProbe[StatusReply[HttpClientActor.Response]]("response")
      mediator ! ArchieMateMediator.SendHttpClientRequest(
        HttpClientActor.SendRequest(
          method = HttpMethods.POST,
          uri = Uri(s"http://localhost:$port/echo"),
          headers = Seq(
            RawHeader("Content-Type", "application/json")
          ),
          entity = HttpEntity.Default(`application/json`, 15, org.apache.pekko.stream.scaladsl.Source.single(
            org.apache.pekko.util.ByteString("""{"key":"value"}""")
          )),
          replyTo = responseProbe.ref
        )
      )

      val resp = expectSuccess(responseProbe)
      resp.response.status shouldEqual StatusCodes.OK
      resp.entityString should include ("method=POST")
      resp.entityString should include ("body={\"key\":\"value\"}")

      unbind(serverBinding)
    }

    "route multiple requests through to the same HttpClientActor" in {
      val httpActor = spawnHttpClient()
      val mediator = spawnMediator(httpActor)
      val serverBinding = bindTestServer()
      val port = serverBinding.localAddress.getPort

      val responseProbe1 = testKit.createTestProbe[StatusReply[HttpClientActor.Response]]("response-1")
      val responseProbe2 = testKit.createTestProbe[StatusReply[HttpClientActor.Response]]("response-2")

      mediator ! ArchieMateMediator.SendHttpClientRequest(
        HttpClientActor.SendRequest(
          method = HttpMethods.GET,
          uri = Uri(s"http://localhost:$port/echo"),
          replyTo = responseProbe1.ref
        )
      )
      mediator ! ArchieMateMediator.SendHttpClientRequest(
        HttpClientActor.SendRequest(
          method = HttpMethods.GET,
          uri = Uri(s"http://localhost:$port/echo"),
          replyTo = responseProbe2.ref
        )
      )

      expectSuccess(responseProbe1).response.status shouldEqual StatusCodes.OK
      expectSuccess(responseProbe2).response.status shouldEqual StatusCodes.OK

      unbind(serverBinding)
    }

    "propagate connection errors through the mediator" in {
      val httpActor = spawnHttpClient()
      val mediator = spawnMediator(httpActor)

      val responseProbe = testKit.createTestProbe[StatusReply[HttpClientActor.Response]]("response")
      mediator ! ArchieMateMediator.SendHttpClientRequest(
        HttpClientActor.SendRequest(
          method = HttpMethods.GET,
          uri = Uri("http://localhost:1"),
          replyTo = responseProbe.ref
        )
      )

      val message = expectError(responseProbe)
      message should include ("Connection refused")
    }

    "return correct HTTP status codes through the mediator" in {
      val httpActor = spawnHttpClient()
      val mediator = spawnMediator(httpActor)
      val serverBinding = bindTestServer()
      val port = serverBinding.localAddress.getPort

      val responseProbe = testKit.createTestProbe[StatusReply[HttpClientActor.Response]]("response")
      mediator ! ArchieMateMediator.SendHttpClientRequest(
        HttpClientActor.SendRequest(
          method = HttpMethods.GET,
          uri = Uri(s"http://localhost:$port/status"),
          replyTo = responseProbe.ref
        )
      )

      val resp = expectSuccess(responseProbe)
      resp.response.status shouldEqual StatusCodes.TooManyRequests
      resp.entityString shouldEqual "rate limited"

      unbind(serverBinding)
    }

  }

}
