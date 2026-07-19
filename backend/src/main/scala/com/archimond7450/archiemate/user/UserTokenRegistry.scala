package com.archimond7450.archiemate.user

import com.archimond7450.archiemate.user.UserTokenActor.PlatformToken
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.AskPattern.{*, given}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.util.Timeout

import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext
import java.time.Instant

/** Manages per-user [[UserTokenActor]] instances.

  * Provides a single entry point for registering and retrieving Twitch auth
  * tokens across users. Spawns a [[UserTokenActor]] per userId on demand and
  * caches the reference.
  */
object UserTokenRegistry {

  private val actorName = "user-token-registry"

  // ----------------------------------------------------------------
  // Commands
  // ----------------------------------------------------------------

  sealed trait Command

  final case class RegisterTwitchAuthToken(
      userId: String,
      accessToken: String,
      refreshToken: String,
      expiresAt: Instant,
      platformUserId: String,
      replyTo: ActorRef[RegisterResponse]
  ) extends Command

  final case class GetTwitchAuthToken(
      userId: String,
      tokenId: String,
      replyTo: ActorRef[GetResponse]
  ) extends Command

  final case class GetAllTwitchAuthTokens(
      userId: String,
      replyTo: ActorRef[GetResponse]
  ) extends Command

  final case class UpdateTwitchAuthToken(
      userId: String,
      tokenId: String,
      accessToken: String,
      refreshToken: String,
      expiresAt: Instant,
      replyTo: ActorRef[UpdateResponse]
  ) extends Command

  final case class RevokeTwitchAuthToken(
      userId: String,
      tokenId: String,
      replyTo: ActorRef[UpdateResponse]
  ) extends Command

  final case class HasTwitchAuth(
      userId: String,
      replyTo: ActorRef[HasResponse]
  ) extends Command

  // ----------------------------------------------------------------
  // Responses
  // ----------------------------------------------------------------

  sealed trait RegisterResponse
  final case class Registered(tokenId: String) extends RegisterResponse
  final case class Error(message: String)
      extends RegisterResponse
      with GetResponse
      with UpdateResponse
      with HasResponse

  sealed trait GetResponse
  final case class TokenFound(token: PlatformToken) extends GetResponse
  final case class AllTokensFound(tokens: List[PlatformToken]) extends GetResponse
  case object TokenNotFound extends GetResponse
  case object AllTokensFoundEmpty extends GetResponse

  sealed trait UpdateResponse
  final case class Updated(tokenId: String) extends UpdateResponse
  final case class UpdateFailed(message: String) extends UpdateResponse

  sealed trait HasResponse
  final case class HasAuth(has: Boolean) extends HasResponse

  // ----------------------------------------------------------------
  // Internal state
  // ----------------------------------------------------------------

  private case class State(userActors: Map[String, ActorRef[UserTokenActor.Command]])

  // ----------------------------------------------------------------
  // Factory
  // ----------------------------------------------------------------

