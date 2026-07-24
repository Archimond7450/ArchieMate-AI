package com.archimond7450.archiemate.pages

import com.archimond7450.archiemate.UserStore
import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

/** Dashboard page for logged-in users.
  *
  * Shows user profile info and Twitch connection management.
  */
object DashboardPage {

  /** Format a date string for display. */
  private def formatDate(dateStr: String): String = {
    if (dateStr.isEmpty) "Unknown"
    else dateStr
  }

  /** Render a status badge. */
  private def statusBadge(connected: Boolean): Element = {
    span(
      cls(
        "inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-medium",
        if (connected)
          "bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400"
        else
          "bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400"
      ),
      span(
        cls(
          "w-1.5 h-1.5 rounded-full",
          if (connected) "bg-green-500" else "bg-red-500"
        )
      ),
      if (connected) "Connected" else "Disconnected"
    )
  }

  /** Render an action button. */
  private def actionButton(
      label: String,
      handler: dom.Event => Unit,
      disabled: Boolean,
      variant: String = "primary"
  ): Element = {
    val baseCls = "inline-flex items-center justify-center gap-2 px-4 py-2 text-sm font-medium rounded-md transition-colors focus:outline-none focus:ring-2 focus:ring-offset-2"
    val variantCls = variant match {
      case "primary" => "bg-indigo-600 text-white hover:bg-indigo-700 focus:ring-indigo-500 dark:bg-indigo-500 dark:hover:bg-indigo-600"
      case "danger"  => "bg-red-600 text-white hover:bg-red-700 focus:ring-red-500 dark:bg-red-500 dark:hover:bg-red-600"
      case "outline" => "bg-white text-gray-700 border border-gray-300 hover:bg-gray-50 focus:ring-indigo-500 dark:bg-gray-800 dark:text-gray-300 dark:border-gray-600 dark:hover:bg-gray-700"
      case _         => baseCls
    }
    val disabledCls = if (disabled) "opacity-50 cursor-not-allowed" else "cursor-pointer"

    button(
      cls(s"$baseCls $variantCls $disabledCls"),
      onClick --> handler,
      children <-- Var(label).signal.map { txt =>
        Seq(span(txt))
      }
    )
  }

