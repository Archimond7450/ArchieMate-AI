package com.archimond7450.archiemate.twitch.eventsub

import scala.concurrent.duration.FiniteDuration

/** Configuration for Twitch EventSub WebHooks. */
case class EventSubConfig(
    webhookSecret: String,
    callbackPath: String,
    leaseDuration: FiniteDuration,
    helixBaseUrl: String
)

object EventSubConfig {

  val DefaultHelixBaseUrl = "https://api.twitch.tv/helix"
  val DefaultLeaseDuration: FiniteDuration = scala.concurrent.duration.Duration(604800, scala.concurrent.duration.SECONDS)

  def apply(
      webhookSecret: String,
      callbackPath: String,
      leaseDuration: Option[FiniteDuration] = None,
      helixBaseUrl: String = DefaultHelixBaseUrl
  ): EventSubConfig =
    EventSubConfig(
      webhookSecret = webhookSecret,
      callbackPath = callbackPath,
      leaseDuration = leaseDuration.getOrElse(DefaultLeaseDuration),
      helixBaseUrl = helixBaseUrl
    )
}
