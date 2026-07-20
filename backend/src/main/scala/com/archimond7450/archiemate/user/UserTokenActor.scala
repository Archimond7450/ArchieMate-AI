package com.archimond7450.archiemate.user

import com.archimond7450.archiemate.{CirceConfiguration, SerializerIDs, GenericSerializer}
import io.circe.derivation.{ConfiguredDecoder, ConfiguredEncoder}
import io.circe.{Decoder, Encoder}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.*
import org.apache.pekko.persistence.typed.RecoveryCompleted
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.ClassicActorSystemProvider

import java.time.Instant
import java.util.UUID

// ----------------------------------------------------------------
// Events
// ----------------------------------------------------------------

sealed trait UserTokenEvent
object UserTokenEvent {
  given Decoder[UserTokenEvent] = ConfiguredDecoder.derived(using CirceConfiguration.pekkoConfiguration)
  given Encoder[UserTokenEvent] = ConfiguredEncoder.derived(using CirceConfiguration.pekkoConfiguration)
}

// Twitch auth events
final case class TwitchAuthTokenRegistered(
    id: String,
    accessToken: String,
    refreshToken: String,
    expiresAt: Instant,
    platformUserId: String
) extends UserTokenEvent
private object TwitchAuthTokenRegistered {
  given Decoder[TwitchAuthTokenRegistered] = ConfiguredDecoder.derived(using CirceConfiguration.pekkoConfiguration)
  given Encoder[TwitchAuthTokenRegistered] = ConfiguredEncoder.derived(using CirceConfiguration.pekkoConfiguration)
}

final case class TwitchAuthTokenUpdated(
    id: String,
    accessToken: String,
    refreshToken: String,
    expiresAt: Instant
) extends UserTokenEvent
private object TwitchAuthTokenUpdated {
  given Decoder[TwitchAuthTokenUpdated] = ConfiguredDecoder.derived(using CirceConfiguration.pekkoConfiguration)
  given Encoder[TwitchAuthTokenUpdated] = ConfiguredEncoder.derived(using CirceConfiguration.pekkoConfiguration)
}

final case class TwitchAuthTokenRevoked(id: String) extends UserTokenEvent
private object TwitchAuthTokenRevoked {
  given Decoder[TwitchAuthTokenRevoked] = ConfiguredDecoder.derived(using CirceConfiguration.pekkoConfiguration)
  given Encoder[TwitchAuthTokenRevoked] = ConfiguredEncoder.derived(using CirceConfiguration.pekkoConfiguration)
}

// Platform connection events
final case class PlatformConnectionRegistered(
    platform: String,
    channelId: String,
    accessToken: String,
    refreshToken: String,
    expiresAt: Instant
) extends UserTokenEvent
private object PlatformConnectionRegistered {
  given Decoder[PlatformConnectionRegistered] = ConfiguredDecoder.derived(using CirceConfiguration.pekkoConfiguration)
  given Encoder[PlatformConnectionRegistered] = ConfiguredEncoder.derived(using CirceConfiguration.pekkoConfiguration)
}

final case class PlatformConnectionUpdated(
    platform: String,
    channelId: String,
    accessToken: String,
    refreshToken: String,
    expiresAt: Instant
) extends UserTokenEvent
private object PlatformConnectionUpdated {
  given Decoder[PlatformConnectionUpdated] = ConfiguredDecoder.derived(using CirceConfiguration.pekkoConfiguration)
  given Encoder[PlatformConnectionUpdated] = ConfiguredEncoder.derived(using CirceConfiguration.pekkoConfiguration)
}

final case class PlatformConnectionRevoked(platform: String, channelId: String) extends UserTokenEvent
private object PlatformConnectionRevoked {
  given Decoder[PlatformConnectionRevoked] = ConfiguredDecoder.derived(using CirceConfiguration.pekkoConfiguration)
  given Encoder[PlatformConnectionRevoked] = ConfiguredEncoder.derived(using CirceConfiguration.pekkoConfiguration)
}

// ----------------------------------------------------------------
// Serializer (top-level for Java reflection)
// ----------------------------------------------------------------