  def render(): Element = {
    val confirmDisconnect = Var(false)

    sectionTag(
      cls("py-16 px-4 sm:px-6 lg:px-8"),
      div(
        cls("max-w-4xl mx-auto space-y-8"),

        // Header
        div(
          h1(
            cls("text-4xl font-bold tracking-tight sm:text-5xl lg:text-6xl"),
            "Dashboard"
          )
        ),

        // User profile card
        div(
          cls("bg-white dark:bg-gray-800 rounded-lg shadow-sm border border-gray-200 dark:border-gray-700 p-6"),
          h2(
            cls("text-xl font-semibold mb-4"),
            "Profile"
          ),
          div(
            cls("flex items-center gap-4"),
            img(
              cls("w-16 h-16 rounded-full border-2 border-gray-200 dark:border-gray-700"),
              src <-- UserStore.avatarUrlVar.signal.map { url =>
                if (url.nonEmpty) url else "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Ccircle cx='50' cy='50' r='50' fill='%23e5e7eb'/%3E%3C/svg%3E"
              },
              alt("Profile")
            ),
            div(
              div(
                cls("text-lg font-medium text-gray-900 dark:text-white"),
                child <-- UserStore.displayNameVar.signal.map { name =>
                  if (name.nonEmpty) name else "Unknown"
                }
              ),
              div(
                cls("text-sm text-gray-500 dark:text-gray-400"),
                "Logged in via Twitch"
              )
            )
          )
        ),

        // Twitch connection card
        div(
          cls("bg-white dark:bg-gray-800 rounded-lg shadow-sm border border-gray-200 dark:border-gray-700 p-6"),
          div(
            cls("flex items-center justify-between mb-4"),
            h2(
              cls("text-xl font-semibold"),
              "Twitch Connection"
            ),
            children <-- UserStore.isTwitchConnected.signal.map { connected =>
              Seq(statusBadge(connected))
            }
          ),
          div(
            cls("space-y-4"),
            // Connection details
            div(
              cls("grid grid-cols-1 sm:grid-cols-2 gap-4"),
              div(
                cls("text-sm text-gray-500 dark:text-gray-400"),
                "Status"
              ),
              div(
                cls("text-sm font-medium text-gray-900 dark:text-white"),
                children <-- UserStore.isTwitchConnected.signal.map { connected =>
                  if (connected) Seq(span("Authorized")) else Seq(span("Not authorized"))
                }
              ),
              div(
                cls("text-sm text-gray-500 dark:text-gray-400"),
                "Token expires"
              ),
              div(
                cls("text-sm font-medium text-gray-900 dark:text-white"),
                children <-- UserStore.twitchConnectionExpiry.signal.map { expiry =>
                  Seq(span(formatDate(expiry)))
                }
              )
            ),
            // Divider
            div(cls("border-t border-gray-200 dark:border-gray-700")),
            // Actions
            div(
              cls("flex flex-wrap gap-3"),
              children <-- UserStore.isTwitchConnected.signal.map { connected =>
                if (!connected) {
                  Seq(
                    actionButton(
                      "Connect Twitch",
                      handler = { _ =>
                        dom.window.location.href = "/auth/twitch/authorize"
                      },
                      disabled = false,
                      variant = "primary"
                    )
                  )
                } else if (confirmDisconnect.now()) {
                  Seq(
                    actionButton(
                      "Confirm disconnect",
                      handler = { _ =>
                        UserStore.revokeTwitchConnection()
                        confirmDisconnect.set(false)
                      },
                      disabled = false,
                      variant = "danger"
                    ),
                    actionButton(
                      "Cancel",
                      handler = { _ =>
                        confirmDisconnect.set(false)
                      },
                      disabled = false,
                      variant = "outline"
                    )
                  )
                } else {
                  Seq(
                    actionButton(
                      "Reconnect",
                      handler = { _ =>
                        dom.window.location.href = "/auth/twitch/authorize"
                      },
                      disabled = false,
                      variant = "primary"
                    ),
                    actionButton(
                      "Disconnect",
                      handler = { _ =>
                        confirmDisconnect.set(true)
                      },
                      disabled = false,
                      variant = "danger"
                    )
                  )
                }
              }
            )
          )
        ),

        // Kick connection card
        div(
          cls("bg-white dark:bg-gray-800 rounded-lg shadow-sm border border-gray-200 dark:border-gray-700 p-6"),
          div(
            cls("flex items-center justify-between mb-4"),
            h2(
              cls("text-xl font-semibold"),
              "Kick Connection"
            ),
            children <-- UserStore.isKickConnected.signal.map { connected =>
              Seq(statusBadge(connected))
            }
          ),
          div(
            cls("space-y-4"),
            // Connection details
            div(
              cls("grid grid-cols-1 sm:grid-cols-2 gap-4"),
              div(
                cls("text-sm text-gray-500 dark:text-gray-400"),
                "Status"
              ),
              div(
                cls("text-sm font-medium text-gray-900 dark:text-white"),
                children <-- UserStore.isKickConnected.signal.map { connected =>
                  if (connected) Seq(span("Connected")) else Seq(span("Not connected"))
                }
              ),
              div(
                cls("text-sm text-gray-500 dark:text-gray-400"),
                "Token expires"
              ),
              div(
                cls("text-sm font-medium text-gray-900 dark:text-white"),
                children <-- UserStore.kickConnectionExpiry.signal.map { expiry =>
                  Seq(span(formatDate(expiry)))
                }
              )
            ),
            // Divider
            div(cls("border-t border-gray-200 dark:border-gray-700")),
            // Actions
            div(
              cls("flex flex-wrap gap-3"),
              children <-- UserStore.isKickConnected.signal.map { connected =>
                if (!connected) {
                  Seq(
                    actionButton(
                      "Connect Kick",
                      handler = { _ =>
                        dom.window.location.href = "/auth/kick/authorize"
                      },
                      disabled = false,
                      variant = "primary"
                    )
                  )
                } else if (confirmDisconnect.now()) {
                  Seq(
                    actionButton(
                      "Confirm disconnect",
                      handler = { _ =>
                        UserStore.revokeKickConnection()
                        confirmDisconnect.set(false)
                      },
                      disabled = false,
                      variant = "danger"
                    ),
                    actionButton(
                      "Cancel",
                      handler = { _ =>
                        confirmDisconnect.set(false)
                      },
                      disabled = false,
                      variant = "outline"
                    )
                  )
                } else {
                  Seq(
                    actionButton(
                      "Reconnect",
                      handler = { _ =>
                        dom.window.location.href = "/auth/kick/authorize"
                      },
                      disabled = false,
                      variant = "primary"
                    ),
                    actionButton(
                      "Disconnect",
                      handler = { _ =>
                        confirmDisconnect.set(true)
                      },
                      disabled = false,
                      variant = "danger"
                    )
                  )
                }
              }
            )
          )
        ),

        // Info section
        div(
          cls("bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 rounded-lg p-4"),
          div(
            cls("flex gap-3"),
            svg.svg(
              svg.className("w-5 h-5 text-blue-500 flex-shrink-0 mt-0.5"),
              svg.xmlns("http://www.w3.org/2000/svg"),
              svg.fill("none"),
              svg.viewBox("0 0 24 24"),
              svg.stroke("currentColor"),
              svg.strokeWidth("2"),
              svg.path(
                svg.strokeLineCap("round"),
                svg.strokeLineJoin("round"),
                svg.d("M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z")
              )
            ),
            div(
              cls("text-sm text-blue-700 dark:text-blue-300"),
              p(cls("font-medium mb-1"), "About Twitch authorization"),
              p(
                cls("mb-2"),
                "ArchieMate uses Twitch OAuth to connect to your Twitch chat. " +
                "Your access token is stored securely and used only for chat operations. " +
                "Your email is never read or stored."
              )
            )
          )
        )
      )
    )
  }
}
