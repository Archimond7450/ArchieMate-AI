package com.archimond7450.archiemate.api

import com.archimond7450.archiemate.auth.AuthDirectives
import com.archimond7450.archiemate.auth.JwtActor
import com.archimond7450.archiemate.settings.AppConfig
import com.archimond7450.archiemate.user.UserConfigRegistry
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
    userConfigRegistry: ActorRef[UserConfigRegistry.Command],
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
        } ~
        // YouTube primary connection operations
        path("connections" / "youtube" / "primary") {
          get {
            getYoutubePrimaryConnection
          } ~
          post {
            postYoutubePrimaryConnection
          } ~
          delete {
            revokeYoutubePrimaryConnection
          }
        } ~
        // YouTube secondary connection operations
        path("connections" / "youtube" / "secondary") {
          get {
            getYoutubeSecondaryConnections
          } ~
          post {
            postYoutubeSecondaryConnection
          } ~
          delete {
            revokeYoutubeSecondaryConnection
          }
        } ~
        path("connections" / "youtube" / "secondary" / Segment) { channelId =>
          delete {
            revokeYoutubeSecondaryConnection(channelId)
          }
        } ~
        // YouTube ad config
        path("youtube" / "ads") {
          get {
            getYoutubeAdsConfig
          } ~
          put {
            putYoutubeAdsConfig
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
      case scala.util.Success(UserTokenRegistry.ConnectionRegistered(platform, channelId)) =>
        complete(StatusCodes.InternalServerError -> s"Unexpected connection registered: $platform/$channelId")
      case scala.util.Success(UserTokenRegistry.ConnectionRevoked(platform, channelId)) =>
        complete(StatusCodes.InternalServerError -> s"Unexpected connection revoked: $platform/$channelId")
      case scala.util.Success(UserTokenRegistry.ConnectionNotFound) =>
        complete(StatusCodes.NotFound -> "No connections found")
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
      case scala.util.Success(UserTokenRegistry.ConnectionRegistered(p, cid)) =>
        complete(StatusCodes.InternalServerError -> s"Unexpected connection registered: $p/$cid")
      case scala.util.Success(UserTokenRegistry.ConnectionRevoked(p, cid)) =>
        complete(StatusCodes.InternalServerError -> s"Unexpected connection revoked: $p/$cid")
      case scala.util.Success(UserTokenRegistry.ConnectionNotFound) =>
        complete(StatusCodes.NotFound -> "No connections found")
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

  // ----------------------------------------------------------------
  // YouTube Primary Connection
  // ----------------------------------------------------------------

  private def getYoutubePrimaryConnection: Route = {
    extractRequest { request =>
      request.cookies.find(_.name == jwtCookieName) match {
        case Some(cookie) =>
          onComplete(AuthDirectives.authenticateToken(cookie.value, jwtActor)) {
            case scala.util.Success(Right(userId)) =>
              handleYoutubePrimaryConnection(userId)
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

  private def handleYoutubePrimaryConnection(userId: String): Route = {
    onComplete(
      userTokenRegistry.ask[UserTokenRegistry.YoutubePrimaryResponse](ref =>
        UserTokenRegistry.GetYoutubePrimaryConnection(userId, ref)
      )
    ) {
      case scala.util.Success(r: UserTokenRegistry.YoutubePrimaryFound) =>
        complete(StatusCodes.OK -> encodeYoutubeConnection(r.connection))
      case scala.util.Success(UserTokenRegistry.YoutubePrimaryNotFound) =>
        complete(StatusCodes.NotFound -> "No YouTube primary connection found")
      case scala.util.Success(r: UserTokenRegistry.YoutubePrimaryError) =>
        complete(StatusCodes.InternalServerError -> r.message)
      case scala.util.Success(other) =>
        // Unexpected response types for GET (registered/revoked are POST/DELETE responses)
        complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
      case scala.util.Failure(ex) =>
        complete(StatusCodes.InternalServerError -> ex.getMessage)
    }
  }

  private def encodeYoutubeConnection(conn: UserTokenActor.PlatformConnection): String = {
    val encoder = deriveEncoder[PlatformConnectionResponse]
    PlatformConnectionResponse(
      conn.platform,
      conn.channelId,
      conn.accessToken,
      conn.refreshToken,
      conn.expiresAt.toEpochMilli
    ).asJson.noSpaces
  }

  private def postYoutubePrimaryConnection: Route = {
    extractRequest { req =>
      req.cookies.find(_.name == jwtCookieName) match {
        case Some(cookie) =>
          onComplete(AuthDirectives.authenticateToken(cookie.value, jwtActor)) {
            case scala.util.Success(Right(userId)) =>
              registerYoutubePrimaryConnection(userId, req)
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

  private def registerYoutubePrimaryConnection(userId: String, request: org.apache.pekko.http.scaladsl.model.HttpRequest): Route = {
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
            userTokenRegistry.ask[UserTokenRegistry.YoutubePrimaryResponse](ref =>
              UserTokenRegistry.RegisterYoutubePrimaryConnection(
                userId,
                req.channelId,
                req.accessToken,
                req.refreshToken,
                expiresAt,
                ref
              )
            )
          ) {
            case scala.util.Success(r: UserTokenRegistry.YoutubePrimaryRegistered) =>
              complete(StatusCodes.Created)
            case scala.util.Success(r: UserTokenRegistry.YoutubePrimaryError) =>
              complete(StatusCodes.Conflict -> r.message)
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

  private def revokeYoutubePrimaryConnection: Route = {
    extractRequest { req =>
      req.cookies.find(_.name == jwtCookieName) match {
        case Some(cookie) =>
          onComplete(AuthDirectives.authenticateToken(cookie.value, jwtActor)) {
            case scala.util.Success(Right(userId)) =>
              handleRevokeYoutubePrimary(userId)
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

  private def handleRevokeYoutubePrimary(userId: String): Route = {
    onComplete(
      userTokenRegistry.ask[UserTokenRegistry.YoutubePrimaryResponse](ref =>
        UserTokenRegistry.RevokeYoutubePrimaryConnection(userId, ref)
      )
    ) {
      case scala.util.Success(r: UserTokenRegistry.YoutubePrimaryRevoked) =>
        complete(StatusCodes.OK)
      case scala.util.Success(r: UserTokenRegistry.YoutubePrimaryError) =>
        complete(StatusCodes.InternalServerError -> r.message)
      case scala.util.Success(other) =>
        complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
      case scala.util.Failure(ex) =>
        complete(StatusCodes.InternalServerError -> ex.getMessage)
    }
  }

  // ----------------------------------------------------------------
  // YouTube Secondary Connections
  // ----------------------------------------------------------------

  private def getYoutubeSecondaryConnections: Route = {
    extractRequest { request =>
      request.cookies.find(_.name == jwtCookieName) match {
        case Some(cookie) =>
          onComplete(AuthDirectives.authenticateToken(cookie.value, jwtActor)) {
            case scala.util.Success(Right(userId)) =>
              handleYoutubeSecondaryConnections(userId)
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

  private def handleYoutubeSecondaryConnections(userId: String): Route = {
    onComplete(
      userTokenRegistry.ask[UserTokenRegistry.YoutubeSecondaryResponse](ref =>
        UserTokenRegistry.GetYoutubeSecondaryConnections(userId, ref)
      )
    ) {
      case scala.util.Success(r: UserTokenRegistry.YoutubeSecondaryConnectionsFound) =>
        complete(StatusCodes.OK -> encodeConnections(r.connections))
      case scala.util.Success(r: UserTokenRegistry.YoutubeSecondaryError) =>
        complete(StatusCodes.InternalServerError -> r.message)
      case scala.util.Success(other) =>
        // Unexpected response types for GET (registered/revoked are POST/DELETE responses)
        complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
      case scala.util.Failure(ex) =>
        complete(StatusCodes.InternalServerError -> ex.getMessage)
    }
  }

  private def postYoutubeSecondaryConnection: Route = {
    extractRequest { req =>
      req.cookies.find(_.name == jwtCookieName) match {
        case Some(cookie) =>
          onComplete(AuthDirectives.authenticateToken(cookie.value, jwtActor)) {
            case scala.util.Success(Right(userId)) =>
              registerYoutubeSecondaryConnection(userId, req)
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

  private def registerYoutubeSecondaryConnection(userId: String, request: org.apache.pekko.http.scaladsl.model.HttpRequest): Route = {
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
            userTokenRegistry.ask[UserTokenRegistry.YoutubeSecondaryResponse](ref =>
              UserTokenRegistry.RegisterYoutubeSecondaryConnection(
                userId,
                req.channelId,
                req.accessToken,
                req.refreshToken,
                expiresAt,
                ref
              )
            )
          ) {
            case scala.util.Success(r: UserTokenRegistry.YoutubeSecondaryRegistered) =>
              complete(StatusCodes.Created)
            case scala.util.Success(r: UserTokenRegistry.YoutubeSecondaryError) =>
              complete(StatusCodes.Conflict -> r.message)
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

  private def revokeYoutubeSecondaryConnection: Route = {
    extractRequest { req =>
      req.cookies.find(_.name == jwtCookieName) match {
        case Some(cookie) =>
          onComplete(AuthDirectives.authenticateToken(cookie.value, jwtActor)) {
            case scala.util.Success(Right(userId)) =>
              handleRevokeYoutubeSecondary(userId, "default-channel")
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

  private def revokeYoutubeSecondaryConnection(channelId: String): Route = {
    extractRequest { req =>
      req.cookies.find(_.name == jwtCookieName) match {
        case Some(cookie) =>
          onComplete(AuthDirectives.authenticateToken(cookie.value, jwtActor)) {
            case scala.util.Success(Right(userId)) =>
              handleRevokeYoutubeSecondary(userId, channelId)
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

  private def handleRevokeYoutubeSecondary(userId: String, channelId: String): Route = {
    onComplete(
      userTokenRegistry.ask[UserTokenRegistry.YoutubeSecondaryResponse](ref =>
        UserTokenRegistry.RevokeYoutubeSecondaryConnection(userId, channelId, ref)
      )
    ) {
      case scala.util.Success(r: UserTokenRegistry.YoutubeSecondaryRevoked) =>
        complete(StatusCodes.OK)
      case scala.util.Success(r: UserTokenRegistry.YoutubeSecondaryError) =>
        complete(StatusCodes.InternalServerError -> r.message)
      case scala.util.Success(other) =>
        complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
      case scala.util.Failure(ex) =>
        complete(StatusCodes.InternalServerError -> ex.getMessage)
    }
  }

  // ----------------------------------------------------------------
  // YouTube Ad Config
  // ----------------------------------------------------------------

  private def getYoutubeAdsConfig: Route = {
    extractRequest { request =>
      request.cookies.find(_.name == jwtCookieName) match {
        case Some(cookie) =>
          onComplete(AuthDirectives.authenticateToken(cookie.value, jwtActor)) {
            case scala.util.Success(Right(userId)) =>
              handleYoutubeAdsConfig(userId)
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

  private def handleYoutubeAdsConfig(userId: String): Route = {
    onComplete(
      userConfigRegistry.ask[UserConfigRegistry.ConfigResponse](ref =>
        UserConfigRegistry.GetConfig(userId, "youtube.ads", ref)
      )
    ) {
      case scala.util.Success(r: UserConfigRegistry.ConfigFound) =>
        val config = decodeYoutubeAdsConfig(r.value)
        complete(StatusCodes.OK -> encodeYoutubeAdsConfig(config.adMode, config.adIntervalSeconds, config.adDescriptionParagraph))
      case scala.util.Success(r: UserConfigRegistry.ConfigNotFound) =>
        complete(StatusCodes.NotFound -> "No YouTube ad configuration found")
      case scala.util.Success(r: UserConfigRegistry.Error) =>
        complete(StatusCodes.InternalServerError -> r.message)
      case scala.util.Success(other) =>
        complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
      case scala.util.Failure(ex) =>
        complete(StatusCodes.InternalServerError -> ex.getMessage)
    }
  }

  private case class YoutubeAdsConfigDto(
      adMode: String,
      adIntervalSeconds: Int,
      adDescriptionParagraph: Int
  )

  private object YoutubeAdsConfigDto {
    import io.circe.Decoder
    import io.circe.generic.semiauto.deriveDecoder
    given Decoder[YoutubeAdsConfigDto] = deriveDecoder
  }

  private def decodeYoutubeAdsConfig(json: String): YoutubeAdsConfigDto = {
    io.circe.parser.decode[YoutubeAdsConfigDto](json) match {
      case Right(config) => config
      case Left(ex) =>
        // Return defaults on decode error
        YoutubeAdsConfigDto("title", 300, 0)
    }
  }

  private def encodeYoutubeAdsConfig(adMode: String, adIntervalSeconds: Int, adDescriptionParagraph: Int): String = {
    val encoder = deriveEncoder[YoutubeAdsConfigResponse]
    YoutubeAdsConfigResponse(adMode, adIntervalSeconds, adDescriptionParagraph).asJson.noSpaces
  }

  private def putYoutubeAdsConfig: Route = {
    extractRequest { req =>
      req.cookies.find(_.name == jwtCookieName) match {
        case Some(cookie) =>
          onComplete(AuthDirectives.authenticateToken(cookie.value, jwtActor)) {
            case scala.util.Success(Right(userId)) =>
              registerYoutubeAdsConfig(userId, req)
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

  private def registerYoutubeAdsConfig(userId: String, request: org.apache.pekko.http.scaladsl.model.HttpRequest): Route = {
    val bodyFuture = request.entity.dataBytes
      .runFold("") { (acc, byteString) =>
        acc + byteString.utf8String
      }
    val bodyDecoded: Future[Either[String, YoutubeAdsConfigDto]] = bodyFuture.map { (bodyStr: String) =>
      decodeYoutubeAdsConfigRequest(bodyStr)
    }(executionContext)
    onComplete(bodyDecoded) { result =>
      result match {
        case scala.util.Success(Right(req)) =>
          // Encode config as JSON for storage
          val configJson = encodeYoutubeAdsConfig(req.adMode, req.adIntervalSeconds, req.adDescriptionParagraph)
          onComplete(
            userConfigRegistry.ask[UserConfigRegistry.ConfigResponse](ref =>
              UserConfigRegistry.SetConfig(userId, "youtube.ads", configJson, ref)
            )
          ) {
            case scala.util.Success(UserConfigRegistry.ConfigSet) =>
              complete(StatusCodes.OK -> configJson)
            case scala.util.Success(r: UserConfigRegistry.Error) =>
              complete(StatusCodes.Conflict -> r.message)
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

  private def decodeYoutubeAdsConfigRequest(json: String): Either[String, YoutubeAdsConfigDto] = {
    io.circe.parser.decode[YoutubeAdsConfigDto](json) match {
      case Right(req) => Right(req)
      case Left(ex)   => Left(s"Invalid request body: ${ex.getMessage}")
    }
  }

  private case class YoutubeAdsConfigResponse(
      adMode: String,
      adIntervalSeconds: Int,
      adDescriptionParagraph: Int
  )

  private object YoutubeAdsConfigResponse {
    import io.circe.Encoder
    import io.circe.generic.semiauto.deriveEncoder
    given Encoder[YoutubeAdsConfigResponse] = deriveEncoder
  }

}
