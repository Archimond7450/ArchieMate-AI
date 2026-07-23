package com.archimond7450.archiemate.actors.websocket

import com.archimond7450.archiemate.settings.WebSocketConfig
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.ws.{
  BinaryMessage,
  Message,
  TextMessage
}
import org.apache.pekko.http.scaladsl.model.{StatusCodes, Uri}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}
import org.apache.pekko.util.ByteString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Promise
import scala.concurrent.duration.*

class WebSocketClientSpec
    extends ScalaTestWithActorTestKit(
      WebSocketClientSpec.testConfig
    )
    with AnyWordSpecLike
    with Matchers {

  private val classicProvider: ClassicActorSystemProvider = new ClassicActorSystemProvider {
    def classicSystem = org.apache.pekko.actor.ActorSystem(
      "test-classic",
      WebSocketClientSpec.testConfig
    )
  }

  private def spawnWebSocketClient(
      uri: Uri
  )(using
      ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[
        WebSocketClient.Command
      ]
  ): ActorRef[WebSocketClient.Command] = {
    val probe = testKit.createTestProbe[WebSocketClient.Event]("event-probe")
    testKit.spawn(
      Behaviors.supervise(
        WebSocketClient(
          uri = uri,
          parent = probe.ref,
          config = WebSocketConfig(
            reconnectDelay = 100.millis,
            maxReconnectAttempts = 3
          )
        )
      ).onFailure[Throwable](SupervisorStrategy.restart),
      s"websocket-client-${java.util.UUID.randomUUID().toString.take(8)}"
    )
  }

  private def bindEchoServer(): Http.ServerBinding = {
    val (queue, source) = Source.queue[Message](64, OverflowStrategy.dropHead)
      .preMaterialize()

    val route: Route =
      path("echo") {
        get {
          handleWebSocketMessages {
            Flow.fromSinkAndSource(
              Sink.foreach[Message] { msg =>
                queue.offer(msg)
              },
              source
            )
          }
        }
      }

    Http()
      .newServerAt("localhost", 0)
      .bind(route)
      .futureValue
  }

  private def unbind(serverBinding: Http.ServerBinding): Unit = {
    val p = Promise[Unit]()
    serverBinding
      .unbind()
      .onComplete { _ => p.success(()) }(scala.concurrent.ExecutionContext.global)
    scala.concurrent.Await.result(p.future, 10.seconds)
  }

  "WebSocketClient" should {

    "notify parent on successful connection" in {
      val serverBinding = bindEchoServer()
      val port = serverBinding.localAddress.getPort
      val uri = Uri(s"ws://localhost:$port/echo")

      val probe = testKit.createTestProbe[WebSocketClient.Event]("event-probe")
      val client = testKit.spawn(
        Behaviors.supervise(
          WebSocketClient(
            uri = uri,
            parent = probe.ref,
            config = WebSocketConfig(
              reconnectDelay = 100.millis,
              maxReconnectAttempts = 3
            )
          )
        ).onFailure[Throwable](SupervisorStrategy.restart),
        "websocket-client-1"
      )

      probe.expectMessageType[WebSocketClient.Connected].connectionId should not be null

      client ! WebSocketClient.Stop
      unbind(serverBinding)
    }

    "send and receive text messages" in {
      val serverBinding = bindEchoServer()
      val port = serverBinding.localAddress.getPort
      val uri = Uri(s"ws://localhost:$port/echo")

      val probe = testKit.createTestProbe[WebSocketClient.Event]("event-probe")
      val client = testKit.spawn(
        Behaviors.supervise(
          WebSocketClient(
            uri = uri,
            parent = probe.ref,
            config = WebSocketConfig(
              reconnectDelay = 100.millis,
              maxReconnectAttempts = 3
            )
          )
        ).onFailure[Throwable](SupervisorStrategy.restart),
        "websocket-client-2"
      )

      // Wait for connected event
      probe.receiveMessage(5.seconds) match {
        case _: WebSocketClient.Connected => // OK
        case other                        => fail(s"Expected Connected, got $other")
      }

      // Send text message
      client ! WebSocketClient.SendText("hello")

      // Receive echoed message
      probe.receiveMessage(5.seconds) match {
        case WebSocketClient.IncomingText(msg) =>
          msg shouldEqual "hello"
        case other => fail(s"Expected IncomingText, got $other")
      }

      client ! WebSocketClient.Stop
      unbind(serverBinding)
    }

    "send and receive binary messages" in {
      val serverBinding = bindEchoServer()
      val port = serverBinding.localAddress.getPort
      val uri = Uri(s"ws://localhost:$port/echo")

      val probe = testKit.createTestProbe[WebSocketClient.Event]("event-probe")
      val client = testKit.spawn(
        Behaviors.supervise(
          WebSocketClient(
            uri = uri,
            parent = probe.ref,
            config = WebSocketConfig(
              reconnectDelay = 100.millis,
              maxReconnectAttempts = 3
            )
          )
        ).onFailure[Throwable](SupervisorStrategy.restart),
        "websocket-client-3"
      )

      // Wait for connected event
      probe.receiveMessage(5.seconds) match {
        case _: WebSocketClient.Connected => // OK
        case other                        => fail(s"Expected Connected, got $other")
      }

      // Send binary message
      val data = ByteString("binary data")
      client ! WebSocketClient.SendBinary(data.toArray)

      // Receive echoed message
      probe.receiveMessage(5.seconds) match {
        case WebSocketClient.IncomingBinary(msg) =>
          ByteString(msg) shouldEqual data
        case other => fail(s"Expected IncomingBinary, got $other")
      }

      client ! WebSocketClient.Stop
      unbind(serverBinding)
    }

    "notify parent on disconnection" in {
      val serverBinding = bindEchoServer()
      val port = serverBinding.localAddress.getPort
      val uri = Uri(s"ws://localhost:$port/echo")

      val probe = testKit.createTestProbe[WebSocketClient.Event]("event-probe")
      val client = testKit.spawn(
        Behaviors.supervise(
          WebSocketClient(
            uri = uri,
            parent = probe.ref,
            config = WebSocketConfig(
              reconnectDelay = 100.millis,
              maxReconnectAttempts = 3
            )
          )
        ).onFailure[Throwable](SupervisorStrategy.restart),
        "websocket-client-4"
      )

      // Wait for connected event
      probe.receiveMessage(5.seconds) match {
        case _: WebSocketClient.Connected => // OK
        case other                        => fail(s"Expected Connected, got $other")
      }

      // Stop the client
      client ! WebSocketClient.Stop

      // Wait for disconnected event
      probe.receiveMessage(5.seconds) match {
        case WebSocketClient.Disconnected => // OK
        case other                           => fail(s"Expected Disconnected, got $other")
      }

      unbind(serverBinding)
    }

    "handle connection failure gracefully" in {
      val probe = testKit.createTestProbe[WebSocketClient.Event]("event-probe")
      // Use an invalid URI that will fail to connect
      val uri = Uri("ws://localhost:1")

      val client = testKit.spawn(
        Behaviors.supervise(
          WebSocketClient(
            uri = uri,
            parent = probe.ref,
            config = WebSocketConfig(
              reconnectDelay = 100.millis,
              maxReconnectAttempts = 1
            )
          )
        ).onFailure[Throwable](SupervisorStrategy.restart),
        "websocket-client-5"
      )

      // Should eventually receive a failure event
      val event = probe.receiveMessage(5.seconds)
      event match {
        case WebSocketClient.Failed(ex) =>
          ex.getMessage should include("Connection")
        case other => fail(s"Expected Failed, got $other")
      }

      client ! WebSocketClient.Stop
    }

  }

}

object WebSocketClientSpec {
  val testConfig: com.typesafe.config.Config = ConfigFactory.parseString("""
    archiemate.websocket {
      reconnect-delay = "100ms"
      max-reconnect-attempts = 3
    }
    pekko.http.host-connection-pool.client.connect-timeout = 1s
    pekko.test.single-expect-default = 10s
  """).withFallback(ConfigFactory.load())
}
