package com.archimond7450.archiemate

import com.archimond7450.archiemate.actors.http.HttpRequestActor
import com.archimond7450.archiemate.http.HttpClientActor
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

/** Central mediator that routes commands to application actors.
  *
  * ActorRefs are passed in at construction time — the mediator does not
  * spawn actors itself, because those actors require config/dependencies
  * that are only available at the app level.
  *
  * Only actors that need to be routed to by other actors are registered
  * here. Leaf actors (ReadinessTracker, JwtActor) are passed directly
  * to their callers.
  *
  * @note
  *   This actor must be supervised by its parent with a resume strategy on
  *   [[Throwable]] to preserve state across failures:
  *   {{{supervise(ArchieMateMediator(...)).onFailure[Throwable](SupervisorStrategy.resume)}}}
  */
object ArchieMateMediator {

  private val actorName = "archie-mate-mediator"

  sealed trait Command

  /** Send a command to the HttpClient actor. */
  final case class SendHttpClientRequest(cmd: HttpClientActor.Command)
      extends Command

  /** Send a prepared HTTP request via HttpRequestActor (typed decode).
    */
  final case class SendHttpRequest[T](cmd: HttpRequestActor.Request[T])
      extends Command

  private case class State(
      httpClient: ActorRef[HttpClientActor.Command],
      httpRequestActor: ActorRef[HttpRequestActor.Command]
  )

  /** Returns an unsupervised behavior. Use when the parent applies its own
    * supervision.
    */
  def apply(
      httpClient: ActorRef[HttpClientActor.Command],
      httpRequestActor: ActorRef[HttpRequestActor.Command]
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      new ArchieMateMediator(ctx, httpClient, httpRequestActor).initial()
    }

  /** Returns a supervised behavior. Use when spawning as a root actor. */
  def supervised(
      httpClient: ActorRef[HttpClientActor.Command],
      httpRequestActor: ActorRef[HttpRequestActor.Command]
  ): Behavior[Command] =
    Behaviors.supervise(apply(httpClient, httpRequestActor))
      .onFailure[Throwable](SupervisorStrategy.resume)

}

class ArchieMateMediator private (
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[ArchieMateMediator.Command],
    httpClient: ActorRef[HttpClientActor.Command],
    httpRequestActor: ActorRef[HttpRequestActor.Command]
) {

  import ArchieMateMediator.*

  def initial(): Behavior[Command] =
    mainBehavior(State(httpClient, httpRequestActor))

  private def mainBehavior(state: State): Behavior[Command] =
    Behaviors.receiveMessage {
      case SendHttpClientRequest(cmd) =>
        state.httpClient ! cmd
        Behaviors.same
      case SendHttpRequest(cmd) =>
        state.httpRequestActor ! cmd
        Behaviors.same
    }

}
