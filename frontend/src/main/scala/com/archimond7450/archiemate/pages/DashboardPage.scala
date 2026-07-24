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

        // YouTube connection card
        div(
          cls("bg-white dark:bg-gray-800 rounded-lg shadow-sm border border-gray-200 dark:border-gray-700 p-6"),
          div(
            cls("flex items-center justify-between mb-4"),
            h2(
              cls("text-xl font-semibold"),
              "YouTube Connection"
            ),
            children <-- UserStore.isYoutubeConnected.signal.map { connected =>
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
                children <-- UserStore.isYoutubeConnected.signal.map { connected =>
                  if (connected) Seq(span("Connected")) else Seq(span("Not connected"))
                }
              ),
              div(
                cls("text-sm text-gray-500 dark:text-gray-400"),
                "Token expires"
              ),
              div(
                cls("text-sm font-medium text-gray-900 dark:text-white"),
                children <-- UserStore.youtubeConnectionExpiry.signal.map { expiry =>
                  Seq(span(formatDate(expiry)))
                }
              )
            ),
            // Divider
            div(cls("border-t border-gray-200 dark:border-gray-700")),
            // Actions
            div(
              cls("flex flex-wrap gap-3"),
              children <-- UserStore.isYoutubeConnected.signal.map { connected =>
                if (!connected) {
                  Seq(
                    actionButton(
                      "Connect YouTube",
                      handler = { _ =>
                        dom.window.location.href = "/auth/youtube/authorize"
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
                        UserStore.revokeYoutubeConnection()
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
                        dom.window.location.href = "/auth/youtube/authorize"
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

        // Secondary YouTube connections
        div(
          cls("bg-white dark:bg-gray-800 rounded-lg shadow-sm border border-gray-200 dark:border-gray-700 p-6"),
          h2(
            cls("text-xl font-semibold mb-4"),
            "Secondary YouTube Connections"
          ),
          div(
            cls("text-sm text-gray-500 dark:text-gray-400 mb-4"),
            "Additional YouTube connections for querying latest videos. These are used for ad promotions during Twitch ad breaks."
          ),
          children <-- UserStore.youtubeConnections.signal.map { connections =>
            if (connections.isEmpty) {
              Seq(
                div(
                  cls("text-sm text-gray-400 dark:text-gray-500 italic py-2"),
                  "No secondary connections configured"
                )
              )
            } else {
              connections.map { conn =>
                div(
                  cls("flex items-center justify-between py-2 border-b border-gray-100 dark:border-gray-700 last:border-b-0"),
                  div(
                    cls("text-sm font-medium text-gray-900 dark:text-white"),
                    span("Channel: "),
                    span(cls("text-gray-500 dark:text-gray-400"), conn.channelId)
                  ),
                  button(
                    cls("text-red-600 hover:text-red-700 dark:text-red-400 dark:hover:text-red-300 text-sm font-medium"),
                    onClick --> { (_: dom.Event) =>
                      UserStore.revokeYoutubeConnection(conn.channelId)
                    },
                    children <-- Var("Revoke").signal.map { txt =>
                      Seq(span(txt))
                    }
                  )
                )
              }
            }
          }
        ),

        // YouTube ad configuration
        div(
          cls("bg-white dark:bg-gray-800 rounded-lg shadow-sm border border-gray-200 dark:border-gray-700 p-6"),
          h2(
            cls("text-xl font-semibold mb-4"),
            "YouTube Ad Configuration"
          ),
          div(
            cls("text-sm text-gray-500 dark:text-gray-400 mb-6"),
            "Configure how ArchieMate promotes your YouTube videos during Twitch ad breaks. " +
            "Ads are sent to active Twitch chats when an ad break starts."
          ),
          div(
            cls("space-y-6"),
            // Ad mode selection
            div(
              cls("space-y-2"),
              label(
                cls("block text-sm font-medium text-gray-700 dark:text-gray-300"),
                "Ad Message Source"
              ),
              div(
                cls("flex gap-4"),
                label(
                  cls("flex items-center gap-2 cursor-pointer"),
                  input(
                    cls("w-4 h-4 text-indigo-600 border-gray-300 focus:ring-indigo-500"),
                    checked <-- UserStore.youtubeAdsConfig.signal.map(_.adMode == "title"),
                    onChange --> { (e: dom.Event) =>
                      val target = e.target.asInstanceOf[dom.HTMLInputElement]
                      if (target.checked) {
                        UserStore.saveYoutubeAdsConfig("title", UserStore.youtubeAdsConfig.now().adIntervalSeconds, UserStore.youtubeAdsConfig.now().adDescriptionParagraph)
                      }
                    }
                  ),
                  span(cls("text-sm text-gray-900 dark:text-white"), "Video Title")
                ),
                label(
                  cls("flex items-center gap-2 cursor-pointer"),
                  input(
                    cls("w-4 h-4 text-indigo-600 border-gray-300 focus:ring-indigo-500"),
                    checked <-- UserStore.youtubeAdsConfig.signal.map(_.adMode == "description"),
                    onChange --> { (e: dom.Event) =>
                      val target = e.target.asInstanceOf[dom.HTMLInputElement]
                      if (target.checked) {
                        UserStore.saveYoutubeAdsConfig("description", UserStore.youtubeAdsConfig.now().adIntervalSeconds, UserStore.youtubeAdsConfig.now().adDescriptionParagraph)
                      }
                    }
                  ),
                  span(cls("text-sm text-gray-900 dark:text-white"), "Description Paragraph")
                )
              )
            ),
            // Ad interval
            div(
              cls("space-y-2"),
              label(
                cls("block text-sm font-medium text-gray-700 dark:text-gray-300"),
                "Minimum Interval Between Ads (seconds)"
              ),
              input(
                cls("block w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm px-3 py-2"),
                value <-- UserStore.youtubeAdsConfig.signal.map(_.adIntervalSeconds.toString),
                onChange --> { (e: dom.Event) =>
                  val target = e.target.asInstanceOf[dom.HTMLInputElement]
                  val interval = target.value.toIntOption.getOrElse(300)
                  UserStore.saveYoutubeAdsConfig(UserStore.youtubeAdsConfig.now().adMode, interval, UserStore.youtubeAdsConfig.now().adDescriptionParagraph)
                }
              ),
              p(cls("text-xs text-gray-500 dark:text-gray-400 mt-1"), "Minimum 60 seconds, maximum 3600 seconds")
            ),
            // Description paragraph (conditional)
            children <-- UserStore.youtubeAdsConfig.signal.map { config =>
              if (config.adMode == "description") {
                Seq(
                  div(
                    cls("space-y-2"),
                    label(
                      cls("block text-sm font-medium text-gray-700 dark:text-gray-300"),
                      "Description Paragraph Index (0-based)"
                    ),
                    input(
                      cls("block w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 dark:bg-gray-700 dark:border-gray-600 dark:text-white sm:text-sm px-3 py-2"),
                      value <-- UserStore.youtubeAdsConfig.signal.map(_.adDescriptionParagraph.toString),
                      onChange --> { (e: dom.Event) =>
                        val target = e.target.asInstanceOf[dom.HTMLInputElement]
                        val paragraph = target.value.toIntOption.getOrElse(0)
                        UserStore.saveYoutubeAdsConfig(UserStore.youtubeAdsConfig.now().adMode, UserStore.youtubeAdsConfig.now().adIntervalSeconds, paragraph)
                      }
                    ),
                    p(cls("text-xs text-gray-500 dark:text-gray-400 mt-1"), "Index of the paragraph to use from the video description (0 = first paragraph)")
                  )
                )
              } else {
                Seq()
              }
            }
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
              p(cls("font-medium mb-1"), "About platform connections"),
              p(
                cls("mb-2"),
                "ArchieMate connects to streaming platforms to manage your chat and stream. " +
                "Your access tokens are stored securely and used only for platform operations. " +
                "You can connect one primary YouTube channel and multiple secondary YouTube channels for video queries."
              )
            )
          )
        )
      )
    )
  }
}
