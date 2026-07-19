package com.archimond7450.archiemate.actors.http

import com.archimond7450.archiemate.http.HttpClientActor
import com.typesafe.config.{Config, ConfigFactory}
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.parser.decode
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.HttpMethods
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.pattern.StatusReply
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Promise
import scala.concurrent.duration.*

case class TestResponse(id: String, name: String)

object TestResponse {
  given Decoder[TestResponse] = deriveDecoder
}

class HttpRequestActorSpec
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

  private def spawnHttpClient(): ActorRef[HttpClientActor.Command] = {
    testKit.spawn(
      Behaviors.supervise(HttpClientActor(classicProvider, testConfig))
        .onFailure[Throwable](SupervisorStrategy.resume),
      s"http-client-${java.util.UUID.randomUUID().toString.take(8)}"
    )
  }

  private def spawnHttpRequestActor(
      httpClient: ActorRef[HttpClientActor.Command]
  ): ActorRef[HttpRequestActor.Command] = {
    testKit.spawn(
      Behaviors.supervise(HttpRequestActor(httpClient))
        .onFailure[Throwable](SupervisorStrategy.resume),
      s"http-request-${java.util.UUID.randomUUID().toString.take(8)}"
    )
  }

  private def bindTestServer(): Http.ServerBinding = {
    val route: Route =
      path("json") {
        get {
          complete(StatusCodes.OK, """{"id":"42","name":"alice"}""")
        }
      } ~
      path("error") {
        get {
          complete(StatusCodes.InternalServerError, """{"error":"server error"}""")
        }
      } ~
      path("malformed") {
        get {
          complete(StatusCodes.OK, """not valid json at all""")
        }
      }

    Http().newServerAt("localhost", 0).bind(route).futureValue
  }

  private def makeUri(port: Int, path: String): Uri =
    Uri("http://localhost:" + port + path)

  private def expectSuccess[T](
      probe: TestProbe[StatusReply[T]]
  ): T =
    probe.receiveMessage() match {
      case StatusReply.Success(value) => value.asInstanceOf[T]
      case StatusReply.Error(message) => fail(s"Expected success, got error: $message")
    }

  private def expectError[T](
      probe: TestProbe[StatusReply[T]]
  ): String =
    probe.receiveMessage() match {
      case StatusReply.Success(_) => "Expected error"
      case StatusReply.Error(ex) => ex.getMessage
    }

  private def unbind(serverBinding: Http.ServerBinding): Unit = {
    val p = Promise[Unit]()
    serverBinding
      .unbind()
      .onComplete { _ => p.success(()) }(scala.concurrent.ExecutionContext.global)
    scala.concurrent.Await.result(p.future, 10.seconds)
  }

  "HttpRequestActor" should {

    "decode a successful JSON response" in {
      val httpClient = spawnHttpClient()
      val actor = spawnHttpRequestActor(httpClient)
      val serverBinding = bindTestServer()
      val port = serverBinding.localAddress.getPort

      val probe = testKit.createTestProbe[StatusReply[TestResponse]]("response-probe")
      actor ! HttpRequestActor.Request[TestResponse](
        method = HttpMethods.GET,
        uri = makeUri(port, "/json"),
        decode = str => decode[TestResponse](str).toTry,
        replyTo = probe.ref
      )

      val resp = expectSuccess(probe)
      resp.id shouldEqual("42")
      resp.name shouldEqual("alice")

      unbind(serverBinding)
    }

    "return an error for HTTP error status codes" in {
      val httpClient = spawnHttpClient()
      val actor = spawnHttpRequestActor(httpClient)
      val serverBinding = bindTestServer()
      val port = serverBinding.localAddress.getPort

      val probe = testKit.createTestProbe[StatusReply[TestResponse]]("response-probe")
      actor ! HttpRequestActor.Request[TestResponse](
        method = HttpMethods.GET,
        uri = makeUri(port, "/error"),
        decode = str => decode[TestResponse](str).toTry,
        replyTo = probe.ref
      )

      val msg = expectError[TestResponse](probe)
      msg should include("DecodingFailure")

      unbind(serverBinding)
    }

    "return a decode failure for malformed JSON" in {
      val httpClient = spawnHttpClient()
      val actor = spawnHttpRequestActor(httpClient)
      val serverBinding = bindTestServer()
      val port = serverBinding.localAddress.getPort

      val probe = testKit.createTestProbe[StatusReply[TestResponse]]("response-probe")
      actor ! HttpRequestActor.Request[TestResponse](
        method = HttpMethods.GET,
        uri = makeUri(port, "/malformed"),
        decode = str => decode[TestResponse](str).toTry,
        replyTo = probe.ref
      )

      val msg = expectError[TestResponse](probe)
      msg should include("expected null got")

      unbind(serverBinding)
    }

    "return a connection error when the server is unreachable" in {
      val httpClient = spawnHttpClient()
      val actor = spawnHttpRequestActor(httpClient)

      // Use a port with no server listening
      val probe = testKit.createTestProbe[StatusReply[TestResponse]]("response-probe")
      actor ! HttpRequestActor.Request[TestResponse](
        method = HttpMethods.GET,
        uri = Uri("http://localhost:9999"),
        decode = str => decode[TestResponse](str).toTry,
        replyTo = probe.ref
      )

      val msg = expectError[TestResponse](probe)
      msg should include("Connection refused")
    }

    "handle concurrent requests correctly" in {
      val httpClient = spawnHttpClient()
      val actor = spawnHttpRequestActor(httpClient)
      val serverBinding = bindTestServer()
      val port = serverBinding.localAddress.getPort

      val probe1 = testKit.createTestProbe[StatusReply[TestResponse]]("response-probe-1")
      val probe2 = testKit.createTestProbe[StatusReply[TestResponse]]("response-probe-2")

      actor ! HttpRequestActor.Request[TestResponse](
        method = HttpMethods.GET,
        uri = makeUri(port, "/json"),
        decode = str => decode[TestResponse](str).toTry,
        replyTo = probe1.ref
      )

      actor ! HttpRequestActor.Request[TestResponse](
        method = HttpMethods.GET,
        uri = makeUri(port, "/json"),
        decode = str => decode[TestResponse](str).toTry,
        replyTo = probe2.ref
      )

      val resp1 = expectSuccess(probe1)
      val resp2 = expectSuccess(probe2)

      resp1.id shouldEqual("42")
      resp1.name shouldEqual("alice")
      resp2.id shouldEqual("42")
      resp2.name shouldEqual("alice")

      unbind(serverBinding)
    }

  }

}
