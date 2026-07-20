package com.archimond7450.archiemate.pages

import com.archimond7450.archiemate.UserStore
import com.raquo.laminar.api.L.{*, given}

/** Dummy dashboard page accessible only to logged-in users. */
object DashboardPage {

  def render(): Element = {
    sectionTag(
      cls("py-16 px-4 sm:px-6 lg:px-8"),
      div(
        cls("max-w-4xl mx-auto"),
        h1(
          cls("text-4xl font-bold tracking-tight sm:text-5xl lg:text-6xl mb-8"),
          "Dashboard"
        ),
        div(
          cls("bg-white dark:bg-gray-800 rounded-lg shadow-sm border border-gray-200 dark:border-gray-700 p-6"),
          h2(
            cls("text-xl font-semibold mb-4"),
            "Welcome"
          ),
          p(
            cls("text-gray-600 dark:text-gray-300"),
            "You're logged in as: ",
            strong(
              className <-- UserStore.displayNameVar.signal.map(d => if (d.nonEmpty) "font-semibold text-indigo-600 dark:text-indigo-400" else ""),
              child <-- UserStore.displayNameVar.signal.map(d => if (d.nonEmpty) d else "Unknown")
            )
          ),
          div(
            cls("mt-6"),
            img(
              cls("w-24 h-24 rounded-full border-2 border-gray-200 dark:border-gray-700"),
              src <-- UserStore.avatarUrlVar.signal.map { url =>
                if (url.nonEmpty) url else "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Ccircle cx='50' cy='50' r='50' fill='%23e5e7eb'/%3E%3C/svg%3E"
              },
              alt("Profile")
            )
          ),
          div(
            cls("mt-8 pt-6 border-t border-gray-200 dark:border-gray-700"),
            p(
              cls("text-sm text-gray-500 dark:text-gray-400"),
              "This is a placeholder dashboard. More features coming soon!"
            )
          )
        )
      )
    )
  }
}
