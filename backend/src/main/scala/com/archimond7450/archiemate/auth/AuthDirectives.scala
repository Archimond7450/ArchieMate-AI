package com.archimond7450.archiemate.auth

import com.archimond7450.archiemate.auth.JwtActor.{DecodeAndValidateSuccess, DecodeAndValidateResponse}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.{*, given}
import org.apache.pekko.util.Timeout

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

object AuthDirectives {

  @scala.annotation.nowarn
  def authenticateToken(
      token: String,
      jwtActor: ActorRef[JwtActor.Command]
  )(using scheduler: Scheduler, timeout: Timeout, ec: ExecutionContext): Future[Either[Throwable, String]] = {
    val f: Future[DecodeAndValidateResponse] = jwtActor.ask[DecodeAndValidateResponse](ref =>
      JwtActor.DecodeAndValidate(token, ref)
    )
    f.map {
      case DecodeAndValidateSuccess(userId, _) => Right(userId)
    }.recover {
      case ex => Left(new RuntimeException(s"Token authentication failed: ${ex.getMessage}"))
    }
  }
}
