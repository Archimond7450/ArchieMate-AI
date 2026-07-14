package com.archimond7450.archiemate.api

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import com.archimond7450.archiemate.settings.AppConfig

class ApiRoutes(config: AppConfig) {

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
            complete(StatusCodes.NoContent)
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
