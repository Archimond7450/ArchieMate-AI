package com.archimond7450.archiemate.pages

import com.archimond7450.archiemate.UserStore
import com.raquo.laminar.api.L.{*, given}

/** Dummy administrator page accessible only to admin users. */
object AdministratorPage {

  def render(): Element = {
    sectionTag(
      cls("py-16 px-4 sm:px-6 lg:px-8"),
      div(
        cls("max-w-4xl mx-auto"),
        h1(
          cls("text-4xl font-bold tracking-tight sm:text-5xl lg:text-6xl mb-8"),
          "Administrator"
        ),
        div(
          cls("bg-white dark:bg-gray-800 rounded-lg shadow-sm border border-gray-200 dark:border-gray-700 p-6"),
          h2(
            cls("text-xl font-semibold mb-4"),
            "Admin Panel"
          ),
          p(
            cls("text-gray-600 dark:text-gray-300"),
            "This is a placeholder admin panel. More features coming soon!"
          )
        )
      )
    )
  }
}
