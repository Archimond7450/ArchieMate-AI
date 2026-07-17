package com.archimond7450.archiemate.auth

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.{*, given}
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.Location
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import com.archimond7450.archiemate.settings.TwitchConfig

import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext

/** Auth routes at `/auth/...` (not under `/api/v1/`). */
class AuthRoutes(
    twitchConfig: TwitchConfig,
    twitchOAuthActor: ActorRef[TwitchOAuthActor.Command],
    redirectUriPostfix: String,
    classicActorSystem: org.apache.pekko.actor.ActorSystem
) {

  private def buildRedirectUri(): String = {
    // The redirect URI is constructed from the server's base URL + the postfix
    // In production, this should come from the request or be configurable
    val host = twitchConfig.redirectUriPostfix.split("/").headOption.getOrElse("localhost")
    s"http://$host$redirectUriPostfix"
  }

  def authRoutes: Route = {
    pathPrefix("auth") {
      pathPrefix("twitch") {
        // GET /auth/twitch/login
        path("login") {
          get {
            given Scheduler = classicActorSystem.toTyped.scheduler
            given Timeout = Timeout(5.seconds)
            given ExecutionContext = scala.concurrent.ExecutionContext.global

            val redirectUri = buildRedirectUri()
            onComplete(twitchOAuthActor.ask[TwitchOAuthActor.StateGenerated](ref =>
              TwitchOAuthActor.GenerateState(redirectUri, ref)
            )) {
              case scala.util.Success(TwitchOAuthActor.StateOk(_, authUrl)) =>
                redirect(authUrl.toString, StatusCodes.Found)
              case scala.util.Success(TwitchOAuthActor.StateError(msg)) =>
                complete(StatusCodes.InternalServerError -> s"Failed to generate OAuth state: $msg")
              case scala.util.Failure(ex) =>
                complete(StatusCodes.InternalServerError -> s"OAuth state generation failed: ${ex.getMessage}")
            }
          }
        } ~
        // GET /auth/twitch/callback
        path("callback") {
          get {
            extractRequest { request =>
              val code = request.uri.query().get("code")
              val state = request.uri.query().get("state")

              (code, state) match {
                case (Some(code), Some(state)) =>
                  given Scheduler = classicActorSystem.toTyped.scheduler
                  given Timeout = Timeout(10.seconds)
                  given ExecutionContext = scala.concurrent.ExecutionContext.global

                  onComplete(twitchOAuthActor.ask[TwitchOAuthActor.TokenExchangeResponse](ref =>
                    TwitchOAuthActor.ExchangeCode(state, ref)
                  )) {
                    case scala.util.Success(TwitchOAuthActor.TokenExchangeSuccess(accessToken, refreshToken, expiresIn, platformUserId)) =>
                      // TODO: Store tokens in UserTokenActor and issue JWT
                      complete(StatusCodes.OK ->
                        s"""{
                           |  "platformUserId": "$platformUserId",
                           |  "accessToken": "$accessToken",
                           |  "refreshToken": "$refreshToken",
                           |  "expiresIn": $expiresIn
                           |}""".stripMargin
                      )
                    case scala.util.Success(TwitchOAuthActor.TokenExchangeError(msg)) =>
                      complete(StatusCodes.Unauthorized -> s"Token exchange failed: $msg")
                    case scala.util.Failure(ex) =>
                      complete(StatusCodes.InternalServerError -> s"Token exchange failed: ${ex.getMessage}")
                  }
                case _ =>
                  complete(StatusCodes.BadRequest -> "Missing code or state parameter")
              }
            }
          }
        }
      }
    }
  }
}
