package com.archimond7450.archiemate.http

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

class HttpClientActorSpec
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

  private def spawnActor(): ActorRef[HttpClientActor.Command] = {
    testKit.spawn(HttpClientActor(classicProvider, testConfig), s"http-client-${java.util.UUID.randomUUID().toString.take(8)}")
  }

  /** Helper to bind a simple test server that echoes request details. */
  private def bindTestServer(): Http.ServerBinding = {
    val route: Route =
      path("echo") {
        get {
          extractRequest { req =>
            complete {
              s"method=${req.method.name()},path=${req.uri.path}"
            }
          }
        } ~
        post {
          entity(as[String]) { body =>
            extractRequest { req =>
              complete {
                s"method=${req.method.name()},path=${req.uri.path},body=$body"
              }
            }
          }
        }
      } ~
      path("status") {
        get {
          complete(StatusCodes.TooManyRequests, "rate limited")
        }
      } ~
      path("headers") {
        get {
          extractRequest { req =>
            val xApiKey = req.headers.find(h => h.name.toLowerCase == "x-api-key").map(_.value).getOrElse("none")
            val customHeader = req.headers.find(h => h.name.toLowerCase == "x-custom").map(_.value).getOrElse("none")
            complete {
              s"X-API-Key=$xApiKey,X-Custom=$customHeader"
            }
          }
        }
      }

    Http().newServerAt("localhost", 0).bind(route).futureValue
  }

  private def makeUri(port: Int, path: String): Uri =
    Uri("http://localhost:" + port + path)

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

  private def unbind(serverBinding: Http.ServerBinding): Unit = {
    val p = Promise[Unit]()
    serverBinding.unbind().onComplete { _ => p.success(()) }(scala.concurrent.ExecutionContext.global)
    scala.concurrent.Await.result(p.future, 10.seconds)
  }

  "HttpClientActor" should {

    "return success response for a GET request" in {
      val actor = spawnActor()
      val serverBinding = bindTestServer()
      val port = serverBinding.localAddress.getPort

      val probe = testKit.createTestProbe[StatusReply[HttpClientActor.Response]]("response-probe")
      actor ! HttpClientActor.SendRequest(
        method = HttpMethods.GET,
        uri = makeUri(port, "/echo"),
        replyTo = probe.ref
      )

      val resp = expectSuccess(probe)
      resp.response.status shouldEqual StatusCodes.OK
      resp.entityString shouldEqual "method=GET,path=/echo"

      // Cleanup
      unbind(serverBinding)
    }

    "return success response for a POST request with entity and custom headers" in {
      val actor = spawnActor()
      val serverBinding = bindTestServer()
      val port = serverBinding.localAddress.getPort

      val probe = testKit.createTestProbe[StatusReply[HttpClientActor.Response]]("response-probe")
      actor ! HttpClientActor.SendRequest(
        method = HttpMethods.POST,
        uri = makeUri(port, "/echo"),
        headers = Seq(
          RawHeader("Content-Type", "application/json")
        ),
        entity = HttpEntity.Default(`application/json`, 15, org.apache.pekko.stream.scaladsl.Source.single(org.apache.pekko.util.ByteString("""{"key":"value"}"""))),
        replyTo = probe.ref
      )

      val resp = expectSuccess(probe)
      resp.response.status shouldEqual StatusCodes.OK
      resp.entityString should include ("method=POST")
      resp.entityString should include ("path=/echo")
      resp.entityString should include ("body={\"key\":\"value\"}")

      // Cleanup
      unbind(serverBinding)
    }

    "return error response for a connection error" in {
      val actor = spawnActor()

      val probe = testKit.createTestProbe[StatusReply[HttpClientActor.Response]]("response-probe")
      actor ! HttpClientActor.SendRequest(
        method = HttpMethods.GET,
        uri = Uri("http://localhost:1"), // Port 1 is typically not in use
        replyTo = probe.ref
      )

      val message = expectError(probe)
      message should include ("Connection refused")
    }

    "handle multiple concurrent requests correctly" in {
      val actor = spawnActor()
      val serverBinding = bindTestServer()
      val port = serverBinding.localAddress.getPort

      val numRequests = 5
      val probes = (1 to numRequests).map { i =>
        testKit.createTestProbe[StatusReply[HttpClientActor.Response]](s"response-probe-$i")
      }

      probes.zipWithIndex.foreach { case (probe, i) =>
        actor ! HttpClientActor.SendRequest(
          method = HttpMethods.GET,
          uri = makeUri(port, "/echo"),
          replyTo = probe.ref
        )
      }

      probes.foreach { probe =>
        val resp = expectSuccess(probe)
        resp.response.status shouldEqual StatusCodes.OK
        resp.entityString shouldEqual "method=GET,path=/echo"
      }

      // Cleanup
      unbind(serverBinding)
    }

    "return correct status code for non-2xx responses" in {
      val actor = spawnActor()
      val serverBinding = bindTestServer()
      val port = serverBinding.localAddress.getPort

      val probe = testKit.createTestProbe[StatusReply[HttpClientActor.Response]]("response-probe")
      actor ! HttpClientActor.SendRequest(
        method = HttpMethods.GET,
        uri = makeUri(port, "/status"),
        replyTo = probe.ref
      )

      val resp = expectSuccess(probe)
      resp.response.status shouldEqual StatusCodes.TooManyRequests
      resp.entityString shouldEqual "rate limited"

      // Cleanup
      unbind(serverBinding)
    }

    "forward custom headers to the server" in {
      val actor = spawnActor()
      val serverBinding = bindTestServer()
      val port = serverBinding.localAddress.getPort

      val probe = testKit.createTestProbe[StatusReply[HttpClientActor.Response]]("response-probe")
      actor ! HttpClientActor.SendRequest(
        method = HttpMethods.GET,
        uri = makeUri(port, "/headers"),
        headers = Seq(
          RawHeader("X-API-Key", "secret-key-123"),
          RawHeader("X-Custom", "custom-value")
        ),
        replyTo = probe.ref
      )

      val resp = expectSuccess(probe)
      resp.response.status shouldEqual StatusCodes.OK
      resp.entityString should include ("X-API-Key=secret-key-123")
      resp.entityString should include ("X-Custom=custom-value")

      // Cleanup
      unbind(serverBinding)
    }

  }

}
