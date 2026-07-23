package com.archimond7450.archiemate.actors.websocket

import com.archimond7450.archiemate.settings.WebSocketConfig
import org.apache.pekko.Done
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.ws.{
  BinaryMessage,
  Message,
  TextMessage,
  WebSocketRequest,
  WebSocketUpgradeResponse
}
import org.apache.pekko.http.scaladsl.model.{StatusCodes, Uri}
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.{
  Flow,
  Keep,
  Sink,
  Source,
  SourceQueueWithComplete
}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** Generic WebSocket client actor for sending/receiving text and binary
  * messages over WebSocket connections.
  *
  * This actor handles:
  *   - WebSocket connection lifecycle (connect, disconnect, reconnect)
  *   - Sending text and binary messages
  *   - Receiving text and binary messages
  *   - Automatic reconnection with configurable delay and max attempts
  *
  * Messages are delivered to the parent actor via a typed Event trait.
  *
  * @note
  *   This actor must be supervised by its parent with a restart strategy to
  *   preserve state across failures:
  *   {{{supervise(WebSocketClient(...)).onFailure[Throwable](SupervisorStrategy.restart)}}}
  */
object WebSocketClient {

  private val actorName = "websocket-client"

  // ----------------------------------------------------------------
  // Commands
  // ----------------------------------------------------------------

  sealed trait Command

  /** Send a text message over the WebSocket connection. */
  final case class SendText(text: String) extends Command

  /** Send a binary message over the WebSocket connection. */
  final case class SendBinary(data: Array[Byte]) extends Command

  /** Stop the WebSocket connection gracefully. */
  case object Stop extends Command

  // ----------------------------------------------------------------
  // Events (delivered to parent)
  // ----------------------------------------------------------------

  sealed trait Event

  /** WebSocket connection was successfully established. */
  final case class Connected(connectionId: String) extends Event

  /** A text message was received from the WebSocket server. */
  final case class IncomingText(text: String) extends Event

  /** A binary message was received from the WebSocket server. */
  final case class IncomingBinary(data: Array[Byte]) extends Event

  /** WebSocket connection was closed gracefully. */
  case object Disconnected extends Event

  /** WebSocket connection failed (upgrade or runtime error). */
  final case class Failed(cause: Throwable) extends Event

  // ----------------------------------------------------------------
  // Internal commands
  // ----------------------------------------------------------------

  private final case class InternalUpgradeSucceeded(connectionId: String)
      extends Command
  private final case class InternalConnected(
      queue: SourceQueueWithComplete[Message]
  ) extends Command
  private final case class InternalFailed(cause: Throwable) extends Command
  private case object InternalDone extends Command
  private final case class InternalIncomingText(text: String) extends Command
  private final case class InternalIncomingBinary(data: Array[Byte])
      extends Command

  // ----------------------------------------------------------------
  // Initialization
  // ----------------------------------------------------------------

  def apply(
      uri: Uri,
      parent: ActorRef[Event],
      config: WebSocketConfig = WebSocketConfig()
  )(using classicProvider: ClassicActorSystemProvider): Behavior[Command] =
    Behaviors.supervise[Command] {
      Behaviors.setup { ctx =>
        given ExecutionContext = scala.concurrent.ExecutionContext.global
        new WebSocketClient(ctx, uri, parent, config).initial()
      }
    }.onFailure[Throwable](SupervisorStrategy.restartWithBackoff(
      config.reconnectDelay,
      config.reconnectDelay * 10,
      0.2
    ))

}

