package com.archimond7450.archiemate.http

import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpEntity, HttpHeader, HttpMethod, HttpRequest, HttpResponse, RequestEntity, Uri}
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.pattern.StatusReply
import com.typesafe.config.Config

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/** Sends HTTP requests and responds back with the raw response and body.
  *
  * Uses Pekko HTTP's `singleRequest` API with `Unmarshal` for entity
  * marshaling — no manual stream materializer needed.
  *
  * @note
  *   This actor must be supervised by its parent with a resume strategy on
  *   [[Throwable]] to preserve state across failures:
  *   {{{supervise(HttpClientActor(config)).onFailure[Throwable](SupervisorStrategy.resume)}}}
  */
object HttpClientActor {

  private val actorName = "http-client-actor"

  // ----------------------------------------------------------------
  // Commands
  // ----------------------------------------------------------------

  sealed trait Command
  final case class SendRequest(
      method: HttpMethod,
      uri: Uri,
      headers: Seq[HttpHeader] = Seq.empty,
      entity: RequestEntity = HttpEntity.Empty,
      replyTo: ActorRef[StatusReply[Response]]
  ) extends Command

  // ----------------------------------------------------------------
  // Responses
  // ----------------------------------------------------------------

  final case class Response(response: HttpResponse, entityString: String)

  // ----------------------------------------------------------------
  // Configuration
  // ----------------------------------------------------------------

  private case class ClientConfig(
      maxConnections: Int,
      maxIdleTimeout: java.time.Duration
  )

  private def loadConfig(config: Config): ClientConfig = {
    val httpConf = config.getConfig("archiemate.http-client")
    ClientConfig(
      maxConnections = httpConf.getInt("max-connections"),
      maxIdleTimeout = httpConf.getDuration("max-idle-timeout")
    )
  }

  def apply(classicSystem: ClassicActorSystemProvider, config: Config): Behavior[Command] =
    Behaviors.supervise[Command] {
      Behaviors.setup { ctx =>
        val clientConfig = loadConfig(config)
        ctx.log.info("HttpClientActor initialized with maxConnections={}, maxIdleTimeout={}",
          clientConfig.maxConnections, clientConfig.maxIdleTimeout)
        mainBehavior(ctx, classicSystem)
      }
    }.onFailure[Throwable](SupervisorStrategy.resume)

  private def mainBehavior(
      ctx: ActorContext[Command],
      classicSystem: ClassicActorSystemProvider
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case request: SendRequest =>
        sendRequest(ctx, request, classicSystem)
        Behaviors.same
    }

  private def sendRequest(
      ctx: ActorContext[Command],
      request: SendRequest,
      classicSystem: ClassicActorSystemProvider
  ): Unit = {
    given ec: ExecutionContext = ctx.executionContext
    given ClassicActorSystemProvider = classicSystem
    val httpRequest = HttpRequest(
      method = request.method,
      uri = request.uri,
      headers = request.headers,
      entity = request.entity
    )

    val future: scala.concurrent.Future[Response] = Http()(classicSystem).singleRequest(httpRequest).flatMap { response =>
      Unmarshal(response.entity).to[String].map { body =>
        Response(response, body)
      }
    }(ec)

    future.onComplete {
      case Success(resp) =>
        request.replyTo ! StatusReply.success(resp)
      case Failure(ex) =>
        request.replyTo ! StatusReply.error(ex)
    }(ec)
  }
}
