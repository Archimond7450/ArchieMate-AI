package com.archimond7450.archiemate.components

import com.archimond7450.archiemate.{DashboardPage, UserStore, Page}
import com.raquo.laminar.api.L.{*, given}
import com.raquo.waypoint.Router
import org.scalajs.dom

/** User avatar with dropdown menu (Dashboard + Logout).
  *
  * Shows the Twitch avatar and display name when logged in.
  * Renders nothing when not logged in.
  */
object UserMenu {

  def render(router: Router[Page]): Element = {
    val menuOpen = Var(false)

    // Close menu when clicking outside
    val closeMenu: dom.Event => Unit = _ => menuOpen.set(false)

    // Use a wrapper div for conditional display of the dropdown
    div(
      cls("relative"),
      // Avatar button
      button(
        cls(
          "flex items-center gap-2 p-1 rounded-full focus:outline-none focus:ring-2 focus:ring-indigo-500",
          "hover:ring-2 hover:ring-indigo-400 transition-shadow"
        ),
        onClick --> { (_: dom.Event) =>
          menuOpen.update(!_)
        },
        // Avatar image
        img(
          cls(
            "w-9 h-9 rounded-full border border-gray-200 dark:border-gray-700",
            "bg-gray-100 dark:bg-gray-800"
          ),
          src <-- UserStore.avatarUrlVar.signal.map { url =>
            if (url.nonEmpty) url else "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Ccircle cx='50' cy='50' r='50' fill='%23d1d5db'/%3E%3C/svg%3E"
          },
          alt <-- UserStore.displayNameVar.signal.map(d => s"Profile of $d"),
          width("36"),
          height("36")
        ),
        // Dropdown menu - controlled by a wrapper div
        children <-- menuOpen.signal.map { open =>
          if (!open) Seq.empty else Seq(renderDropdown(router, closeMenu))
        }
      )
    )
  }

  private def renderDropdown(router: Router[Page], closeMenu: dom.Event => Unit): Element = {
    div(
      cls(
        "absolute right-0 mt-2 w-48 rounded-md shadow-lg bg-white dark:bg-gray-800",
        "border border-gray-200 dark:border-gray-700 ring-1 ring-black ring-opacity-5",
        "origin-top-right z-50"
      ),
      // Dashboard link
      div(
        cls("py-1"),
        a(
          cls(
            "block px-4 py-2 text-sm text-gray-700 dark:text-gray-300",
            "hover:bg-gray-100 dark:hover:bg-gray-700"
          ),
          router.navigateTo(DashboardPage),
          "Dashboard"
        )
      ),
      // Logout
      div(
        cls("py-1 border-t border-gray-200 dark:border-gray-700"),
        button(
          cls(
            "block w-full text-left px-4 py-2 text-sm text-red-600 dark:text-red-400",
            "hover:bg-gray-100 dark:hover:bg-gray-700"
          ),
          onClick --> { (_: dom.Event) =>
            UserStore.logout()
          },
          "Log out"
        )
      )
    )
  }
}
