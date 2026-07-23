package com.archimond7450.archiemate.twitch

import com.archimond7450.archiemate.settings.{AppConfig, TwitchIrcConfig}
import com.archimond7450.archiemate.twitch.eventsub.EventSubConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class EventSubConfigSpec extends AnyWordSpecLike with Matchers {

  "EventSubConfig" should {

    "use defaults when only webhookSecret and callbackPath are provided" in {
      val config = EventSubConfig(
        webhookSecret = "my-secret",
        callbackPath = "/api/v1/eventsub/webhook"
      )

      config.webhookSecret shouldEqual "my-secret"
      config.callbackPath shouldEqual "/api/v1/eventsub/webhook"
      config.leaseDuration shouldEqual EventSubConfig.DefaultLeaseDuration
      config.helixBaseUrl shouldEqual EventSubConfig.DefaultHelixBaseUrl
    }

    "accept a custom lease duration" in {
      val config = EventSubConfig(
        webhookSecret = "my-secret",
        callbackPath = "/webhook",
        leaseDuration = Some(3600.seconds)
      )

      config.leaseDuration shouldEqual 3600.seconds
    }

    "accept a custom helix base URL" in {
      val config = EventSubConfig(
        webhookSecret = "my-secret",
        callbackPath = "/webhook",
        helixBaseUrl = "https://custom.twitch.api/helix"
      )

      config.helixBaseUrl shouldEqual "https://custom.twitch.api/helix"
    }

    "accept all parameters" in {
      val config = EventSubConfig(
        webhookSecret = "secret",
        callbackPath = "/cb",
        leaseDuration = Some(120.minutes),
        helixBaseUrl = "https://example.com/helix"
      )

      config.webhookSecret shouldEqual "secret"
      config.callbackPath shouldEqual "/cb"
      config.leaseDuration shouldEqual 120.minutes
      config.helixBaseUrl shouldEqual "https://example.com/helix"
    }
  }

  "AppConfig.eventSub" should {

    "load from application.conf with default values" in {
      val config = ConfigFactory.load()
      val appConfig = AppConfig(config)

      val eventSub = appConfig.eventSub
      eventSub.webhookSecret shouldEqual ""
      eventSub.callbackPath shouldEqual "/api/v1/eventsub/webhook"
      eventSub.leaseDuration shouldEqual EventSubConfig.DefaultLeaseDuration
      eventSub.helixBaseUrl shouldEqual EventSubConfig.DefaultHelixBaseUrl
    }

    "respect environment variable overrides" in {
      val overriden = ConfigFactory.parseString("""
        archiemate.eventsub.webhook-secret = "env-secret-123"
        archiemate.eventsub.callback-path = "/api/v2/eventsub"
        archiemate.eventsub.lease-duration = "3600s"
        archiemate.eventsub.helix-base-url = "https://override.example.com/helix"
      """).withFallback(ConfigFactory.load())

      val appConfig = AppConfig(overriden)
      val eventSub = appConfig.eventSub

      eventSub.webhookSecret shouldEqual "env-secret-123"
      eventSub.callbackPath shouldEqual "/api/v2/eventsub"
      eventSub.leaseDuration shouldEqual 3600.seconds
      eventSub.helixBaseUrl shouldEqual "https://override.example.com/helix"
    }

    "use default lease-duration when not overridden" in {
      val config = ConfigFactory.parseString("""
        archiemate.eventsub.webhook-secret = "test"
        archiemate.eventsub.callback-path = "/test"
      """).withFallback(ConfigFactory.load())

      val appConfig = AppConfig(config)
      appConfig.eventSub.leaseDuration shouldEqual EventSubConfig.DefaultLeaseDuration
    }

    "use default helix-base-url when not overridden" in {
      val config = ConfigFactory.parseString("""
        archiemate.eventsub.webhook-secret = "test"
        archiemate.eventsub.callback-path = "/test"
      """).withFallback(ConfigFactory.load())

      val appConfig = AppConfig(config)
      appConfig.eventSub.helixBaseUrl shouldEqual EventSubConfig.DefaultHelixBaseUrl
    }
  }
}
