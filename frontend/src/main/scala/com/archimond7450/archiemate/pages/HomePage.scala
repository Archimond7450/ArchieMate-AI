package com.archimond7450.archiemate.pages

import org.scalajs.dom

object HomePage {

  private def createEl(tag: String): dom.HTMLElement = {
    dom.document.createElement(tag).asInstanceOf[dom.HTMLElement]
  }

  def render(): dom.HTMLElement = {
    val el = createEl("section")
    el.className = "py-16 px-4 sm:px-6 lg:px-8"

    val container = createEl("div")
    container.className = "max-w-4xl mx-auto text-center"

    val heading = createEl("h1")
    heading.className = "text-4xl font-bold tracking-tight sm:text-5xl lg:text-6xl"
    heading.textContent = "Welcome to ArchieMate"

    val description = createEl("p")
    description.className = "mt-6 text-lg leading-8 text-gray-600 dark:text-gray-300"
    description.textContent = "Your intelligent chatbot companion for live streaming."

    val btnContainer = createEl("div")
    btnContainer.className = "mt-10 flex items-center justify-center gap-x-6"

    val link = createEl("a").asInstanceOf[dom.HTMLAnchorElement]
    link.className = "rounded-md bg-indigo-600 px-3.5 py-2.5 text-sm font-semibold text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600"
    link.href = "/about"
    link.textContent = "Learn more"

    btnContainer.appendChild(link)
    container.appendChild(heading)
    container.appendChild(description)
    container.appendChild(btnContainer)
    el.appendChild(container)

    el
  }
}
