package com.archimond7450.archiemate.auth

import com.archimond7450.archiemate.user.UserTokenRegistry
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern.{*, given}
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import com.archimond7450.archiemate.settings.{AppConfig, TwitchConfig}

import java.time.Instant
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext

/** Auth routes at `/auth/...` (not under `/api/v1/`). */
class AuthRoutes(
    appConfig: AppConfig,
    twitchOAuthActor: ActorRef[TwitchOAuthActor.Command],
    userTokenRegistry: ActorRef[UserTokenRegistry.Command],
    jwtActor: ActorRef[JwtActor.Command],
    redirectUriPostfix: String,
    classicActorSystem: org.apache.pekko.actor.ActorSystem
) {

  private val JwtCookieName = "archiemate_jwt"

  private def buildRedirectUri(): String = {
    val host = appConfig.twitch.redirectUriPostfix.split("/").headOption.getOrElse("localhost")
    s"http://$host$redirectUriPostfix"
  }

  def authRoutes: Route = {
    pathPrefix("auth") {
      pathPrefix("twitch") {
        // GET /auth/twitch/login
        path("login") {
          get {
            given Scheduler = classicActorSystem.toTyped.scheduler
            given Timeout = Timeout(appConfig.askTimeout)
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
                  given Timeout = Timeout(appConfig.askTimeout)
                  given ExecutionContext = scala.concurrent.ExecutionContext.global

                  onComplete(twitchOAuthActor.ask[TwitchOAuthActor.TokenExchangeResponse](ref =>
                    TwitchOAuthActor.ExchangeCode(state, ref)
                  )) {
                    case scala.util.Success(TwitchOAuthActor.TokenExchangeSuccess(accessToken, refreshToken, expiresIn, platformUserId)) =>
                      val expiresAt = Instant.now().plusSeconds(expiresIn)
                      onComplete(
                        userTokenRegistry.ask[UserTokenRegistry.RegisterResponse](ref =>
                          UserTokenRegistry.RegisterTwitchAuthToken(
                            platformUserId,
                            accessToken,
                            refreshToken,
                            expiresAt,
                            platformUserId,
                            ref
                          )
                        )
                      ) { tokenResult =>
                        tokenResult match {
                          case scala.util.Success(UserTokenRegistry.Registered(_)) =>
                            // Issue a JWT for this user
                            onComplete(
                              jwtActor.ask[JwtActor.EncodeResponse](ref =>
                                JwtActor.Encode(platformUserId, ref)
                              )
                            ) { jwtResult =>
                              jwtResult match {
                                case scala.util.Success(JwtActor.EncodeSuccess(jwtToken)) =>
                                  // Set HTTP-only cookie and redirect to dashboard
                                  val cookie = org.apache.pekko.http.scaladsl.model.headers.HttpCookie(
                                    "archiemate_jwt",
                                    jwtToken,
                                    httpOnly = true,
                                    secure = true,
                                    maxAge = Some(appConfig.jwt.tokenLifetimeMinutes.toLong * 60)
                                  )
                                  setCookie(cookie) {
                                    redirect(redirectUriPostfix, StatusCodes.Found)
                                  }
                                case scala.util.Success(JwtActor.Error(msg)) =>
                                  redirect(s"$redirectUriPostfix?error=jwt_failed", StatusCodes.Found)
                                case scala.util.Failure(ex) =>
                                  redirect(s"$redirectUriPostfix?error=jwt_failed", StatusCodes.Found)
                              }
                            }
                          case scala.util.Success(UserTokenRegistry.Error(msg)) =>
                            redirect(s"$redirectUriPostfix?error=token_store_failed", StatusCodes.Found)
                          case scala.util.Failure(ex) =>
                            redirect(s"$redirectUriPostfix?error=token_store_failed", StatusCodes.Found)
                        }
                      }
                    case scala.util.Success(TwitchOAuthActor.TokenExchangeError(msg)) =>
                      redirect(s"$redirectUriPostfix?error=token_exchange_failed", StatusCodes.Found)
                    case scala.util.Failure(ex) =>
                      redirect(s"$redirectUriPostfix?error=token_exchange_failed", StatusCodes.Found)
                  }
                case _ =>
                  complete(StatusCodes.BadRequest -> "Missing code or state parameter")
              }
            }
          }
        }
      } ~
      // GET /auth/refresh — renew the JWT cookie without re-authenticating
      path("refresh") {
        get {
          extractRequest { request =>
            val returnUrl = request.uri.query().get("return").getOrElse("/dashboard")
            request.cookies.find(_.name == JwtCookieName) match {
                case Some(cookie) if cookie.value.nonEmpty =>
                  given Scheduler = classicActorSystem.toTyped.scheduler
                  given Timeout = Timeout(appConfig.askTimeout)
                  given ExecutionContext = scala.concurrent.ExecutionContext.global

                  onComplete(
                    jwtActor.ask[JwtActor.RefreshResponse](ref =>
                      JwtActor.Refresh(cookie.value, ref)
                    )
                  ) {
                    case scala.util.Success(JwtActor.RefreshSuccess(newToken)) =>
                      val newCookie = org.apache.pekko.http.scaladsl.model.headers.HttpCookie(
                        JwtCookieName,
                        newToken,
                        httpOnly = true,
                        secure = true,
                        maxAge = Some(appConfig.jwt.tokenLifetimeMinutes.toLong * 60)
                      )
                      setCookie(newCookie) {
                        redirect(returnUrl, StatusCodes.Found)
                      }
                    case scala.util.Success(JwtActor.Error(msg)) =>
                      complete(StatusCodes.Unauthorized -> msg)
                    case scala.util.Failure(ex) =>
                      complete(StatusCodes.InternalServerError -> ex.getMessage)
                  }
                case _ =>
                  complete(StatusCodes.Unauthorized -> "No JWT cookie present")
              }
          }
        }
      }
    }
  }
}
