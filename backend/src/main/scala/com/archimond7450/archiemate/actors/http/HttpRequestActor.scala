package com.archimond7450.archiemate.actors.http

import com.archimond7450.archiemate.http.HttpClientActor
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.pattern.StatusReply

import scala.util.Try

/** Sends prepared HTTP requests to the [[HttpClientActor]] and responds with
  * a decoded typed result.
  *
  * The caller provides a decoder function that transforms the raw response
  * body string into a typed result. This keeps the actor generic — it never
  * knows or cares about the wire format.
  *
  * @note
  *   This actor must be supervised by its parent with a resume strategy on
  *   [[Throwable]] to preserve state across failures:
  *   {{{supervise(HttpRequestActor(httpClient)).onFailure[Throwable](SupervisorStrategy.resume)}}}
  */
object HttpRequestActor {

  // ----------------------------------------------------------------
  // Commands
  // ----------------------------------------------------------------

  sealed trait Command
  final case class Request[T](
      method: org.apache.pekko.http.scaladsl.model.HttpMethod,
      uri: org.apache.pekko.http.scaladsl.model.Uri,
      headers: Seq[org.apache.pekko.http.scaladsl.model.HttpHeader] = Seq.empty,
      entity: org.apache.pekko.http.scaladsl.model.RequestEntity = org.apache.pekko.http.scaladsl.model.HttpEntity.Empty,
      decode: String => Try[T],
      replyTo: ActorRef[StatusReply[T]]
  ) extends Command

  // Internal command: wraps the HTTP response for processing
  private final case class GotHttpRawResponse(
      decode: String => Try[Any],
      replyTo: ActorRef[StatusReply[Any]],
      response: HttpClientActor.Response
  ) extends Command

  private final case class GotHttpErrorResponse(
      decode: String => Try[Any],
      replyTo: ActorRef[StatusReply[Any]],
      cause: Throwable
  ) extends Command

  // ----------------------------------------------------------------
  // Responses
  // ----------------------------------------------------------------

  type Response[T] = Try[T]

  // ----------------------------------------------------------------
  // Initialization
  // ----------------------------------------------------------------

  def apply(
      httpClient: ActorRef[HttpClientActor.Command]
  ): Behavior[Command] =
    Behaviors.supervise[Command] {
      Behaviors.setup { ctx =>
        ctx.log.info("HttpRequestActor initialized")
        new HttpRequestActor(ctx, httpClient).initial()
      }
    }.onFailure[Throwable](SupervisorStrategy.resume)

}

class HttpRequestActor private (
    ctx: ActorContext[HttpRequestActor.Command],
    httpClient: ActorRef[HttpClientActor.Command]
) {

  import HttpRequestActor.*
  import org.apache.pekko.pattern.StatusReply.{success, error}

  def initial(): Behavior[Command] =
    Behaviors.logMessages(waiting)

  /** Idle state — waiting for a new request. */
  private def waiting: Behavior[Command] =
    Behaviors.receiveMessage {
      case req: Request[_] =>
        handleRequest(req.asInstanceOf[Request[Any]])
        Behaviors.same
    }

  private def handleRequest(request: Request[Any]): Unit = {
    val replyTo = request.replyTo.asInstanceOf[ActorRef[StatusReply[Any]]]
    val decode = request.decode

    // Create a message adapter that converts HttpClientActor's raw response
    // into an internal command sent to self.
    val probeRef = ctx.messageAdapter[StatusReply[HttpClientActor.Response]] {
      case StatusReply.Success(response: HttpClientActor.Response) =>
        GotHttpRawResponse(decode, replyTo, response)
      case StatusReply.Error(ex) =>
        GotHttpErrorResponse(decode, replyTo, ex)
    }

    // Send the prepared request directly to HttpClientActor.
    // The HttpClientActor sends its response directly to probeRef
    // (not through the mediator).
    httpClient ! HttpClientActor.SendRequest(
      method = request.method,
      uri = request.uri,
      headers = request.headers,
      entity = request.entity,
      replyTo = probeRef
    )
  }

  /** Active state — waiting for the HTTP response from HttpClientActor. */
  private def active(
      decode: String => Try[Any],
      replyTo: ActorRef[StatusReply[Any]]
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case GotHttpRawResponse(_, _, response) =>
        decodeAndForward(decode, replyTo, response)
        waiting
      case GotHttpErrorResponse(_, replyTo, cause) =>
        replyTo ! error(cause)
        waiting
      case _ =>
        // Should not happen — any other command is queued until we're back in waiting
        Behaviors.same
    }

  private def decodeAndForward(
      decode: String => Try[Any],
      replyTo: ActorRef[StatusReply[Any]],
      response: HttpClientActor.Response
  ): Unit = {
    decode(response.entityString) match {
      case scala.util.Success(value) => replyTo ! success(value.asInstanceOf[Any])
      case scala.util.Failure(ex)    => replyTo ! error(ex)
    }
  }

}
