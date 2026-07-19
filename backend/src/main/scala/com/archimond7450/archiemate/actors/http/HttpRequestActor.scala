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
  private final case class GotHttpRawResponse[T](
      decode: String => Try[T],
      replyTo: ActorRef[StatusReply[T]],
      response: HttpClientActor.Response,
      statusCode: Int
  ) extends Command

  private final case class GotHttpErrorResponse[T](
      decode: String => Try[T],
      replyTo: ActorRef[StatusReply[T]],
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
    }

  private def handleRequest[T](
      request: Request[T],
      pending: Seq[Request[Any]] = Seq.empty
  ): Behavior[Command] = {
    val replyTo = request.replyTo
    val decode = request.decode

    // Create a message adapter that converts HttpClientActor's raw response
    // into an internal command sent to self.
    val probeRef = ctx.messageAdapter[StatusReply[HttpClientActor.Response]] {
      case StatusReply.Success(response: HttpClientActor.Response) =>
        GotHttpRawResponse(decode, replyTo, response, response.response.status.intValue())
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

    // Switch to active state to handle the HTTP response
    active(decode, replyTo, pending)
  }

  /** Active state — waiting for the HTTP response from HttpClientActor.
    * Stores pending requests to handle concurrent calls.
    */
  private def active[T](
      decode: String => Try[T],
      replyTo: ActorRef[StatusReply[T]],
      pending: Seq[Request[Any]]
  ): Behavior[Command] =
    Behaviors.receive {
      case (_, GotHttpRawResponse(_, _, response, statusCode)) =>
        decodeAndForward(decode, replyTo, response, statusCode)
        processPending(pending)
      case (_, GotHttpErrorResponse(_, replyTo, cause)) =>
        replyTo ! error(cause)
        processPending(pending)
      case (_, req: Request[_]) =>
        active(decode, replyTo, pending :+ req.asInstanceOf[Request[Any]])
      case (_, _) =>
        Behaviors.same
    }

  private def processPending(
      pending: Seq[Request[Any]]
  ): Behavior[Command] = {
    if (pending.isEmpty) waiting
    else handleRequest(pending.head, pending.tail)
  }

  private def decodeAndForward[T](
      decode: String => Try[T],
      replyTo: ActorRef[StatusReply[T]],
      response: HttpClientActor.Response,
      statusCode: Int
  ): Unit = {
    decode(response.entityString) match {
      case scala.util.Success(value) => replyTo ! success(value)
      case scala.util.Failure(ex) =>
        // Include status code in error message for better debugging
        replyTo ! error(new RuntimeException(s"HTTP $statusCode: ${ex.getMessage}"))
    }
  }

}
