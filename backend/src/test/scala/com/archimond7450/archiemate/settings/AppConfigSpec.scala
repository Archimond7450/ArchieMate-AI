package com.archimond7450.archiemate.settings

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AppConfigSpec extends AnyFlatSpec with Matchers {

  "AppConfig" should "load from default application.conf" in {
    val config = ConfigFactory.load()
    val appConfig = AppConfig(config)

    appConfig.server.host shouldBe "0.0.0.0"
    appConfig.server.port shouldBe 8080
    appConfig.server.apiVersion shouldBe "v1"
    appConfig.database.driver shouldBe "org.postgresql.Driver"
  }

  it should "respect environment variable overrides" in {
    val overriden = ConfigFactory.parseString("""
      archiemate.server.host = "127.0.0.1"
      archiemate.server.port = 9090
    """).withFallback(ConfigFactory.load())

    val appConfig = AppConfig(overriden)
    appConfig.server.host shouldBe "127.0.0.1"
    appConfig.server.port shouldBe 9090
  }
}
