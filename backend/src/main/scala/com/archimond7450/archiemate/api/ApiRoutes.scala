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
    }
  }
}