class UserTokenActorEventSerializer(system: ExtendedActorSystem, configPath: String)
    extends GenericSerializer[UserTokenEvent](
      "user-token-actor",
      SerializerIDs.userTokenActorId
    ) {

  override val toEvent: PartialFunction[AnyRef, UserTokenEvent] = {
    case event: UserTokenEvent => event
  }
}

// ----------------------------------------------------------------
// UserTokenActor
// ----------------------------------------------------------------

object UserTokenActor {

  private val actorName = "user-token-actor"

  // ----------------------------------------------------------------
  // Commands
  // ----------------------------------------------------------------

  sealed trait Command

  // Twitch auth commands (multiple tokens per user, always Twitch)
  final case class RegisterTwitchAuthToken(
      accessToken: String,
      refreshToken: String,
      expiresAt: Instant,
      platformUserId: String,
      replyTo: ActorRef[AuthResponse]
  ) extends Command
  final case class GetTwitchAuthToken(
      id: String,
      replyTo: ActorRef[AuthResponse]
  ) extends Command
  final case class GetAllTwitchAuthTokens(replyTo: ActorRef[AuthResponse]) extends Command
  final case class UpdateTwitchAuthToken(
      id: String,
      accessToken: String,
      refreshToken: String,
      expiresAt: Instant,
      replyTo: ActorRef[AuthResponse]
  ) extends Command
  final case class RevokeTwitchAuthToken(
      id: String,
      replyTo: ActorRef[AuthResponse]
  ) extends Command

  // Platform connection commands
  final case class RegisterPlatformConnection(
      platform: String,
      channelId: String,
      accessToken: String,
      refreshToken: String,
      expiresAt: Instant,
      replyTo: ActorRef[ConnectionResponse]
  ) extends Command
  final case class UpdatePlatformConnection(
      platform: String,
      channelId: String,
      accessToken: String,
      refreshToken: String,
      expiresAt: Instant,
      replyTo: ActorRef[ConnectionResponse]
  ) extends Command
  final case class RevokePlatformConnection(
      platform: String,
      channelId: String,
      replyTo: ActorRef[ConnectionResponse]
  ) extends Command
  final case class GetPlatformConnection(
      platform: String,
      channelId: String,
      replyTo: ActorRef[ConnectionResponse]
  ) extends Command
  final case class GetAllPlatformConnections(
      platform: String,
      replyTo: ActorRef[ConnectionResponse]
  ) extends Command
  final case class HasPlatformConnection(
      platform: String,
      channelId: String,
      replyTo: ActorRef[ConnectionResponse]
  ) extends Command

  // ----------------------------------------------------------------
  // Per-command response traits
  // ----------------------------------------------------------------

  sealed trait AuthResponse
  sealed trait ConnectionResponse

  final case class AuthRegistered(id: String, platformUserId: String) extends AuthResponse
  final case class AuthUpdated(id: String) extends AuthResponse
  final case class AuthRevoked(id: String) extends AuthResponse
  final case class AuthFound(token: PlatformToken) extends AuthResponse
  final case class AllAuthTokensFound(tokens: List[PlatformToken]) extends AuthResponse
  case object AuthNotFound extends AuthResponse

  final case class ConnectionRegistered(platform: String, channelId: String) extends ConnectionResponse
  final case class ConnectionUpdated(platform: String, channelId: String) extends ConnectionResponse
  final case class ConnectionRevoked(platform: String, channelId: String) extends ConnectionResponse
  final case class ConnectionFound(connection: PlatformConnection) extends ConnectionResponse
  final case class ConnectionNotFound(platform: String, channelId: String) extends ConnectionResponse
  final case class AllConnectionsFound(connections: List[PlatformConnection]) extends ConnectionResponse
  final case class HasConnection(hasConnection: Boolean) extends ConnectionResponse

  final case class Error(message: String) extends AuthResponse with ConnectionResponse

  // ----------------------------------------------------------------
  // Shared data classes
  // ----------------------------------------------------------------

  final case class PlatformToken(
      id: String,
      platform: String,
      accessToken: String,
      refreshToken: String,
      expiresAt: Instant,
      platformUserId: String
  )

  final case class PlatformConnection(
      platform: String,
      channelId: String,
      accessToken: String,
      refreshToken: String,
      expiresAt: Instant
  )

  // ----------------------------------------------------------------
  // State
  // ----------------------------------------------------------------

