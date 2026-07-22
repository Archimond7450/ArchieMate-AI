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

  // Platform connection commands
  final case class GetAllPlatformConnections(
      userId: String,
      platform: String,
      replyTo: ActorRef[ConnectionResponse]
  ) extends Command

  final case class RegisterPlatformConnection(
      userId: String,
      platform: String,
      channelId: String,
      accessToken: String,
      refreshToken: String,
      expiresAt: Instant,
      replyTo: ActorRef[ConnectionResponse]
  ) extends Command

  final case class RevokePlatformConnection(
      userId: String,
      platform: String,
      channelId: String,
      replyTo: ActorRef[ConnectionResponse]
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
      with ConnectionResponse

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

  sealed trait ConnectionResponse
  final case class AllPlatformConnectionsFound(connections: List[UserTokenActor.PlatformConnection]) extends ConnectionResponse
  final case class ConnectionRegistered(platform: String, channelId: String) extends ConnectionResponse
  final case class ConnectionRevoked(platform: String, channelId: String) extends ConnectionResponse
  case object ConnectionNotFound extends ConnectionResponse

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

        case getAll: GetAllPlatformConnections =>
          getOrCreateUserActor(getAll.userId, state) match {
            case Left(error) =>
              getAll.replyTo ! Error(error)
              Behaviors.same
            case Right(actorRef) =>
              forwardCommand(getAll, actorRef)
              Behaviors.same
          }

        case register: RegisterPlatformConnection =>
          getOrCreateUserActor(register.userId, state) match {
            case Left(error) =>
              register.replyTo ! Error(error)
              Behaviors.same
            case Right(actorRef) =>
              forwardCommand(register, actorRef)
              Behaviors.same
          }

        case revoke: RevokePlatformConnection =>
          getOrCreateUserActor(revoke.userId, state) match {
            case Left(error) =>
              revoke.replyTo ! Error(error)
              Behaviors.same
            case Right(actorRef) =>
              forwardCommand(revoke, actorRef)
              Behaviors.same
          }
      }
    )

  // ----------------------------------------------------------------
  // Forwarding helpers
  // ----------------------------------------------------------------

  /** Trait to build the ask payload for a given command type. */
  private trait AskBuilder[Cmd, Resp] {
    def buildAsk(cmd: Cmd, ref: ActorRef[Resp]): UserTokenActor.Command
  }

  private object AskBuilder {
    given AskBuilder[RegisterTwitchAuthToken, UserTokenActor.AuthResponse] with
      def buildAsk(cmd: RegisterTwitchAuthToken, ref: ActorRef[UserTokenActor.AuthResponse]): UserTokenActor.Command =
        UserTokenActor.RegisterTwitchAuthToken(
          cmd.accessToken,
          cmd.refreshToken,
          cmd.expiresAt,
          cmd.platformUserId,
          ref
        )

    given AskBuilder[GetTwitchAuthToken, UserTokenActor.AuthResponse] with
      def buildAsk(cmd: GetTwitchAuthToken, ref: ActorRef[UserTokenActor.AuthResponse]): UserTokenActor.Command =
        UserTokenActor.GetTwitchAuthToken(cmd.tokenId, ref)

    given AskBuilder[GetAllTwitchAuthTokens, UserTokenActor.AuthResponse] with
      def buildAsk(cmd: GetAllTwitchAuthTokens, ref: ActorRef[UserTokenActor.AuthResponse]): UserTokenActor.Command =
        UserTokenActor.GetAllTwitchAuthTokens(ref)

    given AskBuilder[UpdateTwitchAuthToken, UserTokenActor.AuthResponse] with
      def buildAsk(cmd: UpdateTwitchAuthToken, ref: ActorRef[UserTokenActor.AuthResponse]): UserTokenActor.Command =
        UserTokenActor.UpdateTwitchAuthToken(
          cmd.tokenId,
          cmd.accessToken,
          cmd.refreshToken,
          cmd.expiresAt,
          ref
        )

    given AskBuilder[RevokeTwitchAuthToken, UserTokenActor.AuthResponse] with
      def buildAsk(cmd: RevokeTwitchAuthToken, ref: ActorRef[UserTokenActor.AuthResponse]): UserTokenActor.Command =
        UserTokenActor.RevokeTwitchAuthToken(cmd.tokenId, ref)

    given AskBuilder[HasTwitchAuth, UserTokenActor.AuthResponse] with
      def buildAsk(cmd: HasTwitchAuth, ref: ActorRef[UserTokenActor.AuthResponse]): UserTokenActor.Command =
        UserTokenActor.GetAllTwitchAuthTokens(ref)

    given AskBuilder[GetAllPlatformConnections, UserTokenActor.ConnectionResponse] with
      def buildAsk(cmd: GetAllPlatformConnections, ref: ActorRef[UserTokenActor.ConnectionResponse]): UserTokenActor.Command =
        UserTokenActor.GetAllPlatformConnections(cmd.platform, ref)

    given AskBuilder[RegisterPlatformConnection, UserTokenActor.ConnectionResponse] with
      def buildAsk(cmd: RegisterPlatformConnection, ref: ActorRef[UserTokenActor.ConnectionResponse]): UserTokenActor.Command =
        UserTokenActor.RegisterPlatformConnection(
          cmd.platform,
          cmd.channelId,
          cmd.accessToken,
          cmd.refreshToken,
          cmd.expiresAt,
          ref
        )

    given AskBuilder[RevokePlatformConnection, UserTokenActor.ConnectionResponse] with
      def buildAsk(cmd: RevokePlatformConnection, ref: ActorRef[UserTokenActor.ConnectionResponse]): UserTokenActor.Command =
        UserTokenActor.RevokePlatformConnection(cmd.platform, cmd.channelId, ref)
  }

  /** Forwards a command to a per-user [[UserTokenActor]] and handles the response.
   *
   *  @tparam Cmd the command type
   *  @tparam Resp the response type
   *  @param cmd the command to forward
   *  @param actorRef the per-user actor to send the command to
   */
  private def forwardCommand[Cmd, Resp](
      cmd: Cmd,
      actorRef: ActorRef[UserTokenActor.Command]
  )(using
      builder: AskBuilder[Cmd, Resp],
      scheduler: org.apache.pekko.actor.typed.Scheduler,
      timeout: Timeout,
      ec: ExecutionContext
  ): Unit = {
    val future = actorRef ? { (ref: ActorRef[Resp]) =>
      builder.buildAsk(cmd, ref)
    }
    future.onComplete {
      case scala.util.Success(resp) =>
        resp match {
          case err: UserTokenActor.Error =>
            handleError(cmd, err)
          case other =>
            handleSuccess(cmd, other.asInstanceOf[UserTokenActor.AuthResponse | UserTokenActor.ConnectionResponse])
        }
      case scala.util.Failure(ex) =>
        handleFailure(cmd, ex)
    }(ec)
  }

  private def handleSuccess[Cmd, Resp](
      cmd: Cmd,
      resp: UserTokenActor.AuthResponse | UserTokenActor.ConnectionResponse
  ): Unit = cmd match {
    case register: RegisterTwitchAuthToken =>
      resp match {
        case UserTokenActor.AuthRegistered(tokenId, _) =>
          register.replyTo ! Registered(tokenId)
        case other =>
          register.replyTo ! Error(s"Unexpected response: $other")
      }
    case get: GetTwitchAuthToken =>
      resp match {
        case UserTokenActor.AuthFound(token) =>
          get.replyTo ! TokenFound(token)
        case UserTokenActor.AuthNotFound =>
          get.replyTo ! TokenNotFound
        case other =>
          get.replyTo ! Error(s"Unexpected response: $other")
      }
    case getAll: GetAllTwitchAuthTokens =>
      resp match {
        case UserTokenActor.AllAuthTokensFound(tokens) =>
          getAll.replyTo ! AllTokensFound(tokens)
        case other =>
          getAll.replyTo ! Error(s"Unexpected response: $other")
      }
    case update: UpdateTwitchAuthToken =>
      resp match {
        case UserTokenActor.AuthUpdated(tokenId) =>
          update.replyTo ! Updated(tokenId)
        case other =>
          update.replyTo ! UpdateFailed(s"Unexpected response: $other")
      }
    case revoke: RevokeTwitchAuthToken =>
      resp match {
        case UserTokenActor.AuthRevoked(tokenId) =>
          revoke.replyTo ! Updated(tokenId)
        case other =>
          revoke.replyTo ! UpdateFailed(s"Unexpected response: $other")
      }
    case has: HasTwitchAuth =>
      resp match {
        case UserTokenActor.AllAuthTokensFound(tokens) =>
          has.replyTo ! HasAuth(tokens.nonEmpty)
        case _ =>
          has.replyTo ! HasAuth(false)
      }
    case getAllConn: GetAllPlatformConnections =>
      resp match {
        case UserTokenActor.AllConnectionsFound(connections) =>
          getAllConn.replyTo ! AllPlatformConnectionsFound(connections)
        case other =>
          getAllConn.replyTo ! Error(s"Unexpected response: $other")
      }
    case registerConn: RegisterPlatformConnection =>
      resp match {
        case UserTokenActor.ConnectionRegistered(platform, channelId) =>
          registerConn.replyTo ! ConnectionRegistered(platform, channelId)
        case other =>
          registerConn.replyTo ! Error(s"Unexpected response: $other")
      }
    case revokeConn: RevokePlatformConnection =>
      resp match {
        case UserTokenActor.ConnectionRevoked(platform, channelId) =>
          revokeConn.replyTo ! ConnectionRevoked(platform, channelId)
        case other =>
          revokeConn.replyTo ! Error(s"Unexpected response: $other")
      }
  }

  private def handleError[Cmd](
      cmd: Cmd,
      err: UserTokenActor.Error
  ): Unit = cmd match {
    case _: RegisterTwitchAuthToken =>
      cmd.asInstanceOf[RegisterTwitchAuthToken].replyTo ! Error(err.message)
    case get: GetTwitchAuthToken =>
      get.replyTo ! Error(err.message)
    case getAll: GetAllTwitchAuthTokens =>
      getAll.replyTo ! Error(err.message)
    case update: UpdateTwitchAuthToken =>
      update.replyTo ! UpdateFailed(err.message)
    case revoke: RevokeTwitchAuthToken =>
      revoke.replyTo ! UpdateFailed(err.message)
    case _: HasTwitchAuth =>
      cmd.asInstanceOf[HasTwitchAuth].replyTo ! HasAuth(false)
    case getAllConn: GetAllPlatformConnections =>
      getAllConn.replyTo ! Error(err.message)
    case registerConn: RegisterPlatformConnection =>
      registerConn.replyTo ! Error(err.message)
    case revokeConn: RevokePlatformConnection =>
      revokeConn.replyTo ! Error(err.message)
  }

  private def handleFailure[Cmd](
      cmd: Cmd,
      ex: Throwable
  ): Unit = cmd match {
    case register: RegisterTwitchAuthToken =>
      register.replyTo ! Error(s"Command failed: ${ex.getMessage}")
    case get: GetTwitchAuthToken =>
      get.replyTo ! Error(s"Command failed: ${ex.getMessage}")
    case getAll: GetAllTwitchAuthTokens =>
      getAll.replyTo ! Error(s"Command failed: ${ex.getMessage}")
    case update: UpdateTwitchAuthToken =>
      update.replyTo ! UpdateFailed(s"Command failed: ${ex.getMessage}")
    case revoke: RevokeTwitchAuthToken =>
      revoke.replyTo ! UpdateFailed(s"Command failed: ${ex.getMessage}")
    case _: HasTwitchAuth =>
      cmd.asInstanceOf[HasTwitchAuth].replyTo ! HasAuth(false)
    case getAllConn: GetAllPlatformConnections =>
      getAllConn.replyTo ! Error(s"Command failed: ${ex.getMessage}")
    case registerConn: RegisterPlatformConnection =>
      registerConn.replyTo ! Error(s"Command failed: ${ex.getMessage}")
    case revokeConn: RevokePlatformConnection =>
      revokeConn.replyTo ! Error(s"Command failed: ${ex.getMessage}")
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
