package com.archimond7450.archiemate

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.ActorSystem as TypedActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Directives._enhanceRouteWithConcatenation
import com.archimond7450.archiemate.api.ApiRoutes
import com.archimond7450.archiemate.auth.AuthRoutes
import com.archimond7450.archiemate.auth.JwtActor
import com.archimond7450.archiemate.auth.TwitchOAuthActor
import com.archimond7450.archiemate.actors.http.HttpRequestActor
import com.archimond7450.archiemate.http.HttpClientActor
import com.archimond7450.archiemate.settings.*
import com.archimond7450.archiemate.twitch.TwitchApiActor
import com.archimond7450.archiemate.user.UserTokenRegistry
import com.typesafe.config.{Config, ConfigFactory}

import scala.util.{Failure, Success}
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext

object ArchieMateApp {

  private val mediatorName = "archie-mate-mediator"
  private val httpClientName = "http-client-actor"

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
        withHttp(ctx, tracker, appConfig, classicSystem)
      }
    }.onFailure[Throwable](SupervisorStrategy.restart)

  private def createJwtActor(appConfig: AppConfig): Behavior[JwtActor.Command] =
    Behaviors.supervise(JwtActor(appConfig.jwt)).onFailure[Throwable](SupervisorStrategy.resume)

  private def withHttp(
      ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[ArchieMateApp.Command],
      tracker: ActorRef[ReadinessTracker.Command],
      appConfig: AppConfig,
      classicSystem: ClassicActorSystemProvider
  )(using ExecutionContext): Behavior[Command] =
    Behaviors.setup { innerCtx =>
      given ClassicActorSystemProvider = classicSystem
      val httpClient = innerCtx.spawn(
        Behaviors.supervise(HttpClientActor(classicSystem, ConfigFactory.load())).onFailure[Throwable](SupervisorStrategy.resume),
        httpClientName
      )
      val jwtActor = innerCtx.spawn(createJwtActor(appConfig), "jwt-actor")
      val httpRequestActor = innerCtx.spawn(
        Behaviors.supervise(HttpRequestActor(httpClient)).onFailure[Throwable](SupervisorStrategy.resume),
        "http-request-actor"
      )
      val mediator = innerCtx.spawn(
        ArchieMateMediator.supervised(httpClient, httpRequestActor),
        mediatorName
      )
      val twitchOAuthActor = innerCtx.spawn(
        TwitchOAuthActor(appConfig.twitch, mediator),
        "twitch-oauth-actor"
      )
      val userTokenRegistry = innerCtx.spawn(
        UserTokenRegistry(),
        "user-token-registry"
      )
      val twitchApiActor = innerCtx.spawn(
        Behaviors.supervise(TwitchApiActor(appConfig.twitch, httpRequestActor, userTokenRegistry)).onFailure[Throwable](SupervisorStrategy.resume),
        "twitch-api-actor"
      )

      Behaviors.receiveMessage {
        case StartHttp =>
          val apiRoutes = new ApiRoutes(appConfig, tracker, jwtActor, twitchApiActor, classicSystem.classicSystem)
          val authRoutes = new AuthRoutes(
            appConfig.twitch,
            twitchOAuthActor,
            userTokenRegistry,
            jwtActor,
            appConfig.twitch.redirectUriPostfix,
            classicSystem.classicSystem
          )
          val combinedRoutes = authRoutes.authRoutes ~ apiRoutes.apiRoutes
          val bindingFuture = Http().newServerAt(appConfig.server.host, appConfig.server.port).bind(combinedRoutes)
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

    val tracker = TypedActorSystem(
      ReadinessTracker.supervised(),
      "readiness-tracker"
    )

    val rootBehavior = ArchieMateApp(tracker, appConfig, classicSystem)
    val system = TypedActorSystem(rootBehavior, "archiemate-system")

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      system.terminate()
    }))
  }
}
