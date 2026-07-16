package com.archimond7450.archiemate.settings

import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers

class AppConfigSpec extends AnyWordSpecLike with Matchers {

  "AppConfig should load from default application.conf" should {
    "return correct defaults" in {
      val config = ConfigFactory.load()
      val appConfig = AppConfig(config)

      appConfig.server.host shouldEqual "0.0.0.0"
      appConfig.server.port shouldEqual 8080
      appConfig.server.apiVersion shouldEqual "v1"
      appConfig.database.driver shouldEqual "org.postgresql.Driver"
      appConfig.twitch.clientId shouldEqual ""
      appConfig.twitch.clientSecret shouldEqual ""
      appConfig.twitch.redirectUriPostfix shouldEqual ""
    }

    "respect environment variable overrides" in {
      val overriden = ConfigFactory.parseString("""
        archiemate.server.host = "127.0.0.1"
        archiemate.server.port = 9090
      """).withFallback(ConfigFactory.load())

      val appConfig = AppConfig(overriden)
      appConfig.server.host shouldEqual "127.0.0.1"
      appConfig.server.port shouldEqual 9090
    }
  }
}
