package com.archimond7450.archiemate

import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Route
import com.archimond7450.archiemate.api.ApiRoutes

import scala.concurrent.Future

object HttpServer {

  def start(
      routes: ApiRoutes,
      address: String,
      port: Int
  )(implicit classicSystem: ClassicActorSystemProvider): Future[Http.ServerBinding] = {
    Http().newServerAt(address, port).bind(routes.apiRoutes)
  }
}
