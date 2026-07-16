package com.archimond7450.archiemate.auth

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.{*, given}
import org.apache.pekko.util.Timeout

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.{Failure, Success}

object AuthDirectives {

  def authenticateToken(
      token: String,
      jwtActor: ActorRef[JwtActor.Command]
  )(using scheduler: Scheduler, timeout: Timeout, ec: ExecutionContext): Future[Either[Throwable, String]] = {
    val f: Future[JwtActor.Response] = jwtActor.ask[JwtActor.Response](ref =>
      JwtActor.DecodeAndValidate(token, ref)
    )
    f.map {
      case JwtActor.DecodeAndValidateSuccess(userId, _) =>
        Right(userId)
      case JwtActor.Error(message) =>
        Left(new RuntimeException(message))
      case other =>
        Left(new RuntimeException(s"Unexpected response: $other"))
    }
  }
}
