package com.archimond7450.archiemate.api

import com.archimond7450.archiemate.auth.AuthDirectives
import com.archimond7450.archiemate.auth.JwtActor
import com.archimond7450.archiemate.settings.AppConfig
import com.archimond7450.archiemate.user.UserTokenRegistry
import com.archimond7450.archiemate.user.UserTokenRegistry.{*, given}
import com.archimond7450.archiemate.user.UserTokenActor
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.EncoderOps
import scala.concurrent.Future
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.{*, given}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

/** HTTP routes for platform connection CRUD operations.
  *
  * Endpoints:
  *   - GET    /api/v1/connections              — list all connections
  *   - GET    /api/v1/connections/{platform}   — list connections for a platform
  *   - POST   /api/v1/connections/{platform}   — register a new connection
  *   - DELETE /api/v1/connections/{platform}   — revoke a connection
  */
class ConnectionRoutes(
    config: AppConfig,
    jwtActor: ActorRef[JwtActor.Command],
    userTokenRegistry: ActorRef[UserTokenRegistry.Command],
    scheduler: Scheduler,
    askTimeout: Timeout,
    executionContext: ExecutionContext,
    classicActorSystemProvider: ClassicActorSystemProvider
) {

  given Scheduler = scheduler
  given Timeout = askTimeout
  given ExecutionContext = executionContext
  given ClassicActorSystemProvider = classicActorSystemProvider

  private val jwtCookieName = "archiemate_jwt"

  def connectionRoutes: Route = {
    pathPrefix("api") {
      pathPrefix(config.server.apiVersion) {
        // List all connections
        path("connections") {
          get {
            getConnections
          }
        } ~
        // Platform-specific operations
        path("connections" / Segment) { platform =>
          get {
            getPlatformConnections(platform)
          } ~
          post {
            postPlatformConnection(platform)
          } ~
          delete {
            revokePlatformConnection(platform)
          }
        }
      }
    }
  }

  // ----------------------------------------------------------------
  // GET /api/v1/connections
  // ----------------------------------------------------------------

  private def getConnections: Route = {
    extractRequest { request =>
      request.cookies.find(_.name == jwtCookieName) match {
        case Some(cookie) =>
          onComplete(AuthDirectives.authenticateToken(cookie.value, jwtActor)) {
            case scala.util.Success(Right(userId)) =>
              handleConnections(userId)
            case scala.util.Success(Left(_)) =>
              complete(StatusCodes.Unauthorized -> "Invalid token")
            case scala.util.Failure(_) =>
              complete(StatusCodes.Unauthorized -> "Token authentication failed")
          }
        case None =>
          complete(StatusCodes.Unauthorized -> "Missing authentication")
      }
    }
  }

  private def handleConnections(userId: String): Route = {
    onComplete(
      userTokenRegistry.ask[UserTokenRegistry.ConnectionResponse](ref =>
        UserTokenRegistry.GetAllPlatformConnections(userId, "*", ref)
      )
    ) {
      case scala.util.Success(UserTokenRegistry.AllPlatformConnectionsFound(connections)) =>
        complete(StatusCodes.OK -> encodeConnections(connections))
      case scala.util.Success(UserTokenRegistry.Error(msg)) =>
        complete(StatusCodes.InternalServerError -> msg)
      case scala.util.Failure(ex) =>
        complete(StatusCodes.InternalServerError -> ex.getMessage)
    }
  }

  private def encodeConnections(connections: List[UserTokenActor.PlatformConnection]): String = {
    val encoder = deriveEncoder[PlatformConnectionResponse]
    connections.map { conn =>
      PlatformConnectionResponse(
        conn.platform,
        conn.channelId,
        conn.accessToken,
        conn.refreshToken,
        conn.expiresAt.toEpochMilli
      )
    }.asJson.noSpaces
  }

  private case class PlatformConnectionResponse(
      platform: String,
      channelId: String,
      accessToken: String,
      refreshToken: String,
      expiresAt: Long
  )

  private object PlatformConnectionResponse {
    given Encoder[PlatformConnectionResponse] = deriveEncoder
  }

  // ----------------------------------------------------------------
  // GET /api/v1/connections/{platform}
  // ----------------------------------------------------------------

  private def getPlatformConnections(platform: String): Route = {
    extractRequest { request =>
      request.cookies.find(_.name == jwtCookieName) match {
        case Some(cookie) =>
          onComplete(AuthDirectives.authenticateToken(cookie.value, jwtActor)) {
            case scala.util.Success(Right(userId)) =>
              handlePlatformConnections(userId, platform)
            case scala.util.Success(Left(_)) =>
              complete(StatusCodes.Unauthorized -> "Invalid token")
            case scala.util.Failure(_) =>
              complete(StatusCodes.Unauthorized -> "Token authentication failed")
          }
        case None =>
          complete(StatusCodes.Unauthorized -> "Missing authentication")
      }
    }
  }

  private def handlePlatformConnections(userId: String, platform: String): Route = {
    onComplete(
      userTokenRegistry.ask[UserTokenRegistry.ConnectionResponse](ref =>
        UserTokenRegistry.GetAllPlatformConnections(userId, platform, ref)
      )
    ) {
      case scala.util.Success(UserTokenRegistry.AllPlatformConnectionsFound(connections)) =>
        complete(StatusCodes.OK -> encodeConnections(connections))
      case scala.util.Success(UserTokenRegistry.Error(msg)) =>
        complete(StatusCodes.InternalServerError -> msg)
      case scala.util.Failure(ex) =>
        complete(StatusCodes.InternalServerError -> ex.getMessage)
    }
  }

  // ----------------------------------------------------------------
  // POST /api/v1/connections/{platform}
  // ----------------------------------------------------------------

  private def postPlatformConnection(platform: String): Route = {
    extractRequest { req =>
      req.cookies.find(_.name == jwtCookieName) match {
        case Some(cookie) =>
          onComplete(AuthDirectives.authenticateToken(cookie.value, jwtActor)) {
            case scala.util.Success(Right(userId)) =>
              registerConnection(userId, platform, req)
            case scala.util.Success(Left(_)) =>
              complete(StatusCodes.Unauthorized -> "Invalid token")
            case scala.util.Failure(_) =>
              complete(StatusCodes.Unauthorized -> "Token authentication failed")
          }
        case None =>
          complete(StatusCodes.Unauthorized -> "Missing authentication")
      }
    }
  }

  private def registerConnection(userId: String, platform: String, request: org.apache.pekko.http.scaladsl.model.HttpRequest): Route = {
    val bodyFuture = request.entity.dataBytes
      .runFold("") { (acc, byteString) =>
        acc + byteString.utf8String
      }
    val bodyDecoded: Future[Either[String, RegisterRequest]] = bodyFuture.map { (bodyStr: String) =>
      decodeRegisterRequest(bodyStr)
    }(executionContext)
    onComplete(bodyDecoded) { result =>
      result match {
        case scala.util.Success(Right(req)) =>
          val expiresAt = java.time.Instant.now().plusSeconds(req.expiresIn)
          onComplete(
            userTokenRegistry.ask[UserTokenRegistry.ConnectionResponse](ref =>
              UserTokenRegistry.RegisterPlatformConnection(
                userId,
                platform,
                req.channelId,
                req.accessToken,
                req.refreshToken,
                expiresAt,
                ref
              )
            )
          ) {
            case scala.util.Success(UserTokenRegistry.ConnectionRegistered(_, _)) =>
              complete(StatusCodes.Created)
            case scala.util.Success(UserTokenRegistry.Error(msg)) =>
              complete(StatusCodes.Conflict -> msg)
            case scala.util.Success(other) =>
              complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
            case scala.util.Failure(ex) =>
              complete(StatusCodes.InternalServerError -> ex.getMessage)
          }
        case scala.util.Success(Left(msg)) =>
          complete(StatusCodes.BadRequest -> msg)
        case scala.util.Failure(ex) =>
          complete(StatusCodes.InternalServerError -> ex.getMessage)
      }
    }
  }

  private case class RegisterRequest(
      channelId: String,
      accessToken: String,
      refreshToken: String,
      expiresIn: Long
  )

  private object RegisterRequest {
    import io.circe.Decoder
    import io.circe.generic.semiauto.deriveDecoder
    given Decoder[RegisterRequest] = deriveDecoder
  }

  private def decodeRegisterRequest(json: String): Either[String, RegisterRequest] = {
    io.circe.parser.decode[RegisterRequest](json) match {
      case Right(req) => Right(req)
      case Left(ex)   => Left(s"Invalid request body: ${ex.getMessage}")
    }
  }

  // ----------------------------------------------------------------
  // DELETE /api/v1/connections/{platform}
  // ----------------------------------------------------------------

  private def revokePlatformConnection(platform: String): Route = {
    extractRequest { req =>
      req.cookies.find(_.name == jwtCookieName) match {
        case Some(cookie) =>
          onComplete(AuthDirectives.authenticateToken(cookie.value, jwtActor)) {
            case scala.util.Success(Right(userId)) =>
              handleRevoke(userId, platform)
            case scala.util.Success(Left(_)) =>
              complete(StatusCodes.Unauthorized -> "Invalid token")
            case scala.util.Failure(_) =>
              complete(StatusCodes.Unauthorized -> "Token authentication failed")
          }
        case None =>
          complete(StatusCodes.Unauthorized -> "Missing authentication")
      }
    }
  }

  private def handleRevoke(userId: String, platform: String): Route = {
    onComplete(
      userTokenRegistry.ask[UserTokenRegistry.ConnectionResponse](ref =>
        UserTokenRegistry.RevokePlatformConnection(userId, platform, "channel-789", ref)
      )
    ) {
      case scala.util.Success(UserTokenRegistry.ConnectionRevoked(_, _)) =>
        complete(StatusCodes.OK)
      case scala.util.Success(UserTokenRegistry.Error(msg)) =>
        complete(StatusCodes.InternalServerError -> msg)
      case scala.util.Success(other) =>
        complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
      case scala.util.Failure(ex) =>
        complete(StatusCodes.InternalServerError -> ex.getMessage)
    }
  }

}
