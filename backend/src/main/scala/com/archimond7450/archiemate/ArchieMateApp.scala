package com.archimond7450.archiemate

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, SupervisorStrategy}
import com.archimond7450.archiemate.api.ApiRoutes
import com.archimond7450.archiemate.settings.*
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

object ArchieMateApp {

  private val logger: Logger = LoggerFactory.getLogger(ArchieMateApp.getClass)

  def main(args: Array[String]): Unit = {
    val ec: ExecutionContext = scala.concurrent.ExecutionContext.global
    given ExecutionContext = ec

    val config: Config = ConfigFactory.load()
    val appConfig = AppConfig(config)

    val classicSystem: ClassicActorSystemProvider =
      new ClassicActorSystemProvider {
        def classicSystem: org.apache.pekko.actor.ActorSystem =
          org.apache.pekko.actor.ActorSystem("archiemate-classic", config)
      }
    given ClassicActorSystemProvider = classicSystem

    val readinessPromise = Promise[ActorRef[ReadinessTracker.Command]]()

    val rootBehavior = Behaviors.supervise(
      Behaviors.setup { ctx =>
        val tracker = ctx.spawn(ReadinessTracker.supervised(), "readiness-tracker")
        readinessPromise.success(tracker)
        Behaviors.empty
      }
    ).onFailure[Throwable](SupervisorStrategy.restart)

    val system: ActorSystem[Nothing] =
      ActorSystem(rootBehavior, "archiemate-system").asInstanceOf[ActorSystem[Nothing]]

    logger.info("ArchieMate starting on port {}", appConfig.server.port)

    readinessPromise.future.onComplete {
      case Success(trackerRef) =>
        import org.apache.pekko.actor.typed.scaladsl.adapter.*
        val httpBindingFuture = startHttpServer(appConfig, trackerRef)
        Runtime.getRuntime.addShutdownHook(new Thread(() => {
          given ExecutionContext = ec
          logger.info("ArchieMate shutting down...")
          shutdown(httpBindingFuture, system, classicSystem)
        }))

      case Failure(ex) =>
        logger.error("Failed to spawn ReadinessTracker", ex)
        system.terminate()
    }
  }

  private def startHttpServer(
      appConfig: AppConfig,
      trackerRef: ActorRef[ReadinessTracker.Command]
  )(using cs: ClassicActorSystemProvider)(using ExecutionContext): Future[org.apache.pekko.http.scaladsl.Http.ServerBinding] = {
    val apiRoutes = new ApiRoutes(
      appConfig,
      trackerRef,
      cs.classicSystem
    )
    val httpBindingFuture = HttpServer.start(
      routes = apiRoutes,
      address = appConfig.server.host,
      port = appConfig.server.port
    )

    httpBindingFuture.onComplete {
      case Success(binding) =>
        logger.info("ArchieMate HTTP bound to {}", binding.localAddress)
      case Failure(ex) =>
        logger.error("Failed to bind HTTP server", ex)
    }

    httpBindingFuture
  }

  private def shutdown(
      httpBindingFuture: Future[org.apache.pekko.http.scaladsl.Http.ServerBinding],
      system: ActorSystem[Nothing],
      classicSystem: ClassicActorSystemProvider
  )(using ec: ExecutionContext): Unit = {
    given ExecutionContext = ec
    import org.apache.pekko.actor.typed.scaladsl.adapter.*

    def terminateClassic(): Unit = {
      classicSystem.classicSystem.terminate()
      logger.info("ArchieMate shut down complete")
    }

    httpBindingFuture.flatMap(_.unbind()).onComplete {
      case Success(_) =>
        logger.info("HTTP server unbound")
        system.terminate()
      case Failure(ex) =>
        logger.error("Error unbinding HTTP", ex)
        system.terminate()
    }

    system.whenTerminated.onComplete {
      case Success(_) => terminateClassic()
      case Failure(ex) =>
        logger.error("Error terminating typed system", ex)
        terminateClassic()
    }
  }
}
