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

  /** Whether the user has a Kick connection. */
  val isKickConnected: Var[Boolean] = Var(false)

  /** Kick connection expiry time, if connected. */
  val kickConnectionExpiry: Var[String] = Var("")

  /** Whether the user has a YouTube primary connection. */
  val isYoutubeConnected: Var[Boolean] = Var(false)

  /** YouTube primary connection expiry time, if connected. */
  val youtubeConnectionExpiry: Var[String] = Var("")

  /** All YouTube connections (primary + secondary). */
  case class YoutubeConnection(channelId: String, accessToken: String, refreshToken: String, expiresAt: String)
  val youtubeConnections: Var[Seq[YoutubeConnection]] = Var(Seq.empty)

  /** YouTube ad configuration. */
  case class YoutubeAdsConfig(adMode: String, adIntervalSeconds: Int, adDescriptionParagraph: Int)
  val youtubeAdsConfig: Var[YoutubeAdsConfig] = Var(YoutubeAdsConfig("title", 300, 0))

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
        fetchKickConnectionStatus()
        fetchYoutubeConnectionStatus()
        fetchYoutubeConnections()
      case scala.util.Failure(_) =>
        // Token may have expired — try refreshing once
        isLoggedIn.set(false)
        isAdmin.set(false)
        isTwitchConnected.set(false)
        twitchConnectionExpiry.set("")
        isKickConnected.set(false)
        kickConnectionExpiry.set("")
        isYoutubeConnected.set(false)
        youtubeConnectionExpiry.set("")
        youtubeConnections.set(Seq.empty)
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

  /** Fetch Kick connection status from the backend. */
  private def fetchKickConnectionStatus(): Unit = {
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

    fetch(s"$ApiBaseUrl/connections/kick", init).`then` { (resp: dom.Response) =>
      if (resp.ok) {
        resp.json().`then` { (raw: js.Any) =>
          p.success(raw.asInstanceOf[js.Dynamic])
          raw
        }
      } else {
        p.failure(new RuntimeException("Failed to fetch Kick connection status"))
        js.undefined
      }
    }

    p.future.onComplete {
      case scala.util.Success(data) =>
        val connections = data.asInstanceOf[js.Array[js.Dynamic]]
        if (connections.length > 0) {
          isKickConnected.set(true)
          val expiresAt = connections(0).expires_at.asInstanceOf[js.UndefOr[Double]]
          if (!expiresAt.isEmpty) {
            val date = new js.Date(expiresAt.asInstanceOf[Double])
            kickConnectionExpiry.set(date.toLocaleString())
          } else {
            kickConnectionExpiry.set("")
          }
        } else {
          isKickConnected.set(false)
          kickConnectionExpiry.set("")
        }
      case scala.util.Failure(_) =>
        isKickConnected.set(false)
        kickConnectionExpiry.set("")
    }
  }

  /** Revoke the Kick connection via the backend API. */
  def revokeKickConnection(): Unit = {
    import org.scalajs.dom
    import org.scalajs.dom.fetch

    val headers = new dom.Headers()
    headers.set("Accept", "application/json")
    val init = js.Dynamic.literal(
      method = "DELETE",
      credentials = "include",
      headers = headers
    ).asInstanceOf[dom.RequestInit]

    fetch(s"$ApiBaseUrl/connections/kick", init).`then` { (resp: dom.Response) =>
      if (resp.ok) {
        isKickConnected.set(false)
        kickConnectionExpiry.set("")
      }
      js.undefined
    }
  }

  /** Fetch YouTube primary connection status from the backend. */
  def fetchYoutubeConnectionStatus(): Unit = {
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

    fetch(s"$ApiBaseUrl/connections/youtube/primary", init).`then` { (resp: dom.Response) =>
      if (resp.ok) {
        resp.json().`then` { (raw: js.Any) =>
          p.success(raw.asInstanceOf[js.Dynamic])
          raw
        }
      } else {
        p.failure(new RuntimeException("Failed to fetch YouTube primary connection status"))
        js.undefined
      }
    }

    p.future.onComplete {
      case scala.util.Success(data) =>
        isYoutubeConnected.set(true)
        val expiresAt = data.expires_at.asInstanceOf[js.UndefOr[Double]]
        if (!expiresAt.isEmpty) {
          val date = new js.Date(expiresAt.asInstanceOf[Double])
          youtubeConnectionExpiry.set(date.toLocaleString())
        } else {
          youtubeConnectionExpiry.set("")
        }
      case scala.util.Failure(_) =>
        isYoutubeConnected.set(false)
        youtubeConnectionExpiry.set("")
    }
  }

  /** Fetch all YouTube connections (primary + secondary) from the backend. */
  def fetchYoutubeConnections(): Unit = {
    import org.scalajs.dom
    import org.scalajs.dom.fetch

    val headers = new dom.Headers()
    headers.set("Accept", "application/json")
    val init = js.Dynamic.literal(
      method = "GET",
      credentials = "include",
      headers = headers
    ).asInstanceOf[dom.RequestInit]

    fetch(s"$ApiBaseUrl/connections/youtube/secondary", init).`then` { (resp: dom.Response) =>
      if (resp.ok) {
        resp.json().`then` { (raw: js.Any) =>
          val connections = raw.asInstanceOf[js.Array[js.Dynamic]]
          val youtubeConns = (0 until connections.length).map { i =>
            val conn = connections(i)
            val expiresAt = conn.expires_at.asInstanceOf[js.UndefOr[Double]]
            val expiryStr = if (!expiresAt.isEmpty) {
              new js.Date(expiresAt.asInstanceOf[Double]).toLocaleString()
            } else {
              ""
            }
            YoutubeConnection(
              channelId = conn.channel_id.toString,
              accessToken = conn.access_token.toString,
              refreshToken = conn.refresh_token.toString,
              expiresAt = expiryStr
            )
          }
          youtubeConnections.set(youtubeConns)
        }
      }
      js.undefined
    }
  }

  /** Revoke the YouTube primary connection via the backend API. */
  def revokeYoutubeConnection(): Unit = {
    import org.scalajs.dom
    import org.scalajs.dom.fetch

    val headers = new dom.Headers()
    headers.set("Accept", "application/json")
    val init = js.Dynamic.literal(
      method = "DELETE",
      credentials = "include",
      headers = headers
    ).asInstanceOf[dom.RequestInit]

    fetch(s"$ApiBaseUrl/connections/youtube/primary", init).`then` { (resp: dom.Response) =>
      if (resp.ok) {
        isYoutubeConnected.set(false)
        youtubeConnectionExpiry.set("")
        fetchYoutubeConnections()
      }
      js.undefined
    }
  }

  /** Revoke a specific YouTube secondary connection via the backend API. */
  def revokeYoutubeConnection(channelId: String): Unit = {
    import org.scalajs.dom
    import org.scalajs.dom.fetch

    val headers = new dom.Headers()
    headers.set("Accept", "application/json")
    val init = js.Dynamic.literal(
      method = "DELETE",
      credentials = "include",
      headers = headers
    ).asInstanceOf[dom.RequestInit]

    fetch(s"$ApiBaseUrl/connections/youtube/secondary/$channelId", init).`then` { (resp: dom.Response) =>
      if (resp.ok) {
        fetchYoutubeConnections()
      }
      js.undefined
    }
  }

  /** Register a YouTube connection (primary or secondary) via the backend API.
    *
    * @param isPrimary whether to register as primary connection
    */
  def registerYoutubeConnection(channelId: String, accessToken: String, refreshToken: String, expiresIn: Long, isPrimary: Boolean): Unit = {
    import org.scalajs.dom
    import org.scalajs.dom.fetch

    val headers = new dom.Headers()
    headers.set("Accept", "application/json")
    headers.set("Content-Type", "application/json")
    val body = js.Dynamic.literal(
      channelId = channelId,
      accessToken = accessToken,
      refreshToken = refreshToken,
      expiresIn = expiresIn.toDouble
    )
    val bodyStr = js.JSON.stringify(body)
    val init = js.Dynamic.literal(
      method = "POST",
      credentials = "include",
      headers = headers,
      body = bodyStr
    ).asInstanceOf[dom.RequestInit]

    val path = if (isPrimary) s"$ApiBaseUrl/connections/youtube/primary" else s"$ApiBaseUrl/connections/youtube/secondary"
    fetch(path, init).`then` { (resp: dom.Response) =>
      if (resp.ok || resp.status == 201) {
        if (isPrimary) {
          fetchYoutubeConnectionStatus()
        }
        fetchYoutubeConnections()
      }
      js.undefined
    }
  }

  /** Fetch YouTube ad configuration from the backend. */
  def fetchYoutubeAdsConfig(): Unit = {
    import org.scalajs.dom
    import org.scalajs.dom.fetch

    val headers = new dom.Headers()
    headers.set("Accept", "application/json")
    val init = js.Dynamic.literal(
      method = "GET",
      credentials = "include",
      headers = headers
    ).asInstanceOf[dom.RequestInit]

    fetch(s"$ApiBaseUrl/youtube/ads", init).`then` { (resp: dom.Response) =>
      if (resp.ok) {
        resp.json().`then` { (raw: js.Any) =>
          val data = raw.asInstanceOf[js.Dynamic]
          val config = YoutubeAdsConfig(
            adMode = data.ad_mode.toString,
            adIntervalSeconds = data.ad_interval_seconds.asInstanceOf[Double].toInt,
            adDescriptionParagraph = data.ad_description_paragraph.asInstanceOf[Double].toInt
          )
          youtubeAdsConfig.set(config)
        }
      }
      js.undefined
    }
  }

  /** Save YouTube ad configuration to the backend. */
  def saveYoutubeAdsConfig(adMode: String, adIntervalSeconds: Int, adDescriptionParagraph: Int): Unit = {
    import org.scalajs.dom
    import org.scalajs.dom.fetch

    val headers = new dom.Headers()
    headers.set("Accept", "application/json")
    headers.set("Content-Type", "application/json")
    val body = js.Dynamic.literal(
      adMode = adMode,
      adIntervalSeconds = adIntervalSeconds,
      adDescriptionParagraph = adDescriptionParagraph
    )
    val bodyStr = js.JSON.stringify(body)
    val init = js.Dynamic.literal(
      method = "PUT",
      credentials = "include",
      headers = headers,
      body = bodyStr
    ).asInstanceOf[dom.RequestInit]

    fetch(s"$ApiBaseUrl/youtube/ads", init).`then` { (resp: dom.Response) =>
      if (resp.ok) {
        val config = YoutubeAdsConfig(adMode, adIntervalSeconds, adDescriptionParagraph)
        youtubeAdsConfig.set(config)
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
