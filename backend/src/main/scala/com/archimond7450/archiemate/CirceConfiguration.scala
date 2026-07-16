package com.archimond7450.archiemate

import io.circe.derivation.Configuration

/** Common circe configuration for the ArchieMate project.
  *
  * Provides derived decoders and encoders for case classes using standard
  * field names (kebab-case by default in Pekko).
  */
object CirceConfiguration {

  /** Default configuration for Pekko serialization (kebab-case field names). */
  given pekkoConfiguration: Configuration = Configuration.default.withSnakeCaseMemberNames

  /** Per-channel configuration for external wire formats. */
  object ChannelConfiguration {
    // Each channel (Twitch, Kick, YouTube) gets its own circe config
  }
}
