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

// YouTube primary connection events
final case class YoutubePrimaryConnectionRegistered(
    channelId: String,
    accessToken: String,
    refreshToken: String,
    expiresAt: Instant
) extends UserTokenEvent
private object YoutubePrimaryConnectionRegistered {
  given Decoder[YoutubePrimaryConnectionRegistered] = ConfiguredDecoder.derived(using CirceConfiguration.pekkoConfiguration)
  given Encoder[YoutubePrimaryConnectionRegistered] = ConfiguredEncoder.derived(using CirceConfiguration.pekkoConfiguration)
}

final case class YoutubePrimaryConnectionUpdated(
    channelId: String,
    accessToken: String,
    refreshToken: String,
    expiresAt: Instant
) extends UserTokenEvent
private object YoutubePrimaryConnectionUpdated {
  given Decoder[YoutubePrimaryConnectionUpdated] = ConfiguredDecoder.derived(using CirceConfiguration.pekkoConfiguration)
  given Encoder[YoutubePrimaryConnectionUpdated] = ConfiguredEncoder.derived(using CirceConfiguration.pekkoConfiguration)
}

final case class YoutubePrimaryConnectionRevoked(channelId: String) extends UserTokenEvent
private object YoutubePrimaryConnectionRevoked {
  given Decoder[YoutubePrimaryConnectionRevoked] = ConfiguredDecoder.derived(using CirceConfiguration.pekkoConfiguration)
  given Encoder[YoutubePrimaryConnectionRevoked] = ConfiguredEncoder.derived(using CirceConfiguration.pekkoConfiguration)
}

// YouTube secondary connection events
final case class YoutubeSecondaryConnectionRegistered(
    channelId: String,
    accessToken: String,
    refreshToken: String,
    expiresAt: Instant
) extends UserTokenEvent
private object YoutubeSecondaryConnectionRegistered {
  given Decoder[YoutubeSecondaryConnectionRegistered] = ConfiguredDecoder.derived(using CirceConfiguration.pekkoConfiguration)
  given Encoder[YoutubeSecondaryConnectionRegistered] = ConfiguredEncoder.derived(using CirceConfiguration.pekkoConfiguration)
}

final case class YoutubeSecondaryConnectionUpdated(
    channelId: String,
    accessToken: String,
    refreshToken: String,
    expiresAt: Instant
) extends UserTokenEvent
private object YoutubeSecondaryConnectionUpdated {
  given Decoder[YoutubeSecondaryConnectionUpdated] = ConfiguredDecoder.derived(using CirceConfiguration.pekkoConfiguration)
  given Encoder[YoutubeSecondaryConnectionUpdated] = ConfiguredEncoder.derived(using CirceConfiguration.pekkoConfiguration)
}

final case class YoutubeSecondaryConnectionRevoked(channelId: String) extends UserTokenEvent
private object YoutubeSecondaryConnectionRevoked {
  given Decoder[YoutubeSecondaryConnectionRevoked] = ConfiguredDecoder.derived(using CirceConfiguration.pekkoConfiguration)
  given Encoder[YoutubeSecondaryConnectionRevoked] = ConfiguredEncoder.derived(using CirceConfiguration.pekkoConfiguration)
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

  // YouTube primary connection commands
  final case class RegisterYoutubePrimaryConnection(
      channelId: String,
      accessToken: String,
      refreshToken: String,
      expiresAt: Instant,
      replyTo: ActorRef[YoutubePrimaryResponse]
  ) extends Command
  final case class UpdateYoutubePrimaryConnection(
      channelId: String,
      accessToken: String,
      refreshToken: String,
      expiresAt: Instant,
      replyTo: ActorRef[YoutubePrimaryResponse]
  ) extends Command
  final case class RevokeYoutubePrimaryConnection(
      replyTo: ActorRef[YoutubePrimaryResponse]
  ) extends Command
  final case class GetYoutubePrimaryConnection(replyTo: ActorRef[YoutubePrimaryResponse]) extends Command

