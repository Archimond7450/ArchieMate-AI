package com.archimond7450.archiemate.http

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.HttpMethods
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.stream.ActorMaterializer
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.*
import org.apache.pekko.util.ByteString
import com.typesafe.config.Config

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.{Failure, Success}
import java.nio.charset.StandardCharsets

/** Sends HTTP requests and responds back with status code, headers, and body.
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
      uri: Uri,
      method: HttpMethod = HttpMethods.GET,
      headers: Seq[HttpHeader] = Seq.empty,
      entity: Option[HttpEntity.Default] = None,
      replyTo: ActorRef[SendResponse]
  ) extends Command

  // ----------------------------------------------------------------
  // Responses
  // ----------------------------------------------------------------

  sealed trait Response

  // Per-command response trait
  sealed trait SendResponse extends Response

  final case class RequestSuccess(statusCode: StatusCode, headers: Seq[HttpHeader], body: String)
      extends SendResponse
  final case class RequestFailure(message: String) extends SendResponse

  // ----------------------------------------------------------------
  // Configuration
  // ----------------------------------------------------------------

  private case class ClientConfig(
      maxConnections: Int,
      maxIdleTimeout: FiniteDuration
  )

  private def loadConfig(config: Config): ClientConfig = {
    val httpConf = config.getConfig("archiemate.http-client")
    ClientConfig(
      maxConnections = httpConf.getInt("max-connections"),
      maxIdleTimeout = httpConf.getDuration("max-idle-timeout").toMillis.millis
    )
  }

  def apply(config: Config)(using
      classicSystem: ClassicActorSystemProvider
  ): Behavior[Command] =
    Behaviors.supervise[Command] {
      Behaviors.setup { ctx =>
        val clientConfig = loadConfig(config)
        ctx.log.info("HttpClientActor initialized with maxConnections={}, maxIdleTimeout={}",
          clientConfig.maxConnections, clientConfig.maxIdleTimeout)
        val classicActorSystem = classicSystem.classicSystem
        val ec = ctx.executionContext
        val materializer = ActorMaterializer()(classicActorSystem)
        mainBehavior(ctx, classicActorSystem, ec, materializer)
      }
    }.onFailure[Throwable](SupervisorStrategy.resume)

  private def mainBehavior(
      ctx: ActorContext[Command],
      classicActorSystem: ActorSystem,
      ec: ExecutionContext,
      materializer: Materializer
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case request: SendRequest =>
        sendRequest(ctx, request, classicActorSystem, ec, materializer)
        Behaviors.same
    }

  private def sendRequest(
      ctx: ActorContext[Command],
      request: SendRequest,
      classicActorSystem: ActorSystem,
      ec: ExecutionContext,
      materializer: Materializer
  ): Unit = {
    val hostAddress = request.uri.authority.host.address()
    val host = if (hostAddress != null) hostAddress
               else throw new IllegalArgumentException(s"Cannot resolve host: ${request.uri.authority.host}")
    val port = if (request.uri.authority.port == -1) -1 else request.uri.authority.port

    val connectionFlow = if (port == -1) Http()(classicActorSystem).outgoingConnection(host)
                         else Http()(classicActorSystem).outgoingConnection(host, port)

    val httpRequest = request.entity match {
      case Some(entity) =>
        HttpRequest(
          method = request.method,
          uri = request.uri,
          headers = request.headers,
          entity = entity
        )
      case None =>
        HttpRequest(
          method = request.method,
          uri = request.uri,
          headers = request.headers
        )
    }

    val future = Source.single(httpRequest)
      .via(connectionFlow)
      .runWith(Sink.head)(materializer)

    future.onComplete {
      case Success(response) =>
        val bodyFut = response.entity.dataBytes.runWith(Sink.seq[ByteString])(materializer)
        bodyFut.onComplete {
          case Success(bytes) =>
            val body = new String(bytes.flatMap(_.toArray).toArray, StandardCharsets.UTF_8)
            request.replyTo ! RequestSuccess(response.status, response.headers, body)
          case Failure(ex) =>
            request.replyTo ! RequestFailure(s"Failed to read response body: ${ex.getMessage}")
        }(ec)
      case Failure(ex) =>
        request.replyTo ! RequestFailure(s"Request failed: ${ex.getMessage}")
    }(ec)
  }
}
