package com.archimond7450.archiemate.auth

import com.archimond7450.archiemate.settings.JwtConfig
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtOptions}
import pdi.jwt.JwtCirce
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import java.time.Instant
import scala.util.{Failure, Success}

object JwtActor {

  private val actorName = "jwt-actor"

  sealed trait Command
  final case class Encode(userId: String, replyTo: ActorRef[EncodeResponse]) extends Command
  final case class Decode(token: String, replyTo: ActorRef[DecodeResponse]) extends Command
  final case class Validate(token: String, replyTo: ActorRef[ValidateResponse]) extends Command
  final case class DecodeAndValidate(token: String, replyTo: ActorRef[DecodeAndValidateResponse]) extends Command
  final case class Refresh(token: String, replyTo: ActorRef[RefreshResponse]) extends Command

  // Common supertype for all responses
  sealed trait Response

  // ----------------------------------------------------------------
  // Specific response traits per command — callers create probes with
  // these types to get exhaustive match checking on the relevant
  // responses only.
  // ----------------------------------------------------------------

  sealed trait EncodeResponse extends Response
  sealed trait DecodeResponse extends Response
  sealed trait ValidateResponse extends Response
  sealed trait DecodeAndValidateResponse extends Response
  sealed trait RefreshResponse extends Response

  // ----------------------------------------------------------------
  // Response case classes — each extends its own command's trait
  // and Error extends all of them.
  // ----------------------------------------------------------------

  final case class EncodeSuccess(token: String) extends EncodeResponse
  final case class DecodeSuccess(userId: String, expiresAt: Instant) extends DecodeResponse
  final case class ValidateSuccess(valid: Boolean, reason: Option[String] = None) extends ValidateResponse
  final case class DecodeAndValidateSuccess(userId: String, expiresAt: Instant) extends DecodeAndValidateResponse
  final case class RefreshSuccess(newToken: String) extends RefreshResponse
  final case class Error(message: String)
      extends EncodeResponse
      with DecodeResponse
      with ValidateResponse
      with DecodeAndValidateResponse
      with RefreshResponse

  def apply(config: JwtConfig): Behavior[Command] = {
    Behaviors.supervise[Command] {
      Behaviors.setup { ctx =>
        ctx.log.info("JwtActor initialized with token lifetime: {} minutes", config.tokenLifetimeMinutes)
        mainBehavior(config, new JwtOptions(), ctx)
      }
    }.onFailure[Throwable](SupervisorStrategy.resume)
  }

  private def mainBehavior(
      config: JwtConfig,
      options: JwtOptions,
      ctx: ActorContext[Command]
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case Encode(userId, replyTo) =>
        val now = Instant.now()
        val expiresAt = now.plusMillis(java.time.Duration.ofMinutes(config.tokenLifetimeMinutes).toMillis)
        val claim = JwtClaim(subject = Some(userId))
          .issuedAt(now.getEpochSecond)
          .expiresAt(expiresAt.getEpochSecond)
        val token = JwtCirce.encode(claim, config.secret, JwtAlgorithm.HS256)

        replyTo ! EncodeSuccess(token)
        Behaviors.same

      case Decode(token, replyTo) =>
        JwtCirce.decode(token, config.secret, Seq(JwtAlgorithm.HS256)) match {
          case Success(claim) =>
            val userId = claim.subject.getOrElse("")
            val expiresAt = claim.expiration.map(Instant.ofEpochSecond).getOrElse(Instant.MAX)
            replyTo ! DecodeSuccess(userId, expiresAt)
            Behaviors.same
          case Failure(ex) =>
            ctx.log.warn("Failed to decode JWT: {}", ex.getMessage)
            replyTo ! Error(s"Invalid token format: ${ex.getMessage}")
            Behaviors.same
        }

      case Validate(token, replyTo) =>
        val now = Instant.now()
        JwtCirce.decode(token, config.secret, Seq(JwtAlgorithm.HS256)) match {
          case Success(claim) =>
            val isExpired = claim.expiration.exists(_ < now.getEpochSecond)
            if (isExpired) {
              replyTo ! ValidateSuccess(false, Some("Token has expired"))
            } else {
              replyTo ! ValidateSuccess(true)
            }
            Behaviors.same
          case Failure(ex) =>
            ctx.log.debug("JWT validation failed: {}", ex.getMessage)
            replyTo ! ValidateSuccess(false, Some(ex.getMessage))
            Behaviors.same
        }

      case DecodeAndValidate(token, replyTo) =>
        val now = Instant.now()
        JwtCirce.decode(token, config.secret, Seq(JwtAlgorithm.HS256)) match {
          case Success(claim) =>
            val userId = claim.subject.getOrElse("")
            val expiresAt = claim.expiration.map(Instant.ofEpochSecond).getOrElse(Instant.MAX)
            val isExpired = expiresAt.compareTo(now) < 0
            if (isExpired) {
              replyTo ! Error("Token has expired")
            } else {
              replyTo ! DecodeAndValidateSuccess(userId, expiresAt)
            }
            Behaviors.same
          case Failure(ex) =>
            ctx.log.debug("JWT decode and validate failed: {}", ex.getMessage)
            replyTo ! Error(s"Invalid token: ${ex.getMessage}")
            Behaviors.same
        }

      case Refresh(token, replyTo) =>
        JwtCirce.decode(token, config.secret, Seq(JwtAlgorithm.HS256)) match {
          case Success(claim) =>
            val userId = claim.subject.getOrElse("")
            val now = Instant.now()
            val newExpiresAt = now.plusMillis(java.time.Duration.ofMinutes(config.tokenLifetimeMinutes).toMillis)
            val newClaim = JwtClaim(subject = Some(userId))
              .issuedAt(now.getEpochSecond)
              .expiresAt(newExpiresAt.getEpochSecond)
            val newToken = JwtCirce.encode(newClaim, config.secret, JwtAlgorithm.HS256)
            replyTo ! RefreshSuccess(newToken)
            Behaviors.same
          case Failure(ex) =>
            ctx.log.warn("Cannot refresh invalid JWT: {}", ex.getMessage)
            replyTo ! Error(s"Cannot refresh: token is invalid")
            Behaviors.same
        }
    }
}
