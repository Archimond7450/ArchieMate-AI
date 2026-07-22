package com.archimond7450.archiemate.components

import com.archimond7450.archiemate.{AdministratorPage, DarkMode, DashboardPage, HomePage, AboutPage, DocsPage, Page, UserStore}
import com.raquo.laminar.api.L.{*, given}
import com.raquo.waypoint.Router
import org.scalajs.dom

object Header {

  sealed trait PageLink {
    def label: String
    def page: Page
  }

  case object HomeLink extends PageLink {
    override val label: String = "Home"
    override val page: Page = HomePage
  }

  case object AboutLink extends PageLink {
    override val label: String = "About"
    override val page: Page = AboutPage
  }

  case object DocsLink extends PageLink {
    override val label: String = "Docs"
    override val page: Page = DocsPage
  }

  case object DashboardLink extends PageLink {
    override val label: String = "Dashboard"
    override val page: Page = DashboardPage
  }

  val pages: List[PageLink] = List(HomeLink, AboutLink, DocsLink, DashboardLink)

  /** SVG icon for sun (light mode). */
  private def sunSvg: Element = {
    svg.svg(
      svg.className("h-6 w-6"),
      svg.xmlns("http://www.w3.org/2000/svg"),
      svg.fill("none"),
      svg.viewBox("0 0 24 24"),
      svg.stroke("currentColor"),
      svg.strokeLineCap("round"),
      svg.strokeLineJoin("round"),
      svg.strokeWidth("2"),
      svg.path(
        svg.d("M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z")
      )
    )
  }

  /** SVG icon for moon (dark mode). */
  private def moonSvg: Element = {
    svg.svg(
      svg.className("h-6 w-6"),
      svg.xmlns("http://www.w3.org/2000/svg"),
      svg.fill("none"),
      svg.viewBox("0 0 24 24"),
      svg.stroke("currentColor"),
      svg.strokeLineCap("round"),
      svg.strokeLineJoin("round"),
      svg.strokeWidth("2"),
      svg.path(
        svg.d("M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z")
      )
    )
  }

  /** Hamburger menu SVG icon. */
  private def hamburgerSvg: Element = {
    svg.svg(
      svg.className("block h-6 w-6"),
      svg.xmlns("http://www.w3.org/2000/svg"),
      svg.fill("none"),
      svg.viewBox("0 0 24 24"),
      svg.stroke("currentColor"),
      svg.strokeLineCap("round"),
      svg.strokeLineJoin("round"),
      svg.strokeWidth("2"),
      svg.path(
        svg.d("M4 6h16M4 12h16M4 18h16")
      )
    )
  }

  /** Link to the admin panel. */
  private def adminLink(router: Router[Page]): Element = {
    val isActive = router.currentPageSignal.map {
      case AdministratorPage => true
      case _ => false
    }
    a(
      cls <-- isActive.map {
        case true => "px-3 py-2 text-sm font-semibold rounded-md text-indigo-600 dark:text-indigo-400 bg-indigo-50 dark:bg-indigo-900/30"
        case false => "px-3 py-2 text-sm font-semibold rounded-md text-gray-700 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-800 hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors"
      },
      router.navigateTo(AdministratorPage),
      "Admin"
    )
  }

  /** Close (X) SVG icon for the mobile menu. */
  private def closeSvg: Element = {
    svg.svg(
      svg.className("block h-6 w-6"),
      svg.xmlns("http://www.w3.org/2000/svg"),
      svg.fill("none"),
      svg.viewBox("0 0 24 24"),
      svg.stroke("currentColor"),
      svg.strokeLineCap("round"),
      svg.strokeLineJoin("round"),
      svg.strokeWidth("2"),
      svg.path(
        svg.d("M6 18L18 6M6 6l12 12")
      )
    )
  }

