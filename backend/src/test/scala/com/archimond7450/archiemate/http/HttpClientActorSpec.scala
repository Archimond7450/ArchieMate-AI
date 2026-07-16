package com.archimond7450.archiemate.http

import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.HttpMethods.*
import org.apache.pekko.http.scaladsl.model.MediaTypes.`application/json`
import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpEntity, StatusCode, StatusCodes, Uri}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Await
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
  """).withFallback(ConfigFactory.load())

  private def spawnActor(): ActorRef[HttpClientActor.Command] =
    testKit.spawn(HttpClientActor(testConfig), s"http-client-${java.util.UUID.randomUUID().toString.take(8)}")

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

  "HttpClientActor" should {

    "return success response for a GET request" in {
      val actor = spawnActor()
      val serverBinding = bindTestServer()
      val port = serverBinding.localAddress.getPort

      val probe = testKit.createTestProbe[HttpClientActor.SendResponse]("response-probe")
      actor ! HttpClientActor.SendRequest(
        uri = makeUri(port, "/echo"),
        replyTo = probe.ref
      )

      probe.receiveMessage() match {
        case HttpClientActor.RequestSuccess(statusCode, _, body) =>
          statusCode shouldEqual StatusCodes.OK
          body shouldEqual "method=GET,path=/echo"
        case HttpClientActor.RequestFailure(message) =>
          fail(s"Expected RequestSuccess, got RequestFailure: $message")
      }

      // Cleanup
      Await.result(serverBinding.unbind(), 5.seconds)
    }

    "return success response for a POST request with entity and custom headers" in {
      val actor = spawnActor()
      val serverBinding = bindTestServer()
      val port = serverBinding.localAddress.getPort

      val probe = testKit.createTestProbe[HttpClientActor.SendResponse]("response-probe")
      actor ! HttpClientActor.SendRequest(
        uri = makeUri(port, "/echo"),
        method = POST,
        headers = Seq(
          HttpHeader.parse("Content-Type", "application/json") match {
            case HttpHeader.ParsingResult.Ok(header, _) => header
            case _ => throw new RuntimeException("Invalid header")
          }
        ),
        entity = Some(HttpEntity.Default(`application/json`, 15, org.apache.pekko.stream.scaladsl.Source.single(org.apache.pekko.util.ByteString("""{"key":"value"}""")))),
        replyTo = probe.ref
      )

      probe.receiveMessage() match {
        case HttpClientActor.RequestSuccess(statusCode, headers, body) =>
          statusCode shouldEqual StatusCodes.OK
          body should include ("method=POST")
          body should include ("path=/echo")
          body should include ("body={\"key\":\"value\"}")
        case HttpClientActor.RequestFailure(message) =>
          fail(s"Expected RequestSuccess, got RequestFailure: $message")
      }

      // Cleanup
      Await.result(serverBinding.unbind(), 5.seconds)
    }

    "return RequestFailure for a connection error" in {
      val actor = spawnActor()

      val probe = testKit.createTestProbe[HttpClientActor.SendResponse]("response-probe")
      actor ! HttpClientActor.SendRequest(
        uri = Uri("http://localhost:1"), // Port 1 is typically not in use
        replyTo = probe.ref
      )

      probe.receiveMessage() match {
        case HttpClientActor.RequestSuccess(_, _, _) =>
          fail("Expected RequestFailure for connection to closed port")
        case HttpClientActor.RequestFailure(message) =>
          message should include ("Connection refused")
      }
    }

    "handle multiple concurrent requests correctly" in {
      val actor = spawnActor()
      val serverBinding = bindTestServer()
      val port = serverBinding.localAddress.getPort

      val numRequests = 5
      val probes = (1 to numRequests).map { i =>
        testKit.createTestProbe[HttpClientActor.SendResponse](s"response-probe-$i")
      }

      probes.zipWithIndex.foreach { case (probe, i) =>
        actor ! HttpClientActor.SendRequest(
          uri = makeUri(port, "/echo"),
          replyTo = probe.ref
        )
      }

      probes.foreach { probe =>
        probe.receiveMessage() match {
          case HttpClientActor.RequestSuccess(statusCode, _, body) =>
            statusCode shouldEqual StatusCodes.OK
            body shouldEqual "method=GET,path=/echo"
          case HttpClientActor.RequestFailure(message) =>
            fail(s"Expected RequestSuccess, got RequestFailure: $message")
        }
      }

      // Cleanup
      Await.result(serverBinding.unbind(), 5.seconds)
    }

    "return correct status code for non-2xx responses" in {
      val actor = spawnActor()
      val serverBinding = bindTestServer()
      val port = serverBinding.localAddress.getPort

      val probe = testKit.createTestProbe[HttpClientActor.SendResponse]("response-probe")
      actor ! HttpClientActor.SendRequest(
        uri = makeUri(port, "/status"),
        replyTo = probe.ref
      )

      probe.receiveMessage() match {
        case HttpClientActor.RequestSuccess(statusCode, _, body) =>
          statusCode shouldEqual StatusCodes.TooManyRequests
          body shouldEqual "rate limited"
        case HttpClientActor.RequestFailure(message) =>
          fail(s"Expected RequestSuccess with error status, got RequestFailure: $message")
      }

      // Cleanup
      Await.result(serverBinding.unbind(), 5.seconds)
    }

    "forward custom headers to the server" in {
      val actor = spawnActor()
      val serverBinding = bindTestServer()
      val port = serverBinding.localAddress.getPort

      val probe = testKit.createTestProbe[HttpClientActor.SendResponse]("response-probe")
      actor ! HttpClientActor.SendRequest(
        uri = makeUri(port, "/headers"),
        headers = Seq(
          HttpHeader.parse("X-API-Key", "secret-key-123") match {
            case HttpHeader.ParsingResult.Ok(header, _) => header
            case _ => throw new RuntimeException("Invalid header")
          },
          HttpHeader.parse("X-Custom", "custom-value") match {
            case HttpHeader.ParsingResult.Ok(header, _) => header
            case _ => throw new RuntimeException("Invalid header")
          }
        ),
        replyTo = probe.ref
      )

      probe.receiveMessage() match {
        case HttpClientActor.RequestSuccess(statusCode, _, body) =>
          statusCode shouldEqual StatusCodes.OK
          body should include ("X-API-Key=secret-key-123")
          body should include ("X-Custom=custom-value")
        case HttpClientActor.RequestFailure(message) =>
          fail(s"Expected RequestSuccess, got RequestFailure: $message")
      }

      // Cleanup
      Await.result(serverBinding.unbind(), 5.seconds)
    }
  }
}
