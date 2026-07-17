package com.archimond7450.archiemate.api

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.{*, given}
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.Authorization
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import com.archimond7450.archiemate.ReadinessTracker
import com.archimond7450.archiemate.auth.AuthDirectives
import com.archimond7450.archiemate.auth.JwtActor
import com.archimond7450.archiemate.settings.*

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

class ApiRoutes(
    config: AppConfig,
    readinessTracker: ActorRef[ReadinessTracker.Command],
    jwtActor: ActorRef[JwtActor.Command],
    classicActorSystem: org.apache.pekko.actor.ActorSystem
) {

  private val apiVersion = config.server.apiVersion

  def apiRoutes: Route = {
    pathPrefix("api") {
      pathPrefix(apiVersion) {
        // Health check endpoints
        path("live") {
          get {
            complete(StatusCodes.NoContent)
          }
        } ~
        path("ready") {
          get {
            given Scheduler = classicActorSystem.toTyped.scheduler
            given Timeout = Timeout(3.seconds)
            given ExecutionContext = scala.concurrent.ExecutionContext.global
            onSuccess(
              readinessTracker.ask[ReadinessTracker.ReadinessResponse](ref =>
                ReadinessTracker.CheckReadiness(ref)
              )
            ) {
              case ReadinessTracker.ReadyResponse    => complete(StatusCodes.NoContent)
              case ReadinessTracker.NotReadyResponse => complete(StatusCodes.ServiceUnavailable)
            }
          }
        } ~
        // Authenticated endpoints
        path("me") {
          get {
            given Scheduler = classicActorSystem.toTyped.scheduler
            given Timeout = Timeout(3.seconds)
            given ExecutionContext = scala.concurrent.ExecutionContext.global
            extractRequest { request =>
              request.header[Authorization] match {
                case Some(Authorization(credentials)) =>
                  val token = credentials.value
                  onComplete(AuthDirectives.authenticateToken(token, jwtActor)) {
                    case Success(Right(userId)) => complete(StatusCodes.OK -> s"Authenticated as $userId")
                    case Success(Left(_))       => complete(StatusCodes.Unauthorized -> "Invalid token")
                    case Failure(_)             => complete(StatusCodes.Unauthorized -> "Token authentication failed")
                  }
                case None =>
                  complete(StatusCodes.Unauthorized -> "Missing Authorization header")
              }
            }
          }
        } ~
        // Public auth endpoints
        path("auth") {
          get {
            complete(StatusCodes.OK -> "Auth endpoints available")
          }
        }
      }
    } ~
    // Serve static frontend files
    pathEndOrSingleSlash {
      getFromResource("public/index.html")
    } ~
    pathPrefix("static") {
      getFromResourceDirectory("public")
    } ~
    // Catch-all for SPA routing — serve index.html for any unknown path
    get {
      getFromResource("public/index.html")
    }
  }
}
