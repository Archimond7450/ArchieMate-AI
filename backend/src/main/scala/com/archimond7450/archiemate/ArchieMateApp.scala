package com.archimond7450.archiemate

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import com.archimond7450.archiemate.api.ApiRoutes
import com.archimond7450.archiemate.auth.JwtActor
import com.archimond7450.archiemate.settings.*
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.http.scaladsl.Http

import scala.util.{Failure, Success}
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext

object ArchieMateApp {

  sealed trait Command
  private case object StartHttp extends Command
  private final case class HttpBound(binding: Http.ServerBinding) extends Command
  private case object Stop extends Command

  def apply(
      tracker: ActorRef[ReadinessTracker.Command],
      appConfig: AppConfig,
      classicSystem: ClassicActorSystemProvider
  ): Behavior[Command] =
    Behaviors.supervise[Command] {
      Behaviors.setup { ctx =>
        given ExecutionContext = scala.concurrent.ExecutionContext.global
        tracker ! ReadinessTracker.Register(ctx.self.asInstanceOf[ActorRef[Nothing]])
        ctx.self ! StartHttp
        withHttp(tracker, appConfig, classicSystem)
      }
    }.onFailure[Throwable](SupervisorStrategy.restart)

  private def createJwtActor(appConfig: AppConfig): Behavior[JwtActor.Command] =
    Behaviors.supervise(JwtActor(appConfig.jwt)).onFailure[Throwable](SupervisorStrategy.resume)

  private def withHttp(
      tracker: ActorRef[ReadinessTracker.Command],
      appConfig: AppConfig,
      classicSystem: ClassicActorSystemProvider
  )(using ExecutionContext): Behavior[Command] =
    Behaviors.setup { ctx =>
      given ClassicActorSystemProvider = classicSystem
      val jwtActor = ctx.spawn(createJwtActor(appConfig), "jwt-actor")
      Behaviors.receiveMessage {
        case StartHttp =>
          val apiRoutes = new ApiRoutes(appConfig, tracker, jwtActor, classicSystem.classicSystem)
          val bindingFuture = Http().newServerAt(appConfig.server.host, appConfig.server.port).bind(apiRoutes.apiRoutes)
          bindingFuture.onComplete {
            case scala.util.Success(binding) =>
              ctx.self ! HttpBound(binding)
            case scala.util.Failure(ex) =>
              ctx.log.error("Failed to bind the HTTP server!", ex)
          }
          Behaviors.same

        case HttpBound(binding) =>
          ctx.log.info("ArchieMate HTTP bound to {}", binding.localAddress)
          binding.addToCoordinatedShutdown(10.seconds)(classicSystem.classicSystem)
          tracker ! ReadinessTracker.Ready(ctx.self.asInstanceOf[ActorRef[Nothing]])
          ready(tracker)

        case Stop =>
          Behaviors.stopped
      }
    }

  private def ready(tracker: ActorRef[ReadinessTracker.Command]): Behavior[Command] =
    Behaviors.receiveMessage {
      case Stop =>
        Behaviors.stopped
      case msg =>
        Behaviors.same
    }

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.load()
    val appConfig = AppConfig(config)

    val classicSystem = new ClassicActorSystemProvider {
      def classicSystem = org.apache.pekko.actor.ActorSystem("archiemate-classic", config)
    }

    val tracker = ActorSystem(
      ReadinessTracker.supervised(),
      "readiness-tracker"
    )

    val rootBehavior = ArchieMateApp(tracker, appConfig, classicSystem)
    val system = ActorSystem(rootBehavior, "archiemate-system")

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      system.terminate()
    }))
  }
}
