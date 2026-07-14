package com.archimond7450.archiemate.components

import com.archimond7450.archiemate.VersionInfo
import com.raquo.laminar.api.L.{*, given}

object Footer {

  private val currentYear: String = new scala.scalajs.js.Date().getFullYear().toString
  private val aiModelName: String = "qwen/qwen3.6-35b-a3b"

  def render(): Element = {
    footerTag(
      cls("bg-gray-100 dark:bg-gray-800 border-t border-gray-200 dark:border-gray-700"),
      div(
        cls("max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8"),
        div(
          cls("flex flex-col md:flex-row justify-between items-center gap-4 text-sm"),
          // Made with section
          div(
            cls("flex items-center gap-1 text-gray-600 dark:text-gray-400"),
            span("Made with ❤️ using Scala 3.6.4, Circe 0.14.14, Scala.js 1.18.2, Laminar 17.2.1 & Tailwind 3.4.17")
          ),
          // Copyright section
          div(
            cls("text-gray-600 dark:text-gray-400"),
            s"© 2022 - $currentYear ",
            a(
              cls("hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors"),
              href("https://twitch.tv/archimond7450"),
              target("_blank"),
              rel("noopener noreferrer"),
              "Archimond7450"
            ),
            s" & pi using $aiModelName"
          ),
          // Version section
          div(
            cls("text-gray-500 dark:text-gray-500"),
            s"Version ${VersionInfo.version} built at ${VersionInfo.builtAt}"
          )
        )
      )
    )
  }
}
