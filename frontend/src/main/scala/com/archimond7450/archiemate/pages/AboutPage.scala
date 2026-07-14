package com.archimond7450.archiemate.pages

import com.raquo.laminar.api.L._

object AboutPage {

  def render(): Element = {
    sectionTag(
      cls("py-16 px-4 sm:px-6 lg:px-8"),
      div(
        cls("max-w-4xl mx-auto text-center"),
        h1(
          cls("text-4xl font-bold tracking-tight sm:text-5xl lg:text-6xl"),
          "About ArchieMate"
        ),
        p(
          cls("mt-6 text-lg leading-8 text-gray-600 dark:text-gray-300"),
          p("ArchieMate is a full-stack chatbot designed for live streaming platforms like Twitch, Kick, and YouTube."),
          p("It provides real-time interaction with your audience, helping you engage viewers with intelligent responses powered by large language models."),
          p("Built with modern, performant technologies:"),
          ul(
            cls("mt-4 space-y-2 text-left max-w-lg mx-auto"),
            li(cls("flex items-center gap-2"), "⚡ Scala 3.6.4 & Pekko"),
            li(cls("flex items-center gap-2"), "🌐 Scala.js with Laminar"),
            li(cls("flex items-center gap-2"), "🎨 Tailwind CSS"),
            li(cls("flex items-center gap-2"), "🗄️ PostgreSQL persistence"),
            li(cls("flex items-center gap-2"), "📡 WebSocket real-time updates")
          )
        ),
        div(
          cls("mt-10 flex items-center justify-center gap-x-6"),
          a(
            cls("rounded-md bg-indigo-600 px-3.5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600 transition-colors"),
            href := "/docs",
            "Read the docs"
          )
        )
      )
    )
  }
}
