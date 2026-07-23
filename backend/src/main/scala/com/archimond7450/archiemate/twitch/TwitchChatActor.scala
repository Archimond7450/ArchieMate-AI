package com.archimond7450.archiemate.twitch

import com.archimond7450.archiemate.actors.websocket.WebSocketClient
import com.archimond7450.archiemate.actors.websocket.WebSocketClient.{
  Connected => WsConnected,
  Disconnected => WsDisconnected,
  Event => WsEvent,
  Failed => WsFailed,
  IncomingText
}
import com.archimond7450.archiemate.settings.TwitchIrcConfig
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.http.scaladsl.model.Uri

import scala.util.matching.Regex

/** Manages a Twitch IRC WebSocket connection for a single user.
  *
  * Handles the IRC protocol lifecycle:
  *   - PASS + NICK on connect
  *   - CAP REQ for twitch.tv/tags and twitch.tv/command
  *   - Auto-PONG on PING from server
  *   - JOIN/LEAVE channels
  *   - Sending chat messages (stand-alone or replies)
  *
  * Events are delivered to the parent actor via the typed `Event` trait.
  *
  * @note
  *   This actor must be supervised by its parent with a restart strategy to
  *   preserve state across failures:
  *   {{{supervise(TwitchChatActor(...)).onFailure[Throwable](SupervisorStrategy.resume)}}}
  */
object TwitchChatActor {

  private val actorName = "twitch-chat-actor"

  // ----------------------------------------------------------------
  // Commands
  // ----------------------------------------------------------------

  sealed trait Command

  /** Send a stand-alone chat message to a channel. */
  final case class SendChatMessage(channel: String, text: String) extends Command

  /** Send a reply to a message (uses Twitch reply_thread_id). */
  final case class SendReply(
      channel: String,
      text: String,
      replyThreadId: String
  ) extends Command

  /** Join a channel (case-insensitive). */
  final case class JoinChannel(channel: String) extends Command

  /** Leave a channel. */
  final case class LeaveChannel(channel: String) extends Command

  // ----------------------------------------------------------------
  // Events (delivered to parent)
  // ----------------------------------------------------------------

  sealed trait Event

  /** IRC connection was established and login succeeded. */
  final case class LoginSuccess(nick: String) extends Event

  /** IRC login failed (invalid token, etc.). */
  final case class LoginFailed(reason: String) extends Event

  /** IRC connection was closed gracefully. */
  case object Disconnected extends Event

  /** IRC connection failed. */
  final case class Failed(cause: Throwable) extends Event

  // ----------------------------------------------------------------
  // Internal commands
  // ----------------------------------------------------------------

  private final case class InternalIncoming(text: String) extends Command
  private case object InternalConnected extends Command
  private case object InternalDisconnected extends Command
  private final case class InternalFailed(cause: Throwable) extends Command

  // ----------------------------------------------------------------
  // Internal state
  // ----------------------------------------------------------------

  private case class State(
      connected: Boolean,
      nick: String,
      pendingMessages: List[String] = Nil
  )

  // ----------------------------------------------------------------
  // IRC protocol constants
  // ----------------------------------------------------------------

  private val PingPattern = """^PING\s+(.*)$""".r
  private val IrcNumericPattern = """^:(\S+)!\S+ (\d{3}) (.*)$""".r

  // ----------------------------------------------------------------
  // Factory
  // ----------------------------------------------------------------

  def apply(
      config: TwitchIrcConfig,
      nick: String,
      parent: ActorRef[Event]
  )(using classicProvider: ClassicActorSystemProvider): Behavior[Command] =
    Behaviors.supervise[Command] {
      Behaviors.setup { ctx =>
        ctx.log.info("TwitchChatActor initializing for {}", nick)

        // Convert WebSocket events to IRC protocol handling
        val wsAdapter = ctx.messageAdapter[WsEvent] { event =>
          event match {
            case IncomingText(text) => InternalIncoming(text)
            case WsConnected(_)     => InternalConnected
            case WsDisconnected     => parent ! Disconnected; InternalDisconnected
            case WsFailed(ex)       => parent ! Failed(ex); InternalFailed(ex)
          }
        }

        val wsClient = ctx.spawn(
          Behaviors.supervise(
            WebSocketClient(
              uri = Uri(s"${config.scheme}://${config.server}:${config.port}/"),
              parent = wsAdapter.asInstanceOf[ActorRef[WsEvent]],
              config = com.archimond7450.archiemate.settings.WebSocketConfig(
                reconnectDelay = com.archimond7450.archiemate.settings.WebSocketConfig().reconnectDelay,
                maxReconnectAttempts = com.archimond7450.archiemate.settings.WebSocketConfig().maxReconnectAttempts
              )
            )
          ).onFailure[Throwable](SupervisorStrategy.resume),
          "websocket-client"
        )
        new TwitchChatActor(ctx, config, nick, parent, wsClient).initial()
      }
    }.onFailure[Throwable](SupervisorStrategy.resume)

}

