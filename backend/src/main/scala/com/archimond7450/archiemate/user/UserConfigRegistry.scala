package com.archimond7450.archiemate.user

import org.apache.pekko.actor.typed.scaladsl.AskPattern.{*, given}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.util.Timeout

import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext

/** Manages per-user [[UserConfigActor]] instances.
  *
  * Provides a single entry point for managing user configuration across users.
  * Spawns a [[UserConfigActor]] per userId on demand and caches the reference.
  */
object UserConfigRegistry {

  private val actorName = "user-config-registry"

  // ----------------------------------------------------------------
  // Commands
  // ----------------------------------------------------------------

  sealed trait Command

  final case class SetConfig(
      userId: String,
      key: String,
      value: String,
      replyTo: ActorRef[ConfigResponse]
  ) extends Command

  final case class GetConfig(
      userId: String,
      key: String,
      replyTo: ActorRef[ConfigResponse]
  ) extends Command

  final case class DeleteConfig(
      userId: String,
      key: String,
      replyTo: ActorRef[ConfigResponse]
  ) extends Command

  final case class ListConfigs(
      userId: String,
      replyTo: ActorRef[ConfigResponse]
  ) extends Command

  // ----------------------------------------------------------------
  // Responses
  // ----------------------------------------------------------------

  sealed trait ConfigResponse

  final case class ConfigSet(key: String, value: String) extends ConfigResponse
  final case class ConfigFound(key: String, value: String) extends ConfigResponse
  final case class ConfigDeleted(key: String) extends ConfigResponse
  final case class ConfigNotFound(key: String) extends ConfigResponse
  final case class ConfigList(configs: Map[String, String]) extends ConfigResponse
  final case class Error(message: String) extends ConfigResponse

  // ----------------------------------------------------------------
  // Internal state
  // ----------------------------------------------------------------

  private case class State(userActors: Map[String, ActorRef[UserConfigActor.Command]])

  // ----------------------------------------------------------------
  // Factory
  // ----------------------------------------------------------------