  // YouTube secondary connection commands
  final case class RegisterYoutubeSecondaryConnection(
      channelId: String,
      accessToken: String,
      refreshToken: String,
      expiresAt: Instant,
      replyTo: ActorRef[YoutubeSecondaryResponse]
  ) extends Command
  final case class UpdateYoutubeSecondaryConnection(
      channelId: String,
      accessToken: String,
      refreshToken: String,
      expiresAt: Instant,
      replyTo: ActorRef[YoutubeSecondaryResponse]
  ) extends Command
  final case class RevokeYoutubeSecondaryConnection(
      channelId: String,
      replyTo: ActorRef[YoutubeSecondaryResponse]
  ) extends Command
  final case class GetYoutubeSecondaryConnections(replyTo: ActorRef[YoutubeSecondaryResponse]) extends Command

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

  // YouTube primary responses
  sealed trait YoutubePrimaryResponse
  final case class YoutubePrimaryRegistered(channelId: String) extends YoutubePrimaryResponse
  final case class YoutubePrimaryUpdated(channelId: String) extends YoutubePrimaryResponse
  final case class YoutubePrimaryRevoked(channelId: String) extends YoutubePrimaryResponse
  final case class YoutubePrimaryFound(connection: PlatformConnection) extends YoutubePrimaryResponse
  case object YoutubePrimaryNotFound extends YoutubePrimaryResponse

  // YouTube secondary responses
  sealed trait YoutubeSecondaryResponse
  final case class YoutubeSecondaryRegistered(channelId: String) extends YoutubeSecondaryResponse
  final case class YoutubeSecondaryUpdated(channelId: String) extends YoutubeSecondaryResponse
  final case class YoutubeSecondaryRevoked(channelId: String) extends YoutubeSecondaryResponse
  final case class YoutubeSecondaryConnectionsFound(connections: List[PlatformConnection]) extends YoutubeSecondaryResponse

  final case class Error(message: String) extends AuthResponse with ConnectionResponse with YoutubePrimaryResponse with YoutubeSecondaryResponse

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
      connections: Map[String, List[PlatformConnection]] = Map.empty,
      youtubePrimary: Option[PlatformConnection] = None
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

        // --- YouTube primary connection commands ---

        case registerPrimary: RegisterYoutubePrimaryConnection =>
          state.youtubePrimary match {
            case Some(_) =>
              Effect.none.thenReply(registerPrimary.replyTo) { _ =>
                Error("A YouTube primary connection already exists")
              }
            case None =>
              val conn = PlatformConnection(
                "youtube",
                registerPrimary.channelId,
                registerPrimary.accessToken,
                registerPrimary.refreshToken,
                registerPrimary.expiresAt
              )
              Effect
                .persist(YoutubePrimaryConnectionRegistered(
                  registerPrimary.channelId,
                  registerPrimary.accessToken,
                  registerPrimary.refreshToken,
                  registerPrimary.expiresAt
                ))
                .thenReply(registerPrimary.replyTo) { _ =>
                  YoutubePrimaryRegistered(registerPrimary.channelId)
                }
          }

        case updatePrimary: UpdateYoutubePrimaryConnection =>
          state.youtubePrimary match {
            case Some(_) =>
              Effect
                .persist(YoutubePrimaryConnectionUpdated(
                  updatePrimary.channelId,
                  updatePrimary.accessToken,
                  updatePrimary.refreshToken,
                  updatePrimary.expiresAt
                ))
                .thenReply(updatePrimary.replyTo) { _ =>
                  YoutubePrimaryUpdated(updatePrimary.channelId)
                }
            case None =>
              Effect.none.thenReply(updatePrimary.replyTo) { _ =>
                Error("No YouTube primary connection to update")
              }
          }

        case revokePrimary: RevokeYoutubePrimaryConnection =>
          state.youtubePrimary match {
            case Some(conn) =>
              Effect
                .persist(YoutubePrimaryConnectionRevoked(conn.channelId))
                .thenReply(revokePrimary.replyTo) { _ =>
                  YoutubePrimaryRevoked(conn.channelId)
                }
            case None =>
              Effect.none.thenReply(revokePrimary.replyTo) { _ =>
                Error("No YouTube primary connection to revoke")
              }
          }

        case getPrimary: GetYoutubePrimaryConnection =>
          state.youtubePrimary match {
            case Some(conn) =>
              Effect.none.thenReply(getPrimary.replyTo) { _ =>
                YoutubePrimaryFound(conn)
              }
            case None =>
              Effect.none.thenReply(getPrimary.replyTo) { _ =>
                YoutubePrimaryNotFound
              }
          }

        // --- YouTube secondary connection commands ---