class TwitchChatActor private (
    ctx: ActorContext[TwitchChatActor.Command],
    config: TwitchIrcConfig,
    nick: String,
    parent: ActorRef[TwitchChatActor.Event],
    wsClient: ActorRef[WebSocketClient.Command]
) {

  import TwitchChatActor.*

  def initial(): Behavior[Command] =
    Behaviors.withMdc(Map("actor" -> actorName, "nick" -> nick)) {
      Behaviors.logMessages(mainBehavior(State(connected = false, nick)))
    }

  private def mainBehavior(state: State): Behavior[Command] =
    Behaviors.receiveMessage {
      case cmd =>
        handleCommand(cmd, state)
    }

  private def handleCommand(cmd: Command, state: State): Behavior[Command] =
    cmd match {
      case SendChatMessage(channel, text) =>
        handleSendChat(state, channel, text, replyThreadId = null)
      case SendReply(channel, text, replyThreadId) =>
        handleSendChat(state, channel, text, replyThreadId)
      case JoinChannel(channel) =>
        handleJoin(state, channel)
      case LeaveChannel(channel) =>
        handleLeave(state, channel)
      case InternalIncoming(text) =>
        handleMessage(state, text)
      case InternalConnected =>
        handleConnected(state)
      case InternalDisconnected =>
        ctx.log.info("WebSocket disconnected")
        Behaviors.same
      case InternalFailed(ex) =>
        ctx.log.error("WebSocket failed: {}", ex.getMessage)
        parent ! Failed(ex)
        mainBehavior(state)
      case other =>
        ctx.log.warn("Ignoring command: {}", other)
        Behaviors.same
    }

  // ----------------------------------------------------------------
  // IRC login sequence
  // ----------------------------------------------------------------

  private def handleConnected(state: State): Behavior[Command] = {
    ctx.log.info("WebSocket connected for {}, sending IRC login sequence", nick)
    sendWsText(s"PASS oauth:${config.ircToken}")
    sendWsText(s"NICK :$nick")
    sendWsText("CAP REQ :twitch.tv/tags")
    sendWsText("CAP REQ :twitch.tv/command")
    mainBehavior(state)
  }

  // ----------------------------------------------------------------
  // Message handlers
  // ----------------------------------------------------------------

  private def handleSendChat(
      state: State,
      channel: String,
      text: String,
      replyThreadId: String
  ): Behavior[Command] = {
    val message = buildMessage(channel, text, replyThreadId)
    val newState = if (state.connected) {
      sendWsText(message)
      state
    } else {
      state.copy(pendingMessages = state.pendingMessages :+ message)
    }
    mainBehavior(newState)
  }

  private def handleJoin(state: State, channel: String): Behavior[Command] = {
    val normalized = channel.toLowerCase
    val newState = if (state.connected) {
      sendWsText(s"JOIN #$normalized")
      state
    } else {
      state
    }
    mainBehavior(newState)
  }

  private def handleLeave(state: State, channel: String): Behavior[Command] = {
    val normalized = channel.toLowerCase
    val newState = if (state.connected) {
      sendWsText(s"PART #$normalized")
      state
    } else {
      state
    }
    mainBehavior(newState)
  }

  private def handleMessage(state: State, text: String): Behavior[Command] = {
    text match {
      // PING from server → PONG back
      case PingPattern(server) =>
        sendWsText(s"PONG :$server")
        mainBehavior(state)

      // IRC numeric responses
      case IrcNumericPattern(_, "001", _) =>
        // Login successful
        ctx.log.info("Twitch IRC login successful for {}", nick)
        parent ! LoginSuccess(nick)
        mainBehavior(state.copy(connected = true))

      case IrcNumericPattern(_, "421", msg) =>
        // Login failed - unknown command
        val reason421 = extractErrorMessage(msg)
        ctx.log.error("Twitch IRC login failed (421): {}", reason421)
        parent ! LoginFailed(reason421)
        mainBehavior(state)

      case IrcNumericPattern(_, "481", msg) =>
        // Login failed - not logged in
        val reason481 = extractErrorMessage(msg)
        ctx.log.error("Twitch IRC login failed (481): {}", reason481)
        parent ! LoginFailed(reason481)
        mainBehavior(state)

      case IrcNumericPattern(_, _, msg) =>
        ctx.log.debug("IRC numeric: {}", msg)
        mainBehavior(state)

      case _ =>
        ctx.log.debug("Received IRC message: {}", text)
        mainBehavior(state)
    }
  }

  // ----------------------------------------------------------------
  // IRC message building
  // ----------------------------------------------------------------

  private def buildMessage(
      channel: String,
      text: String,
      replyThreadId: String
  ): String = {
    val normalized = channel.toLowerCase
    replyThreadId match {
      case null | "" =>
        s"PRIVMSG #$normalized :$text"
      case threadId =>
        s"@reply-parent-msg-id=$threadId PRIVMSG #$normalized :$text"
    }
  }

  private def extractErrorMessage(text: String): String = {
    val parts = text.split(" ", 4)
    if (parts.length >= 4) parts(3).replace(":", "")
    else text
  }

  // ----------------------------------------------------------------
  // WebSocket helpers
  // ----------------------------------------------------------------

  private def sendWsText(text: String): Unit = {
    wsClient ! WebSocketClient.SendText(text)
  }

}
