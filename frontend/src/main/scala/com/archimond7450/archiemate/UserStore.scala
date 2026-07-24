package com.archimond7450.archiemate

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom
import scala.scalajs.js
import scala.concurrent.ExecutionContext.Implicits.global

/** Reactive store for user authentication state.
  *
  * The frontend does not manage the JWT token directly. Instead, it checks
  * login status via the backend `/api/v1/me` endpoint, which reads the
  * HTTP-only cookie automatically.
  */
object UserStore {

  /** Backend API base URL. */
  private val ApiBaseUrl = "/api/v1"

  /** Current Twitch display name, if logged in. */
  val displayNameVar: Var[String] = Var("")

  /** Current Twitch avatar URL, if logged in. */
  val avatarUrlVar: Var[String] = Var("")

  /** Whether the user is currently authenticated. */
  val isLoggedIn: Var[Boolean] = Var(false)

  /** Whether the user is an administrator. */
  val isAdmin: Var[Boolean] = Var(false)

  /** Whether the user has a Twitch connection. */
  val isTwitchConnected: Var[Boolean] = Var(false)

  /** Twitch connection expiry time, if connected. */
  val twitchConnectionExpiry: Var[String] = Var("")

  /** Signal version of isLoggedIn. */
  val isLoggedInSignal: Signal[Boolean] = isLoggedIn.signal

  /** Check if the user is logged in by calling the backend API.
    *
    * The backend reads the JWT from the HTTP-only cookie automatically.
    * If the user is logged in, also fetches their Twitch profile.
    */
  def checkLogin(): Unit = {
    import org.scalajs.dom
    import org.scalajs.dom.fetch

    val headers = new dom.Headers()
    val init = js.Dynamic.literal(
      method = "GET",
      credentials = "include",
      headers = headers
    ).asInstanceOf[dom.RequestInit]

    val p = scala.concurrent.Promise[js.Dynamic]()

    fetch(s"$ApiBaseUrl/me", init).`then` { (resp: dom.Response) =>
      if (resp.ok) {
        resp.json().`then` { (raw: js.Any) =>
          p.success(raw.asInstanceOf[js.Dynamic])
          raw
        }
      } else {
        p.failure(new RuntimeException("Not logged in"))
        js.undefined
      }
    }

    p.future.onComplete {
      case scala.util.Success(data) =>
        isLoggedIn.set(true)
        isAdmin.set(data.is_admin != null && data.is_admin.asInstanceOf[Boolean])
        fetchUserProfile()
        fetchConnectionStatus()
      case scala.util.Failure(_) =>
        // Token may have expired — try refreshing once
        isLoggedIn.set(false)
        isAdmin.set(false)
        isTwitchConnected.set(false)
        twitchConnectionExpiry.set("")
        displayNameVar.set("")
        avatarUrlVar.set("")
        refresh()
    }
  }

  /** Fetch Twitch user profile using the backend API. */
  private def fetchUserProfile(): Unit = {
    import org.scalajs.dom
    import org.scalajs.dom.fetch

    val headers = new dom.Headers()
    val init = js.Dynamic.literal(
      method = "GET",
      credentials = "include",
      headers = headers
    ).asInstanceOf[dom.RequestInit]

    val p = scala.concurrent.Promise[js.Dynamic]()

    fetch(s"$ApiBaseUrl/twitch/me", init).`then` { (resp: dom.Response) =>
      if (resp.ok) {
        resp.json().`then` { (raw: js.Any) =>
          p.success(raw.asInstanceOf[js.Dynamic])
          raw
        }
      } else {
        p.failure(new RuntimeException("Failed to fetch profile"))
        js.undefined
      }
    }

    p.future.onComplete {
      case scala.util.Success(data) =>
        displayNameVar.set(data.display_name.toString)
        avatarUrlVar.set(data.profile_image_url.toString)
      case scala.util.Failure(_) =>
        displayNameVar.set("")
        avatarUrlVar.set("")
    }
  }