  def apply()(using
      classicSystem: ClassicActorSystemProvider
  ): Behavior[Command] =
    Behaviors.supervise[Command] {
      Behaviors.setup { ctx =>
        ctx.log.info("UserTokenRegistry initialized")
        given ctxg: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command] = ctx
        given ec: ExecutionContext = scala.concurrent.ExecutionContext.global
        given scheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
        given timeout: Timeout = Timeout(5.seconds)
        mainBehavior(State(Map.empty))
      }
    }.onFailure[Throwable](SupervisorStrategy.resume)

  // ----------------------------------------------------------------
  // Behavior
  // ----------------------------------------------------------------

  private def mainBehavior(state: State)(using
      ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
      classicSystem: ClassicActorSystemProvider,
      scheduler: org.apache.pekko.actor.typed.Scheduler,
      timeout: Timeout,
      ec: ExecutionContext
  ): Behavior[Command] =
    Behaviors.withMdc(Map("actor" -> actorName))(
      Behaviors.receiveMessage {
        case register: RegisterTwitchAuthToken =>
          getOrCreateUserActor(register.userId, state) match {
            case Left(error) =>
              register.replyTo ! Error(error)
              Behaviors.same
            case Right(actorRef) =>
              forwardCommand(register, actorRef)
              Behaviors.same
          }

        case get: GetTwitchAuthToken =>
          getOrCreateUserActor(get.userId, state) match {
            case Left(error) =>
              get.replyTo ! Error(error)
              Behaviors.same
            case Right(actorRef) =>
              forwardCommand(get, actorRef)
              Behaviors.same
          }

        case getAll: GetAllTwitchAuthTokens =>
          getOrCreateUserActor(getAll.userId, state) match {
            case Left(error) =>
              getAll.replyTo ! Error(error)
              Behaviors.same
            case Right(actorRef) =>
              forwardCommand(getAll, actorRef)
              Behaviors.same
          }

        case update: UpdateTwitchAuthToken =>
          getOrCreateUserActor(update.userId, state) match {
            case Left(error) =>
              update.replyTo ! UpdateFailed(error)
              Behaviors.same
            case Right(actorRef) =>
              forwardCommand(update, actorRef)
              Behaviors.same
          }

        case revoke: RevokeTwitchAuthToken =>
          getOrCreateUserActor(revoke.userId, state) match {
            case Left(error) =>
              revoke.replyTo ! UpdateFailed(error)
              Behaviors.same
            case Right(actorRef) =>
              forwardCommand(revoke, actorRef)
              Behaviors.same
          }

        case has: HasTwitchAuth =>
          getOrCreateUserActor(has.userId, state) match {
            case Left(error) =>
              has.replyTo ! HasAuth(false)
              Behaviors.same
            case Right(actorRef) =>
              forwardCommand(has, actorRef)
              Behaviors.same
          }
      }
    )

  // ----------------------------------------------------------------
  // Forwarding helpers
  // ----------------------------------------------------------------

  private def forwardCommand(
      register: RegisterTwitchAuthToken,
      actorRef: ActorRef[UserTokenActor.Command]
  )(using scheduler: org.apache.pekko.actor.typed.Scheduler, timeout: Timeout, ec: ExecutionContext): Unit = {
    val future = actorRef ? { ref =>
      UserTokenActor.RegisterTwitchAuthToken(
        register.accessToken,
        register.refreshToken,
        register.expiresAt,
        register.platformUserId,
        ref
      )
    }
    future.onComplete {
      case scala.util.Success(resp: UserTokenActor.AuthResponse) =>
        resp match {
          case UserTokenActor.AuthRegistered(tokenId, _) =>
            register.replyTo ! Registered(tokenId)
          case UserTokenActor.Error(msg) =>
            register.replyTo ! Error(msg)
          case other =>
            register.replyTo ! Error(s"Unexpected response: $other")
        }
      case scala.util.Failure(ex) =>
        register.replyTo ! Error(s"Command failed: ${ex.getMessage}")
    }(ec)
  }

  private def forwardCommand(
      get: GetTwitchAuthToken,
      actorRef: ActorRef[UserTokenActor.Command]
  )(using scheduler: org.apache.pekko.actor.typed.Scheduler, timeout: Timeout, ec: ExecutionContext): Unit = {
    val future = actorRef ? { ref =>
      UserTokenActor.GetTwitchAuthToken(get.tokenId, ref)
    }
    future.onComplete {
      case scala.util.Success(resp: UserTokenActor.AuthResponse) =>
        resp match {
          case UserTokenActor.AuthFound(token) =>
            get.replyTo ! TokenFound(token)
          case UserTokenActor.AuthNotFound =>
            get.replyTo ! TokenNotFound
          case UserTokenActor.Error(msg) =>
            get.replyTo ! Error(msg)
          case other =>
            get.replyTo ! Error(s"Unexpected response: $other")
        }
      case scala.util.Failure(ex) =>
        get.replyTo ! Error(s"Command failed: ${ex.getMessage}")
    }(ec)
  }

  private def forwardCommand(
      getAll: GetAllTwitchAuthTokens,
      actorRef: ActorRef[UserTokenActor.Command]
  )(using scheduler: org.apache.pekko.actor.typed.Scheduler, timeout: Timeout, ec: ExecutionContext): Unit = {
    val future = actorRef ? { ref =>
      UserTokenActor.GetAllTwitchAuthTokens(ref)
    }
    future.onComplete {
      case scala.util.Success(resp: UserTokenActor.AuthResponse) =>
        resp match {
          case UserTokenActor.AllAuthTokensFound(tokens) =>
            getAll.replyTo ! AllTokensFound(tokens)
          case UserTokenActor.Error(msg) =>
            getAll.replyTo ! Error(msg)
          case other =>
            getAll.replyTo ! Error(s"Unexpected response: $other")
        }
      case scala.util.Failure(ex) =>
        getAll.replyTo ! Error(s"Command failed: ${ex.getMessage}")
    }(ec)
  }

  private def forwardCommand(
      update: UpdateTwitchAuthToken,
      actorRef: ActorRef[UserTokenActor.Command]
  )(using scheduler: org.apache.pekko.actor.typed.Scheduler, timeout: Timeout, ec: ExecutionContext): Unit = {
    val future = actorRef ? { ref =>
      UserTokenActor.UpdateTwitchAuthToken(
        update.tokenId,
        update.accessToken,
        update.refreshToken,
        update.expiresAt,
        ref
      )
    }
    future.onComplete {
      case scala.util.Success(resp: UserTokenActor.AuthResponse) =>
        resp match {
          case UserTokenActor.AuthUpdated(tokenId) =>
            update.replyTo ! Updated(tokenId)
          case UserTokenActor.Error(msg) =>
            update.replyTo ! UpdateFailed(msg)
          case other =>
            update.replyTo ! UpdateFailed(s"Unexpected response: $other")
        }
      case scala.util.Failure(ex) =>
        update.replyTo ! UpdateFailed(s"Command failed: ${ex.getMessage}")
    }(ec)
  }

  private def forwardCommand(
      revoke: RevokeTwitchAuthToken,
      actorRef: ActorRef[UserTokenActor.Command]
  )(using scheduler: org.apache.pekko.actor.typed.Scheduler, timeout: Timeout, ec: ExecutionContext): Unit = {
    val future = actorRef ? { ref =>
      UserTokenActor.RevokeTwitchAuthToken(revoke.tokenId, ref)
    }
    future.onComplete {
      case scala.util.Success(resp: UserTokenActor.AuthResponse) =>
        resp match {
          case UserTokenActor.AuthRevoked(tokenId) =>
            revoke.replyTo ! Updated(tokenId)
          case UserTokenActor.Error(msg) =>
            revoke.replyTo ! UpdateFailed(msg)
          case other =>
            revoke.replyTo ! UpdateFailed(s"Unexpected response: $other")
        }
      case scala.util.Failure(ex) =>
        revoke.replyTo ! UpdateFailed(s"Command failed: ${ex.getMessage}")
    }(ec)
  }

  private def forwardCommand(
      has: HasTwitchAuth,
      actorRef: ActorRef[UserTokenActor.Command]
  )(using scheduler: org.apache.pekko.actor.typed.Scheduler, timeout: Timeout, ec: ExecutionContext): Unit = {
    val future = actorRef ? { ref =>
      UserTokenActor.GetAllTwitchAuthTokens(ref)
    }
    future.onComplete {
      case scala.util.Success(resp: UserTokenActor.AuthResponse) =>
        resp match {
          case UserTokenActor.AllAuthTokensFound(tokens) =>
            has.replyTo ! HasAuth(tokens.nonEmpty)
          case UserTokenActor.Error(msg) =>
            has.replyTo ! HasAuth(false)
          case other =>
            has.replyTo ! HasAuth(false)
        }
      case scala.util.Failure(_) =>
        has.replyTo ! HasAuth(false)
    }(ec)
  }

  private def getOrCreateUserActor(
      userId: String,
      state: State
  )(using
      ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
      classicSystem: ClassicActorSystemProvider
  ): Either[String, ActorRef[UserTokenActor.Command]] = {
    state.userActors.get(userId) match {
      case Some(ref) =>
        Right(ref)
      case None =>
        try {
          val actorRef = ctx.spawn(
            Behaviors.supervise(UserTokenActor(userId)).onFailure[Throwable](SupervisorStrategy.resume),
            s"user-token-$userId"
          )
          ctx.log.info("Spawned UserTokenActor for user {}", userId)
          Right(actorRef)
        } catch {
          case ex: Exception =>
            ctx.log.error("Failed to spawn UserTokenActor for user {}: {}", userId, ex.getMessage)
            Left(s"Failed to spawn UserTokenActor: ${ex.getMessage}")
        }
    }
  }

}