  def render(router: Router[Page]): Element = {
    val mobileMenuOpen = Var(false)

    def renderMobileNavItem(link: PageLink): Element = {
      val isActive = router.currentPageSignal.map {
        case HomePage => link.page == HomePage
        case AboutPage => link.page == AboutPage
        case DocsPage => link.page == DocsPage
        case DashboardPage => link.page == DashboardPage
        case AdministratorPage => link.page == AdministratorPage
      }
      a(
        cls <-- isActive.map {
          case true => "px-3 py-2 text-sm font-semibold rounded-md text-indigo-600 dark:text-indigo-400 bg-indigo-50 dark:bg-indigo-900/30"
          case false => "px-3 py-2 text-sm font-semibold rounded-md text-gray-700 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-800 hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors"
        },
        router.navigateTo(link.page),
        link.label
      )
    }

    div(
      cls("bg-white dark:bg-gray-900 border-b border-gray-200 dark:border-gray-700"),
      div(
        cls("max-w-6xl mx-auto px-4 sm:px-6 lg:px-8"),
        div(
          cls("flex justify-between items-center h-16"),
          // Logo + desktop nav (desktop nav hidden on mobile)
          div(
            cls("flex items-center gap-8"),
            a(
              cls("text-xl font-bold text-indigo-600 dark:text-indigo-400 hover:text-indigo-500 transition-colors"),
              href := "/",
              "ArchieMate"
            ),
            navTag(
              cls("hidden md:flex items-center gap-1"),
              pages.map { link =>
                val isActive = router.currentPageSignal.map {
                  case HomePage => link.page == HomePage
                  case AboutPage => link.page == AboutPage
                  case DocsPage => link.page == DocsPage
                  case DashboardPage => link.page == DashboardPage
                  case AdministratorPage => link.page == AdministratorPage
                }
                a(
                  cls <-- isActive.map {
                    case true => "px-3 py-2 text-sm font-semibold rounded-md text-indigo-600 dark:text-indigo-400 bg-indigo-50 dark:bg-indigo-900/30"
                    case false => "px-3 py-2 text-sm font-semibold rounded-md text-gray-700 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-800 hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors"
                  },
                  router.navigateTo(link.page),
                  link.label
                )
              }
            )
          ),
          // Dark mode toggle + user menu / login button + mobile menu button
          div(
            cls("flex items-center gap-2"),
            // Dark mode toggle (always visible)
            button(
              cls(
                "p-2 rounded-md text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors",
                "focus:outline-none focus:ring-2 focus:ring-indigo-500"
              ),
              aria.label("Toggle dark mode"),
              onClick --> { _ =>
                DarkMode.toggle()
              },
              children <-- DarkMode.darkModeVar.signal.map { isDark =>
                if (isDark) {
                  Seq(sunSvg)
                } else {
                  Seq(moonSvg)
                }
              }
            ),
            // User menu (desktop, logged in) or login button (desktop, not logged in)
            div(
              cls("hidden md:flex items-center gap-2"),
              children <-- UserStore.isLoggedIn.signal.map { loggedIn =>
                if (loggedIn) {
                  if (UserStore.isAdmin.now()) {
                    Seq(UserMenu.render(router), adminLink(router))
                  } else {
                    Seq(UserMenu.render(router))
                  }
                } else {
                  Seq(
                    a(
                      cls(
                        "px-3 py-2 text-sm font-semibold rounded-md",
                        "text-indigo-600 dark:text-indigo-400",
                        "bg-indigo-50 dark:bg-indigo-900/30",
                        "hover:bg-indigo-100 dark:hover:bg-indigo-900/50",
                        "transition-colors"
                      ),
                      href("/auth/twitch/login"),
                      "Log in"
                    )
                  )
                }
              }
            ),
            // Hamburger menu button (hidden on desktop)
            button(
              cls(
                "p-2 rounded-md text-gray-600 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors",
                "focus:outline-none focus:ring-2 focus:ring-indigo-500 md:hidden"
              ),
              aria.label("Toggle mobile menu"),
              aria.expanded <-- mobileMenuOpen.signal,
              onClick --> { _ =>
                mobileMenuOpen.update(!_)
              },
              children <-- mobileMenuOpen.signal.map {
                case true  => Seq(closeSvg)
                case false => Seq(hamburgerSvg)
              }
            )
          )
        )
      ),
      // Mobile menu panel (hidden on desktop)
      div(
        cls("md:hidden"),
        div(
          cls("px-2 pt-2 pb-3 space-y-1 sm:px-3"),
          children <-- mobileMenuOpen.signal.map {
            case true =>
              val pageLinks = pages.map(renderMobileNavItem)
              // Login link in mobile menu when not logged in
              if (!UserStore.isLoggedIn.now()) {
                Seq(
                  a(
                    cls("px-3 py-2 text-sm font-semibold rounded-md text-indigo-600 dark:text-indigo-400 bg-indigo-50 dark:bg-indigo-900/30"),
                    href("/auth/twitch/login"),
                    "Log in"
                  )
                ) ++ pageLinks
              } else if (UserStore.isAdmin.now()) {
                // Dashboard + Admin links in mobile menu when logged in as admin
                pageLinks ++ Seq(
                  a(
                    cls("px-3 py-2 text-sm font-semibold rounded-md text-gray-700 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-800 hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors"),
                    href("/dashboard"),
                    "Dashboard"
                  ),
                  a(
                    cls("px-3 py-2 text-sm font-semibold rounded-md text-gray-700 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-800 hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors"),
                    href("/admin"),
                    "Administrator"
                  ),
                  button(
                    cls("w-full text-left px-3 py-2 text-sm font-semibold rounded-md text-red-600 dark:text-red-400 hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors"),
                    onClick --> { (_: dom.Event) =>
                      mobileMenuOpen.set(false)
                      UserStore.logout()
                    },
                    "Log out"
                  )
                )
              } else {
                // Dashboard link in mobile menu when logged in
                pageLinks ++ Seq(
                  a(
                    cls("px-3 py-2 text-sm font-semibold rounded-md text-gray-700 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-800 hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors"),
                    href("/dashboard"),
                    "Dashboard"
                  ),
                  button(
                    cls("w-full text-left px-3 py-2 text-sm font-semibold rounded-md text-red-600 dark:text-red-400 hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors"),
                    onClick --> { (_: dom.Event) =>
                      mobileMenuOpen.set(false)
                      UserStore.logout()
                    },
                    "Log out"
                  )
                )
              }
            case false => Seq.empty
          }
        )
      )
    )
  }
}