  private[user] case class State(
      twitchTokens: Map[String, PlatformToken] = Map.empty,
      connections: Map[String, List[PlatformConnection]] = Map.empty
  )

  // ----------------------------------------------------------------
  // Command handler
  // ----------------------------------------------------------------

  private val commandHandler: (State, Command) => ReplyEffect[UserTokenEvent, State] = {
    (state, command) =>
      command match {

        // --- Twitch auth commands ---

        case register: RegisterTwitchAuthToken =>
          val id = UUID.randomUUID().toString
          Effect
            .persist(TwitchAuthTokenRegistered(
              id,
              register.accessToken,
              register.refreshToken,
              register.expiresAt,
              register.platformUserId
            ))
            .thenReply(register.replyTo) { _ =>
              AuthRegistered(id, register.platformUserId)
            }

        case get: GetTwitchAuthToken =>
          state.twitchTokens.get(get.id) match {
            case Some(token) =>
              Effect.none.thenReply(get.replyTo) { _ =>
                AuthFound(token)
              }
            case None =>
              Effect.none.thenReply(get.replyTo) { _ =>
                AuthNotFound
              }
          }

        case getAll: GetAllTwitchAuthTokens =>
          Effect.none.thenReply(getAll.replyTo) { s =>
            AllAuthTokensFound(s.twitchTokens.values.toList)
          }

        case update: UpdateTwitchAuthToken =>
          state.twitchTokens.get(update.id) match {
            case Some(token) =>
              Effect
                .persist(TwitchAuthTokenUpdated(
                  update.id,
                  update.accessToken,
                  update.refreshToken,
                  update.expiresAt
                ))
                .thenReply(update.replyTo) { _ =>
                  AuthUpdated(update.id)
                }
            case None =>
              Effect.none.thenReply(update.replyTo) { _ =>
                Error(s"Auth token with id ${update.id} not found")
              }
          }

        case revoke: RevokeTwitchAuthToken =>
          state.twitchTokens.get(revoke.id) match {
            case Some(token) =>
              Effect
                .persist(TwitchAuthTokenRevoked(revoke.id))
                .thenReply(revoke.replyTo) { _ =>
                  AuthRevoked(revoke.id)
                }
            case None =>
              Effect.none.thenReply(revoke.replyTo) { _ =>
                Error(s"Auth token with id ${revoke.id} not found")
              }
          }

        // --- Platform connection commands ---

        case registerConn: RegisterPlatformConnection =>
          val existing = state.connections.getOrElse(registerConn.platform, Nil)
          if (existing.exists(_.channelId == registerConn.channelId)) {
            Effect.none.thenReply(registerConn.replyTo) { _ =>
              Error(s"Connection for channel ${registerConn.channelId} on ${registerConn.platform} already registered")
            }
          } else {
            Effect
              .persist(PlatformConnectionRegistered(
                registerConn.platform,
                registerConn.channelId,
                registerConn.accessToken,
                registerConn.refreshToken,
                registerConn.expiresAt
              ))
              .thenReply(registerConn.replyTo) { _ =>
                ConnectionRegistered(registerConn.platform, registerConn.channelId)
              }
          }

        case updateConn: UpdatePlatformConnection =>
          state.connections.get(updateConn.platform) match {
            case Some(conns) if conns.exists(_.channelId == updateConn.channelId) =>
              Effect
                .persist(PlatformConnectionUpdated(
                  updateConn.platform,
                  updateConn.channelId,
                  updateConn.accessToken,
                  updateConn.refreshToken,
                  updateConn.expiresAt
                ))
                .thenReply(updateConn.replyTo) { _ =>
                  ConnectionUpdated(updateConn.platform, updateConn.channelId)
                }
            case _ =>
              Effect.none.thenReply(updateConn.replyTo) { _ =>
                Error(s"Connection for channel ${updateConn.channelId} on ${updateConn.platform} not found")
              }
          }

        case revokeConn: RevokePlatformConnection =>
          state.connections.get(revokeConn.platform) match {
            case Some(conns) if conns.exists(_.channelId == revokeConn.channelId) =>
              Effect
                .persist(PlatformConnectionRevoked(revokeConn.platform, revokeConn.channelId))
                .thenReply(revokeConn.replyTo) { _ =>
                  ConnectionRevoked(revokeConn.platform, revokeConn.channelId)
                }
            case _ =>
              Effect.none.thenReply(revokeConn.replyTo) { _ =>
                Error(s"Connection for channel ${revokeConn.channelId} on ${revokeConn.platform} not found")
              }
          }

        case getConnection: GetPlatformConnection =>
          state.connections
            .get(getConnection.platform)
            .flatMap(_.find(_.channelId == getConnection.channelId)) match {
            case Some(conn) =>
              Effect.none.thenReply(getConnection.replyTo) { _ =>
                ConnectionFound(conn)
              }
            case None =>
              Effect.none.thenReply(getConnection.replyTo) { _ =>
                ConnectionNotFound(getConnection.platform, getConnection.channelId)
              }
          }

        case getAll: GetAllPlatformConnections =>
          Effect.none.thenReply(getAll.replyTo) { s =>
            AllConnectionsFound(s.connections.getOrElse(getAll.platform, Nil))
          }

        case hasConn: HasPlatformConnection =>
          Effect.none.thenReply(hasConn.replyTo) { s =>
            HasConnection(s.connections.getOrElse(hasConn.platform, Nil).exists(_.channelId == hasConn.channelId))
          }
      }
  }

