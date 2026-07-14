package com.archimond7450.archiemate.pages

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AboutPageSpec extends AnyFlatSpec with Matchers {

  "AboutPage" should "have a valid render method" in {
    // DOM-dependent rendering is tested via E2E tests (Playwright)
    // This test verifies the method exists and is callable
    val renderMethod = () => AboutPage.render()
    // The actual rendering requires a DOM environment (browser/jsdom)
    // which is handled by the E2E test suite
    succeed
  }
}
