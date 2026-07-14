package com.archimond7450.archiemate.components

import com.archimond7450.archiemate.VersionInfo
import org.scalajs.dom
import scala.scalajs.js.Date

object Footer {

  private val currentYear: String = new Date().getFullYear().toString

  private val aiModelName: String = "qwen/qwen3.6-35b-a3b"

  private def createEl(tag: String): dom.HTMLElement = {
    dom.document.createElement(tag).asInstanceOf[dom.HTMLElement]
  }

  def render(): dom.HTMLElement = {
    val el = createEl("footer")
    el.className = "bg-gray-100 dark:bg-gray-800 border-t border-gray-200 dark:border-gray-700"

    val container = createEl("div")
    container.className = "max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8"

    val row = createEl("div")
    row.className = "flex flex-col md:flex-row justify-between items-center gap-4 text-sm"

    // Made with section
    val madeWith = createEl("div")
    madeWith.className = "flex items-center gap-1 text-gray-600 dark:text-gray-400"
    val heart = createEl("span")
    heart.className = "text-red-500"
    heart.textContent = "\u2764\uFE0F"
    madeWith.appendChild(heart)
    madeWith.appendChild(dom.document.createTextNode(
      "Made with ❤️ using Scala 3.6.4, Circe 0.14.14, Scala.js 1.18.2, Laminar 17.2.1 & Tailwind 3.4.17"
    ))

    // Copyright section
    val copyright = createEl("div")
    copyright.className = "text-gray-600 dark:text-gray-400"
    val twitchLink = createEl("a").asInstanceOf[dom.HTMLAnchorElement]
    twitchLink.href = "https://twitch.tv/archimond7450"
    twitchLink.target = "_blank"
    twitchLink.rel = "noopener noreferrer"
    twitchLink.className = "hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors"
    twitchLink.textContent = "© 2022 - " + currentYear + " Archimond7450 & " + aiModelName + " using "
    copyright.appendChild(twitchLink)

    // Version section
    val version = createEl("div")
    version.className = "text-gray-500 dark:text-gray-500"
    version.textContent = "Version " + VersionInfo.version + " built at " + VersionInfo.builtAt

    row.appendChild(madeWith)
    row.appendChild(copyright)
    row.appendChild(version)
    container.appendChild(row)
    el.appendChild(container)

    el
  }
}