        case registerSecondary: RegisterYoutubeSecondaryConnection =>
          val existing = state.connections.getOrElse("youtube", Nil)
          if (existing.exists(_.channelId == registerSecondary.channelId)) {
            Effect.none.thenReply(registerSecondary.replyTo) { _ =>
              Error(s"YouTube connection for channel ${registerSecondary.channelId} already registered")
            }
          } else {
            Effect
              .persist(YoutubeSecondaryConnectionRegistered(
                registerSecondary.channelId,
                registerSecondary.accessToken,
                registerSecondary.refreshToken,
                registerSecondary.expiresAt
              ))
              .thenReply(registerSecondary.replyTo) { _ =>
                YoutubeSecondaryRegistered(registerSecondary.channelId)
              }
          }

        case updateSecondary: UpdateYoutubeSecondaryConnection =>
          state.connections.get("youtube") match {
            case Some(conns) if conns.exists(_.channelId == updateSecondary.channelId) =>
              Effect
                .persist(YoutubeSecondaryConnectionUpdated(
                  updateSecondary.channelId,
                  updateSecondary.accessToken,
                  updateSecondary.refreshToken,
                  updateSecondary.expiresAt
                ))
                .thenReply(updateSecondary.replyTo) { _ =>
                  YoutubeSecondaryUpdated(updateSecondary.channelId)
                }
            case _ =>
              Effect.none.thenReply(updateSecondary.replyTo) { _ =>
                Error(s"YouTube connection for channel ${updateSecondary.channelId} not found")
              }
          }

        case revokeSecondary: RevokeYoutubeSecondaryConnection =>
          state.connections.get("youtube") match {
            case Some(conns) if conns.exists(_.channelId == revokeSecondary.channelId) =>
              Effect
                .persist(YoutubeSecondaryConnectionRevoked(revokeSecondary.channelId))
                .thenReply(revokeSecondary.replyTo) { _ =>
                  YoutubeSecondaryRevoked(revokeSecondary.channelId)
                }
            case _ =>
              Effect.none.thenReply(revokeSecondary.replyTo) { _ =>
                Error(s"YouTube connection for channel ${revokeSecondary.channelId} not found")
              }
          }

        case getSecondary: GetYoutubeSecondaryConnections =>
          Effect.none.thenReply(getSecondary.replyTo) { s =>
            YoutubeSecondaryConnectionsFound(s.connections.getOrElse("youtube", Nil))
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

      // --- YouTube primary event handlers ---

      case YoutubePrimaryConnectionRegistered(channelId, accessToken, refreshToken, expiresAt) =>
        val conn = PlatformConnection("youtube", channelId, accessToken, refreshToken, expiresAt)
        state.copy(youtubePrimary = Some(conn))

      case YoutubePrimaryConnectionUpdated(channelId, accessToken, refreshToken, expiresAt) =>
        state.youtubePrimary match {
          case Some(conn) =>
            state.copy(youtubePrimary = Some(conn.copy(accessToken = accessToken, refreshToken = refreshToken, expiresAt = expiresAt)))
          case None =>
            state
        }

      case YoutubePrimaryConnectionRevoked(_) =>
        state.copy(youtubePrimary = None)

      // --- YouTube secondary event handlers ---

      case YoutubeSecondaryConnectionRegistered(channelId, accessToken, refreshToken, expiresAt) =>
        val conn = PlatformConnection("youtube", channelId, accessToken, refreshToken, expiresAt)
        val existing = state.connections.getOrElse("youtube", Nil)
        state.copy(connections = state.connections + ("youtube" -> (existing :+ conn)))

      case YoutubeSecondaryConnectionUpdated(channelId, accessToken, refreshToken, expiresAt) =>
        state.connections.get("youtube") match {
          case Some(conns) =>
            val updated = conns.map {
              case c if c.channelId == channelId =>
                c.copy(accessToken = accessToken, refreshToken = refreshToken, expiresAt = expiresAt)
            }
            state.copy(connections = state.connections + ("youtube" -> updated))
          case None =>
            state
        }

      case YoutubeSecondaryConnectionRevoked(channelId) =>
        state.connections.get("youtube") match {
          case Some(conns) =>
            val updated = conns.filterNot(_.channelId == channelId)
            if (updated.isEmpty) {
              state.copy(connections = state.connections - "youtube")
            } else {
              state.copy(connections = state.connections + ("youtube" -> updated))
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
