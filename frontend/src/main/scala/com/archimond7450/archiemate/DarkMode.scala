package com.archimond7450.archiemate

import com.raquo.laminar.api.L.{*, given}
import org.scalajs.dom

object DarkMode {

  /** Reactive dark mode state, initialized from localStorage or system preference. */
  private val _darkModeVar: Var[Boolean] = Var(initialValue())

  def darkModeVar: Var[Boolean] = _darkModeVar

  /** Initialize dark mode from localStorage or system preference. */
  def init(): Unit = {
    // Watch for system preference changes (only when no explicit preference is set)
    val mediaQuery = dom.window.matchMedia("(prefers-color-scheme: dark)")
    mediaQuery.addEventListener("change", _ => {
      val stored = dom.window.localStorage.getItem("theme")
      if (stored == null) {
        _darkModeVar.set(mediaQuery.matches)
      }
    })
  }

  /** Toggle dark mode and persist preference to localStorage. */
  def toggle(): Unit = {
    val newValue = !_darkModeVar.now()
    _darkModeVar.set(newValue)
    apply(newValue)
  }

  /** Apply dark mode class to documentElement and persist to localStorage. */
  private def apply(isDark: Boolean): Unit = {
    val doc = dom.document.documentElement
    if (isDark) {
      doc.classList.add("dark")
      dom.window.localStorage.setItem("theme", "dark")
    } else {
      doc.classList.remove("dark")
      dom.window.localStorage.setItem("theme", "light")
    }
  }

  /** Initialize and return the initial dark mode value. */
  private def initialValue(): Boolean = {
    val stored = dom.window.localStorage.getItem("theme")
    val isDark = stored == "dark" || (stored == null && dom.window.matchMedia("(prefers-color-scheme: dark)").matches)
    apply(isDark)
    isDark
  }
}
