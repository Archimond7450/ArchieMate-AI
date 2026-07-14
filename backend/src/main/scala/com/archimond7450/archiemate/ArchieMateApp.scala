package com.archimond7450.archiemate

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorSystem, SupervisorStrategy}
import com.archimond7450.archiemate.api.ApiRoutes
import com.archimond7450.archiemate.settings.AppConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success}

object ArchieMateApp {

  private val logger: Logger = LoggerFactory.getLogger(ArchieMateApp.getClass)

  def main(args: Array[String]): Unit = {
    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

    // Load config: Pekko's reference.conf (from classpath) merged with our application.conf
    val config: Config = ConfigFactory.load()
    val appConfig = AppConfig(config)

    // Create classic actor system for Pekko HTTP
    import org.apache.pekko.actor.ClassicActorSystemProvider
    implicit val classicSystem: ClassicActorSystemProvider =
      new ClassicActorSystemProvider {
        def classicSystem: org.apache.pekko.actor.ActorSystem =
          org.apache.pekko.actor.ActorSystem("archiemate-classic", config)
      }

    // Create typed actor system
    val rootBehavior = Behaviors.supervise(Behaviors.empty[Nothing])
      .onFailure(SupervisorStrategy.restart)
    val system: ActorSystem[Nothing] = ActorSystem(rootBehavior, "archiemate-system")

    logger.info("ArchieMate starting on port {}", appConfig.server.port)

    // Start HTTP server
    val httpBindingFuture = HttpServer.start(
      routes = new ApiRoutes(appConfig),
      address = appConfig.server.host,
      port = appConfig.server.port
    )

    httpBindingFuture.onComplete {
      case Success(binding) =>
        logger.info("ArchieMate HTTP bound to {}", binding.localAddress)
      case Failure(ex) =>
        logger.error("Failed to bind HTTP server", ex)
        system.terminate()
    }

    // Register shutdown hook
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      logger.info("ArchieMate shutting down...")

      import org.apache.pekko.actor.typed.scaladsl.adapter._
      import scala.concurrent.Future
      import scala.concurrent.Await
      import scala.concurrent.duration._

      // Unbind the HTTP server
      val unbindFuture = httpBindingFuture.map(_.unbind())
      Await.result(unbindFuture, 30.seconds)
      logger.info("HTTP server unbound")

      // Terminate the actor systems
      system.terminate()
      Await.result(system.whenTerminated, 30.seconds)
      classicSystem.classicSystem.terminate()
      Await.result(classicSystem.classicSystem.whenTerminated, 30.seconds)

      logger.info("ArchieMate shut down complete")
    }))
  }
}
