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

import java.util.UUID

// ----------------------------------------------------------------
// Events
// ----------------------------------------------------------------

sealed trait UserConfigEvent
object UserConfigEvent {
  given Decoder[UserConfigEvent] = ConfiguredDecoder.derived(using CirceConfiguration.pekkoConfiguration)
  given Encoder[UserConfigEvent] = ConfiguredEncoder.derived(using CirceConfiguration.pekkoConfiguration)
}

final case class ConfigEntrySet(
    key: String,
    value: String
) extends UserConfigEvent
private object ConfigEntrySet {
  given Decoder[ConfigEntrySet] = ConfiguredDecoder.derived(using CirceConfiguration.pekkoConfiguration)
  given Encoder[ConfigEntrySet] = ConfiguredEncoder.derived(using CirceConfiguration.pekkoConfiguration)
}

final case class ConfigEntryDeleted(key: String) extends UserConfigEvent
private object ConfigEntryDeleted {
  given Decoder[ConfigEntryDeleted] = ConfiguredDecoder.derived(using CirceConfiguration.pekkoConfiguration)
  given Encoder[ConfigEntryDeleted] = ConfiguredEncoder.derived(using CirceConfiguration.pekkoConfiguration)
}

// ----------------------------------------------------------------
// Serializer (top-level for Java reflection)
// ----------------------------------------------------------------

class UserConfigActorEventSerializer(system: ExtendedActorSystem, configPath: String)
    extends GenericSerializer[UserConfigEvent](
      "user-config-actor",
      SerializerIDs.userConfigActorId
    ) {

  override val toEvent: PartialFunction[AnyRef, UserConfigEvent] = {
    case event: UserConfigEvent => event
  }
}

// ----------------------------------------------------------------
// UserConfigActor
// ----------------------------------------------------------------

object UserConfigActor {

  private val actorName = "user-config-actor"

  // ----------------------------------------------------------------
  // Commands
  // ----------------------------------------------------------------

  sealed trait Command

  final case class SetConfig(
      key: String,
      value: String,
      replyTo: ActorRef[ConfigResponse]
  ) extends Command

  final case class GetConfig(
      key: String,
      replyTo: ActorRef[ConfigResponse]
  ) extends Command

  final case class DeleteConfig(
      key: String,
      replyTo: ActorRef[ConfigResponse]
  ) extends Command

  final case class ListConfigs(replyTo: ActorRef[ConfigResponse]) extends Command

  // ----------------------------------------------------------------
  // Per-command response traits
  // ----------------------------------------------------------------

  sealed trait ConfigResponse

  final case class ConfigSet(key: String, value: String) extends ConfigResponse
  final case class ConfigFound(key: String, value: String) extends ConfigResponse
  final case class ConfigDeletedResponse(key: String) extends ConfigResponse
  final case class ConfigNotFound(key: String) extends ConfigResponse
  final case class ConfigList(configs: Map[String, String]) extends ConfigResponse
  final case class Error(message: String) extends ConfigResponse

  // ----------------------------------------------------------------
  // Shared data classes
  // ----------------------------------------------------------------

  final case class ConfigEntry(
      id: String,
      userId: String,
      key: String,
      value: String
  )

  // ----------------------------------------------------------------
  // State
  // ----------------------------------------------------------------

  private[user] case class State(
      configs: Map[String, ConfigEntry] = Map.empty // key -> entry
  )

  // ----------------------------------------------------------------
  // Command handler
  // ----------------------------------------------------------------

  private val commandHandler: (State, Command) => ReplyEffect[UserConfigEvent, State] = {
    (state, command) =>
      command match {

        case set: SetConfig =>
          state.configs.get(set.key) match {
            case Some(_) =>
              // Update existing config
              Effect
                .persist(ConfigEntrySet(set.key, set.value))
                .thenReply(set.replyTo) { _ =>
                  ConfigSet(set.key, set.value)
                }
            case None =>
              Effect
                .persist(ConfigEntrySet(set.key, set.value))
                .thenReply(set.replyTo) { _ =>
                  ConfigSet(set.key, set.value)
                }
          }

        case get: GetConfig =>
          state.configs.get(get.key) match {
            case Some(entry) =>
              Effect.none.thenReply(get.replyTo) { _ =>
                ConfigFound(entry.key, entry.value)
              }
            case None =>
              Effect.none.thenReply(get.replyTo) { _ =>
                ConfigNotFound(get.key)
              }
          }

        case delete: DeleteConfig =>
          state.configs.get(delete.key) match {
            case Some(_) =>
              Effect
                .persist(ConfigEntryDeleted(delete.key))
                .thenReply(delete.replyTo) { _ =>
                  ConfigDeletedResponse(delete.key)
                }
            case None =>
              Effect.none.thenReply(delete.replyTo) { _ =>
                ConfigNotFound(delete.key)
              }
          }

        case list: ListConfigs =>
          Effect.none.thenReply(list.replyTo) { s =>
            ConfigList(s.configs.values.map(e => e.key -> e.value).toMap)
          }
      }
  }

  // ----------------------------------------------------------------
  // Event handler
  // ----------------------------------------------------------------

  private val eventHandler: (State, UserConfigEvent) => State = { (state, event) =>
    event match {
      case ConfigEntrySet(key, value) =>
        val entry = ConfigEntry(UUID.randomUUID().toString, "", key, value)
        state.copy(configs = state.configs + (key -> entry))

      case ConfigEntryDeleted(key) =>
        state.copy(configs = state.configs - key)
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
          .withEnforcedReplies[Command, UserConfigEvent, State](
            persistenceId = PersistenceId(actorName, userId),
            emptyState = State(),
            commandHandler = commandHandler,
            eventHandler = eventHandler
          )
          .receiveSignal { case (state, RecoveryCompleted) =>
            ctx.log.info(
              "UserConfigActor for user {} recovered: {} config entries",
              userId,
              state.configs.size
            )
          }
      }
    }.onFailure[Throwable](SupervisorStrategy.restart)
  }
}