  /** Fetch Twitch connection status from the backend. */
  private def fetchConnectionStatus(): Unit = {
    import org.scalajs.dom
    import org.scalajs.dom.fetch

    val headers = new dom.Headers()
    headers.set("Accept", "application/json")
    val init = js.Dynamic.literal(
      method = "GET",
      credentials = "include",
      headers = headers
    ).asInstanceOf[dom.RequestInit]

    val p = scala.concurrent.Promise[js.Dynamic]()

    fetch(s"$ApiBaseUrl/connections/twitch", init).`then` { (resp: dom.Response) =>
      if (resp.ok) {
        resp.json().`then` { (raw: js.Any) =>
          p.success(raw.asInstanceOf[js.Dynamic])
          raw
        }
      } else {
        p.failure(new RuntimeException("Failed to fetch connection status"))
        js.undefined
      }
    }

    p.future.onComplete {
      case scala.util.Success(data) =>
        val connections = data.asInstanceOf[js.Array[js.Dynamic]]
        if (connections.length > 0) {
          isTwitchConnected.set(true)
          // Show expiry if available
          val expiresAt = connections(0).expires_at.asInstanceOf[js.UndefOr[Double]]
          if (!expiresAt.isEmpty) {
            val date = new js.Date(expiresAt.asInstanceOf[Double])
            twitchConnectionExpiry.set(date.toLocaleString())
          } else {
            twitchConnectionExpiry.set("")
          }
        } else {
          isTwitchConnected.set(false)
          twitchConnectionExpiry.set("")
        }
      case scala.util.Failure(_) =>
        isTwitchConnected.set(false)
        twitchConnectionExpiry.set("")
    }
  }

  /** Revoke the Twitch connection via the backend API. */
  def revokeTwitchConnection(): Unit = {
    import org.scalajs.dom
    import org.scalajs.dom.fetch

    val headers = new dom.Headers()
    headers.set("Accept", "application/json")
    val init = js.Dynamic.literal(
      method = "DELETE",
      credentials = "include",
      headers = headers
    ).asInstanceOf[dom.RequestInit]

    fetch(s"$ApiBaseUrl/connections/twitch", init).`then` { (resp: dom.Response) =>
      if (resp.ok) {
        isTwitchConnected.set(false)
        twitchConnectionExpiry.set("")
      }
      js.undefined
    }
  }

  /** Clear auth state and redirect to home. */
  def logout(): Unit = {
    import org.scalajs.dom
    import org.scalajs.dom.fetch

    val headers = new dom.Headers()
    val init = js.Dynamic.literal(
      method = "POST",
      credentials = "include",
      headers = headers
    ).asInstanceOf[dom.RequestInit]

    fetch(s"$ApiBaseUrl/logout", init).`then` { (_: js.Any) =>
      isLoggedIn.set(false)
      isAdmin.set(false)
      displayNameVar.set("")
      avatarUrlVar.set("")
      dom.window.location.href = "/"
    }
  }

  /** Refresh the JWT cookie by calling the backend /auth/refresh endpoint.
    *
    * The backend sets a new HTTP-only cookie and redirects to /dashboard.
    * This method follows the redirect and lets the page reload with a fresh token.
    */
  def refresh(): Unit = {
    import org.scalajs.dom
    import org.scalajs.dom.fetch

    val headers = new dom.Headers()
    val init = js.Dynamic.literal(
      method = "GET",
      credentials = "include",
      headers = headers,
      redirect = "manual"
    ).asInstanceOf[dom.RequestInit]

    val currentPath = dom.window.location.pathname + dom.window.location.search
    fetch(s"/auth/refresh?return=$currentPath", init).`then` { (resp: dom.Response) =>
      if (resp.status == 302 || resp.status == 301) {
        // Redirect succeeded — follow it to reload with fresh cookie
        resp.headers.get("location").foreach { loc =>
          dom.window.location.href = loc.toString
        }
      }
      js.undefined
    }
  }
}
