package com.archimond7450.archiemate

import com.raquo.laminar.api.L.{*, given}
import com.raquo.waypoint._
import com.archimond7450.archiemate.components.{Footer, Header}
import com.archimond7450.archiemate.pages.{AboutPage => PageAboutPage, DashboardPage => PageDashboardPage, DocsPage => PageDocsPage, HomePage => PageHomePage, AdministratorPage => PageAdministratorPage}
import org.scalajs.dom
import scala.scalajs.js.annotation.JSExportTopLevel

// Page type hierarchy for header/footer grouping
sealed trait Page
case object HomePage extends Page
case object AboutPage extends Page
case object DocsPage extends Page
case object DashboardPage extends Page
case object AdministratorPage extends Page

private val allPages: List[Page] = List(HomePage, AboutPage, DocsPage, DashboardPage, AdministratorPage)

object App {

  private val serializePage: Page => String = {
    case HomePage      => "/"
    case AboutPage     => "/about"
    case DocsPage      => "/docs"
    case DashboardPage         => "/dashboard"
    case AdministratorPage   => "/admin"
  }

  private val router: Router[Page] = Router[Page](
    routes = List(
      Route.static(AboutPage, root / "about"),
      Route.static(DocsPage, root / "docs"),
      Route.static(DashboardPage, root / "dashboard"),
      Route.static(AdministratorPage, root / "admin"),
      Route.static(HomePage, root)
    ),
    getPageTitle = {
      case HomePage              => "Home"
      case AboutPage             => "About"
      case DocsPage              => "Docs"
      case DashboardPage         => "Dashboard"
      case AdministratorPage   => "Administrator"
    },
    serializePage = serializePage,
    deserializePage = url =>
      allPages.find(p => serializePage(p) == url).getOrElse(HomePage),
    routeFallback = _ => HomePage,
    deserializeFallback = _ => HomePage,
  )

  @JSExportTopLevel("ArchieMateApp")
  def mount(): Unit = {
    // Initialize dark mode from localStorage or system preference
    DarkMode.init()
    // Check if user is logged in (backend reads HTTP-only cookie)
    UserStore.checkLogin()
    renderTo("#app")
  }

  private def renderTo(selector: String): Unit = {
    val headerEl = Header.render(router)

    // Use SplitRender for clean page rendering
    val pageSplitter = SplitRender[Page, Element](router.currentPageSignal)
      .collectStatic(HomePage) { PageHomePage.render() }
      .collectStatic(AboutPage) { PageAboutPage.render() }
      .collectStatic(DocsPage) { PageDocsPage.render() }
      .collectStatic(DashboardPage) { PageDashboardPage.render() }
      .collectStatic(AdministratorPage) { PageAdministratorPage.render() }

    val contentEl = div(
      className := "flex-grow",
      child <-- pageSplitter.signal
    )

    val footerEl = Footer.render()

    val rootEl = div(
      className := "min-h-screen flex flex-col",
      headerEl,
      contentEl,
      footerEl
    )

    val container = dom.document.querySelector(selector)
    if (container != null) {
      render(container, rootEl)
    }
  }
}
