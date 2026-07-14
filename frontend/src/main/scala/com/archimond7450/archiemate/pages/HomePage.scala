package com.archimond7450.archiemate.pages

import com.raquo.laminar.api.L._

object HomePage {

  def render(): Element = {
    sectionTag(
      cls("py-16 px-4 sm:px-6 lg:px-8"),
      div(
        cls("max-w-4xl mx-auto text-center"),
        h1(
          cls("text-4xl font-bold tracking-tight sm:text-5xl lg:text-6xl"),
          "Welcome to ArchieMate"
        ),
        p(
          cls("mt-6 text-lg leading-8 text-gray-600 dark:text-gray-300"),
          "Your intelligent chatbot companion for live streaming."
        ),
        div(
          cls("mt-10 flex items-center justify-center gap-x-6"),
          a(
            cls("rounded-md bg-indigo-600 px-3.5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600 transition-colors"),
            href := "/about",
            "Learn more"
          )
        )
      )
    )
  }
}
