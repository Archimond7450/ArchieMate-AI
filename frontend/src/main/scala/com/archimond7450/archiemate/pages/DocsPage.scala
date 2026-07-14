package com.archimond7450.archiemate.pages

import com.raquo.laminar.api.L._

object DocsPage {

  private case class DocEntry(title: String, description: String, url: String)

  private val docs: List[DocEntry] = List(
    DocEntry("Getting Started", "Set up the development environment and run ArchieMate locally", "#getting-started"),
    DocEntry("Configuration", "Customize server, database, and AI provider settings", "#configuration"),
    DocEntry("API Reference", "REST API endpoints and WebSocket protocols", "#api-reference"),
    DocEntry("Deployment", "Deploy to production with Docker and environment setup", "#deployment"),
    DocEntry("Contributing", "How to contribute to the ArchieMate project", "#contributing")
  )

  def render(): Element = {
    sectionTag(
      cls("py-16 px-4 sm:px-6 lg:px-8"),
      div(
        cls("max-w-4xl mx-auto"),
        h1(
          cls("text-4xl font-bold tracking-tight sm:text-5xl lg:text-6xl text-center"),
          "Documentation"
        ),
        p(
          cls("mt-6 text-lg leading-8 text-gray-600 dark:text-gray-300 text-center"),
          "Everything you need to know about ArchieMate."
        ),
        div(
          cls("mt-12 grid gap-6 sm:grid-cols-2"),
          docs.map { doc =>
            a(
              cls("block p-6 rounded-lg border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 hover:border-indigo-300 dark:hover:border-indigo-600 hover:shadow-md transition-all"),
              href := doc.url,
              h2(
                cls("text-lg font-semibold text-gray-900 dark:text-white"),
                doc.title
              ),
              p(
                cls("mt-2 text-sm text-gray-600 dark:text-gray-400"),
                doc.description
              )
            )
          }
        )
      )
    )
  }
}
