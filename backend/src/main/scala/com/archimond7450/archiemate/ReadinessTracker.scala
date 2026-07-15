package com.archimond7450.archiemate

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}

/** Tracks readiness of all application components.
  *
  * Other actors send [[Command.Register]] when spawned and [[Command.Ready]]
  * when ready. External code sends [[Command.CheckReadiness]] and receives
  * [[ReadinessResponse]].
  *
  * @note
  *   This actor must be supervised by its parent with a resume strategy on
  *   [[Throwable]] to preserve state across failures:
  *   {{{supervise(ReadinessTracker()).onFailure[Throwable](SupervisorStrategy.resume)}}}
  */
object ReadinessTracker {

  private val actorName = "readiness-tracker"

  /** Marker trait for actors that can signal readiness.
    */
  sealed trait ReadySignal

  /** Returns an unsupervised behavior for this actor. Use when the parent
    * already applies its own supervision.
    */
  def apply(): Behavior[Command] =
    Behaviors.setup[Command] { _ =>
      new ReadinessTracker().initial()
    }

  /** Returns a supervised version of this actor. Use when spawning from a
    * parent that does not apply its own supervision.
    */
  def supervised(): Behavior[Command] =
    Behaviors.supervise(apply()).onFailure[Throwable](SupervisorStrategy.resume)

  // ----------------------------------------------------------------
  // Commands to the readiness tracker (sealed, named Command)
  // ----------------------------------------------------------------

  sealed trait Command
  final case class Register(actorRef: ActorRef[ReadySignal]) extends Command
  final case class CheckReadiness(replyTo: ActorRef[ReadinessResponse])
      extends Command
  final case class Ready(actorRef: ActorRef[ReadySignal]) extends Command

  // ----------------------------------------------------------------
  // Responses to CheckReadiness (sealed)
  // ----------------------------------------------------------------

  sealed trait ReadinessResponse
  case object ReadyResponse extends ReadinessResponse
  case object NotReadyResponse extends ReadinessResponse

  // ----------------------------------------------------------------
  // Internal state
  // ----------------------------------------------------------------

  private case class TrackerState(
      registry: Set[ActorRef[ReadySignal]] = Set.empty,
      readyCount: Int = 0
  )

}

class ReadinessTracker private () {

  import ReadinessTracker._

  def initial(): Behavior[Command] = {
    val state = TrackerState()
    mainBehavior(state)
  }

  private def mainBehavior(state: TrackerState): Behavior[Command] =
    Behaviors.receiveMessage {
      case register: Register =>
        mainBehavior(state.copy(registry = state.registry + register.actorRef))

      case check: CheckReadiness =>
        val isReady = state.registry.nonEmpty && state.readyCount == state.registry.size
        check.replyTo ! (if (isReady) ReadyResponse else NotReadyResponse)
        Behaviors.same

      case ready: Ready =>
        if (state.registry.contains(ready.actorRef)) {
          mainBehavior(state.copy(readyCount = state.readyCount + 1))
        } else {
          Behaviors.same
        }
    }
}