  def apply()(using
      classicSystem: ClassicActorSystemProvider
  ): Behavior[Command] =
    Behaviors.supervise[Command] {
      Behaviors.setup { ctx =>
        ctx.log.info("UserConfigRegistry initialized")
        given ctxg: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command] = ctx
        given ec: ExecutionContext = scala.concurrent.ExecutionContext.global
        given scheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
        given timeout: Timeout = Timeout(5.seconds)
        mainBehavior(State(Map.empty))
      }
    }.onFailure[Throwable](SupervisorStrategy.resume)

  // ----------------------------------------------------------------
  // Behavior
  // ----------------------------------------------------------------

  private def mainBehavior(state: State)(using
      ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
      classicSystem: ClassicActorSystemProvider,
      scheduler: org.apache.pekko.actor.typed.Scheduler,
      timeout: Timeout,
      ec: ExecutionContext
  ): Behavior[Command] =
    Behaviors.withMdc(Map("actor" -> actorName))(
      Behaviors.receiveMessage {
        case set: SetConfig =>
          getOrCreateUserActor(set.userId, state) match {
            case Left(error) =>
              set.replyTo ! Error(error)
              Behaviors.same
            case Right(actorRef) =>
              forwardCommand(set, actorRef)
              Behaviors.same
          }

        case get: GetConfig =>
          getOrCreateUserActor(get.userId, state) match {
            case Left(error) =>
              get.replyTo ! Error(error)
              Behaviors.same
            case Right(actorRef) =>
              forwardCommand(get, actorRef)
              Behaviors.same
          }

        case delete: DeleteConfig =>
          getOrCreateUserActor(delete.userId, state) match {
            case Left(error) =>
              delete.replyTo ! Error(error)
              Behaviors.same
            case Right(actorRef) =>
              forwardCommand(delete, actorRef)
              Behaviors.same
          }

        case list: ListConfigs =>
          getOrCreateUserActor(list.userId, state) match {
            case Left(error) =>
              list.replyTo ! Error(error)
              Behaviors.same
            case Right(actorRef) =>
              forwardCommand(list, actorRef)
              Behaviors.same
          }
      }
    )

  // ----------------------------------------------------------------
  // Forwarding helpers
  // ----------------------------------------------------------------

  /** Forwards a command to a per-user [[UserConfigActor]] and handles the response.
   */
  private def forwardCommand[Cmd](
      cmd: Cmd,
      actorRef: ActorRef[UserConfigActor.Command]
  )(using
      scheduler: org.apache.pekko.actor.typed.Scheduler,
      timeout: Timeout,
      ec: ExecutionContext
  ): Unit = {
    val future = cmd match {
      case set: SetConfig =>
        actorRef ? { (ref: ActorRef[UserConfigActor.ConfigResponse]) =>
          UserConfigActor.SetConfig(set.key, set.value, ref)
        }
      case get: GetConfig =>
        actorRef ? { (ref: ActorRef[UserConfigActor.ConfigResponse]) =>
          UserConfigActor.GetConfig(get.key, ref)
        }
      case delete: DeleteConfig =>
        actorRef ? { (ref: ActorRef[UserConfigActor.ConfigResponse]) =>
          UserConfigActor.DeleteConfig(delete.key, ref)
        }
      case list: ListConfigs =>
        actorRef ? { (ref: ActorRef[UserConfigActor.ConfigResponse]) =>
          UserConfigActor.ListConfigs(ref)
        }
    }
    future.onComplete {
      case scala.util.Success(resp) =>
        handleSuccess(cmd, resp.asInstanceOf[UserConfigActor.ConfigResponse])
      case scala.util.Failure(ex) =>
        handleFailure(cmd, ex)
    }(ec)
  }

  private def handleSuccess[Cmd](
      cmd: Cmd,
      resp: UserConfigActor.ConfigResponse
  ): Unit = cmd match {
    case set: SetConfig =>
      resp match {
        case UserConfigActor.ConfigSet(key, value) =>
          set.replyTo ! ConfigSet(key, value)
        case UserConfigActor.Error(msg) =>
          set.replyTo ! Error(msg)
        case other =>
          set.replyTo ! Error(s"Unexpected response: $other")
      }
    case get: GetConfig =>
      resp match {
        case UserConfigActor.ConfigFound(key, value) =>
          get.replyTo ! ConfigFound(key, value)
        case UserConfigActor.ConfigNotFound(key) =>
          get.replyTo ! ConfigNotFound(key)
        case UserConfigActor.Error(msg) =>
          get.replyTo ! Error(msg)
        case other =>
          get.replyTo ! Error(s"Unexpected response: $other")
      }
    case delete: DeleteConfig =>
      resp match {
        case UserConfigActor.ConfigDeletedResponse(key) =>
          delete.replyTo ! ConfigDeleted(key)
        case UserConfigActor.ConfigNotFound(key) =>
          delete.replyTo ! ConfigNotFound(key)
        case UserConfigActor.Error(msg) =>
          delete.replyTo ! Error(msg)
        case other =>
          delete.replyTo ! Error(s"Unexpected response: $other")
      }
    case list: ListConfigs =>
      resp match {
        case UserConfigActor.ConfigList(configs) =>
          list.replyTo ! ConfigList(configs)
        case UserConfigActor.Error(msg) =>
          list.replyTo ! Error(msg)
        case other =>
          list.replyTo ! Error(s"Unexpected response: $other")
      }
  }

  private def handleFailure[Cmd](
      cmd: Cmd,
      ex: Throwable
  ): Unit = cmd match {
    case set: SetConfig =>
      set.replyTo ! Error(s"Command failed: ${ex.getMessage}")
    case get: GetConfig =>
      get.replyTo ! Error(s"Command failed: ${ex.getMessage}")
    case delete: DeleteConfig =>
      delete.replyTo ! Error(s"Command failed: ${ex.getMessage}")
    case list: ListConfigs =>
      list.replyTo ! Error(s"Command failed: ${ex.getMessage}")
  }

  private def getOrCreateUserActor(
      userId: String,
      state: State
  )(using
      ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
      classicSystem: ClassicActorSystemProvider
  ): Either[String, ActorRef[UserConfigActor.Command]] = {
    state.userActors.get(userId) match {
      case Some(ref) =>
        Right(ref)
      case None =>
        try {
          val actorRef = ctx.spawn(
            Behaviors.supervise(UserConfigActor(userId)).onFailure[Throwable](SupervisorStrategy.resume),
            s"user-config-$userId"
          )
          ctx.log.info("Spawned UserConfigActor for user {}", userId)
          Right(actorRef)
        } catch {
          case ex: Exception =>
            ctx.log.error("Failed to spawn UserConfigActor for user {}: {}", userId, ex.getMessage)
            Left(s"Failed to spawn UserConfigActor: ${ex.getMessage}")
        }
    }
  }

}