class WebSocketClient private (
    ctx: ActorContext[WebSocketClient.Command],
    uri: Uri,
    parent: ActorRef[WebSocketClient.Event],
    config: WebSocketConfig
)(using
    classicProvider: ClassicActorSystemProvider,
    execEc: ExecutionContext
) {

  import WebSocketClient.*

  def initial(): Behavior[Command] =
    Behaviors.withMdc(Map("actor" -> actorName, "uri" -> uri.toString())) {
      connect()
    }

  /** Initiate a WebSocket connection. Returns the waiting behavior. */
  private def connect(): Behavior[Command] = {
    val connectionId = java.util.UUID.randomUUID().toString.take(8)

    val outgoing = Source
      .queue[Message](bufferSize = 64, OverflowStrategy.dropHead)
      .mapMaterializedValue { queue =>
        ctx.self ! InternalConnected(queue)
        queue
      }

    val incoming: Sink[Message, Future[Done]] = Sink.foreach {
      case tm: TextMessage.Strict =>
        ctx.self ! InternalIncomingText(tm.text)
      case bm: BinaryMessage.Strict =>
        ctx.self ! InternalIncomingBinary(bm.data.toArray)
      case tm: TextMessage.Streamed =>
        tm.toStrict(30.seconds).onComplete {
          case Success(text) => ctx.self ! InternalIncomingText(text.text)
          case Failure(ex)   => ctx.self ! InternalFailed(ex)
        }
      case bm: BinaryMessage.Streamed =>
        bm.toStrict(30.seconds).onComplete {
          case Success(bin) => ctx.self ! InternalIncomingBinary(bin.data.toArray)
          case Failure(ex)  => ctx.self ! InternalFailed(ex)
        }
    }

    val flow: Flow[Message, Message, Future[Done]] =
      Flow.fromSinkAndSourceMat(incoming, outgoing)(Keep.left)

    val request = WebSocketRequest(uri)

    // Start connection attempt
    val (upgradeResponse: Future[WebSocketUpgradeResponse], closed: Future[Done]) =
      Http().singleWebSocketRequest(request, flow)

    ctx.pipeToSelf(upgradeResponse) {
      case Success(resp) if resp.response.status == StatusCodes.SwitchingProtocols =>
        InternalUpgradeSucceeded(connectionId)
      case Success(resp) =>
        InternalFailed(
          new RuntimeException(s"WebSocket upgrade failed: ${resp.response.status}")
        )
      case Failure(ex) =>
        InternalFailed(ex)
    }

    ctx.pipeToSelf(closed) {
      case Success(_) => InternalDone
      case Failure(ex) => InternalFailed(ex)
    }

    waitingForQueue(connectionId)
  }

  /** Idle state — waiting for the queue to be ready. */
  private def waitingForQueue(
      connectionId: String
  ): Behavior[Command] =
    Behaviors.withStash(64) { buffer =>
      Behaviors.logMessages {
        Behaviors.receiveMessage {
          case InternalUpgradeSucceeded(`connectionId`) =>
            ctx.log.info("WebSocket upgrade succeeded")
            parent ! Connected(connectionId)
            Behaviors.same

          case InternalConnected(queue) =>
            ctx.pipeToSelf(queue.watchCompletion()) {
              case Success(_) => InternalDone
              case Failure(ex) => InternalFailed(ex)
            }
            buffer.unstashAll(active(connectionId, queue))

          case InternalFailed(ex) =>
            handleConnectionFailure(ex)
            Behaviors.stopped

          case InternalDone =>
            parent ! Disconnected
            Behaviors.stopped

          case Stop =>
            Behaviors.stopped

          case other: Command =>
            buffer.stash(other)
            Behaviors.same
        }
      }
    }

  /** Active state — WebSocket is connected and ready to send/receive. */
  private def active(
      connectionId: String,
      queue: SourceQueueWithComplete[Message]
  ): Behavior[Command] =
    Behaviors.withMdc(Map("connectionId" -> connectionId)) {
      Behaviors.logMessages {
        Behaviors.receiveMessage {
          case SendText(text) =>
            queue.offer(TextMessage(text))
            Behaviors.same

          case SendBinary(data) =>
            queue.offer(BinaryMessage(ByteString(data)))
            Behaviors.same

          case InternalIncomingText(text) =>
            parent ! IncomingText(text)
            Behaviors.same

          case InternalIncomingBinary(data) =>
            parent ! IncomingBinary(data)
            Behaviors.same

          case InternalUpgradeSucceeded(`connectionId`) =>
            ctx.log.info("WebSocket upgrade succeeded")
            parent ! Connected(connectionId)
            Behaviors.same

          case InternalDone =>
            ctx.log.info("WebSocket connection closed")
            parent ! Disconnected
            Behaviors.same

          case InternalFailed(ex) =>
            handleConnectionFailure(ex)
            Behaviors.same

          case Stop =>
            completeQueue(queue)
            parent ! Disconnected
            Behaviors.stopped

          case other: Command =>
            ctx.log.warn("Ignoring command: {}", other)
            Behaviors.same
        }
      }
    }

  /** Handle a connection failure: notify parent and attempt to reconnect. */
  private def handleConnectionFailure(ex: Throwable): Unit = {
    ctx.log.error("WebSocket connection failed: {}", ex.getMessage)
    parent ! Failed(ex)
  }

  /** Gracefully complete the outgoing queue. */
  private def completeQueue(queue: SourceQueueWithComplete[Message]): Unit = {
    queue.complete()
  }

}
