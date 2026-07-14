package com.archimond7450.archiemate

import com.archimond7450.archiemate.pages.HomePage
import com.archimond7450.archiemate.components.Footer
import org.scalajs.dom

object App {

  private def createEl(tag: String): dom.HTMLElement = {
    dom.document.createElement(tag).asInstanceOf[dom.HTMLElement]
  }

  def main(args: Array[String]): Unit = {
    val appEl = dom.document.getElementById("app").asInstanceOf[dom.HTMLElement]

    val wrapper = createEl("div")
    wrapper.className = "min-h-screen flex flex-col"

    val mainEl = createEl("main")
    mainEl.className = "flex-grow"
    mainEl.appendChild(HomePage.render())

    wrapper.appendChild(mainEl)
    wrapper.appendChild(Footer.render())

    appEl.appendChild(wrapper)
  }
}
