package com.archimond7450.archiemate.api

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.{*, given}
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import com.archimond7450.archiemate.ReadinessTracker
import com.archimond7450.archiemate.settings.*

import scala.concurrent.duration._

class ApiRoutes(
    config: AppConfig,
    readinessTracker: ActorRef[ReadinessTracker.Command],
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
            onSuccess(
              readinessTracker.ask[ReadinessTracker.ReadinessResponse](ref =>
                ReadinessTracker.CheckReadiness(ref)
              )
            ) {
              case ReadinessTracker.ReadyResponse    => complete(StatusCodes.NoContent)
              case ReadinessTracker.NotReadyResponse => complete(StatusCodes.ServiceUnavailable)
              case _                                 => complete(StatusCodes.ServiceUnavailable)
            }
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