  // ----------------------------------------------------------------
  // Event handler
  // ----------------------------------------------------------------

  private val eventHandler: (State, UserTokenEvent) => State = { (state, event) =>
    event match {
      case TwitchAuthTokenRegistered(id, accessToken, refreshToken, expiresAt, platformUserId) =>
        val token = PlatformToken(id, "twitch", accessToken, refreshToken, expiresAt, platformUserId)
        state.copy(twitchTokens = state.twitchTokens + (id -> token))

      case TwitchAuthTokenUpdated(id, accessToken, refreshToken, expiresAt) =>
        state.twitchTokens.get(id) match {
          case Some(token) =>
            state.copy(twitchTokens = state.twitchTokens + (id -> token.copy(accessToken = accessToken, refreshToken = refreshToken, expiresAt = expiresAt)))
          case None =>
            state
        }

      case TwitchAuthTokenRevoked(id) =>
        state.copy(twitchTokens = state.twitchTokens - id)

      case PlatformConnectionRegistered(platform, channelId, accessToken, refreshToken, expiresAt) =>
        val conn = PlatformConnection(platform, channelId, accessToken, refreshToken, expiresAt)
        val existing = state.connections.getOrElse(platform, Nil)
        state.copy(connections = state.connections + (platform -> (existing :+ conn)))

      case PlatformConnectionUpdated(platform, channelId, accessToken, refreshToken, expiresAt) =>
        state.connections.get(platform) match {
          case Some(conns) =>
            val updated = conns.map {
              case c if c.channelId == channelId =>
                c.copy(accessToken = accessToken, refreshToken = refreshToken, expiresAt = expiresAt)
            }
            state.copy(connections = state.connections + (platform -> updated))
          case None =>
            state
        }

      case PlatformConnectionRevoked(platform, channelId) =>
        state.connections.get(platform) match {
          case Some(conns) =>
            val updated = conns.filterNot(_.channelId == channelId)
            if (updated.isEmpty) {
              state.copy(connections = state.connections - platform)
            } else {
              state.copy(connections = state.connections + (platform -> updated))
            }
          case None =>
            state
        }
    }
  }

  // ----------------------------------------------------------------
  // Factory
  // ----------------------------------------------------------------

  def apply(userId: String)(using
      classicSystem: ClassicActorSystemProvider
  ): Behavior[Command] = {
    Behaviors.supervise[Command] {
      Behaviors.setup { ctx =>
        EventSourcedBehavior
          .withEnforcedReplies[Command, UserTokenEvent, State](
            persistenceId = PersistenceId(actorName, userId),
            emptyState = State(),
            commandHandler = commandHandler,
            eventHandler = eventHandler
          )
          .receiveSignal { case (state, RecoveryCompleted) =>
            ctx.log.info(
              "UserTokenActor for user {} recovered: {} auth tokens, {} platform connections",
              userId,
              state.twitchTokens.size,
              state.connections.values.foldLeft(0)(_ + _.size)
            )
          }
      }
    }.onFailure[Throwable](SupervisorStrategy.restart)
  }
}
