package com.archimond7450.archiemate.twitch.eventsub

import io.circe.*
import io.circe.derivation.{Configuration, ConfiguredDecoder, ConfiguredEncoder}
import io.circe.syntax.EncoderOps

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Shared circe configuration for Twitch EventSub WebHooks (snake_case). */
object EventSubCirce {
  given webhookConfiguration: Configuration = Configuration.default.withSnakeCaseMemberNames
}

// ========================================================================
// Webhook Payload
// ========================================================================

/** Top-level structure of a Twitch EventSub WebHook payload. */
case class WebhookPayload(
    metadata: WebhookMetadata,
    subscription: WebhookSubscriptionInfo,
    event: Json
)
object WebhookPayload {
  given Configuration = EventSubCirce.webhookConfiguration
  given Decoder[WebhookPayload] = ConfiguredDecoder.derived
  given Encoder[WebhookPayload] = ConfiguredEncoder.derived
}

case class WebhookMetadata(
    message_id: String,
    message_type: String,
    message_timestamp: String,
    event_subscription_id: String,
    event_subscription_type: String,
    version: String
)
object WebhookMetadata {
  given Configuration = EventSubCirce.webhookConfiguration
  given Decoder[WebhookMetadata] = ConfiguredDecoder.derived
  given Encoder[WebhookMetadata] = ConfiguredEncoder.derived
}

case class WebhookSubscriptionInfo(
    `type`: String,
    version: String,
    status: String,
    condition: Map[String, String],
    cost: Int
)
object WebhookSubscriptionInfo {
  given Configuration = EventSubCirce.webhookConfiguration
  given Decoder[WebhookSubscriptionInfo] = ConfiguredDecoder.derived
  given Encoder[WebhookSubscriptionInfo] = ConfiguredEncoder.derived
}

// ========================================================================
// Event Sealed Trait
// ========================================================================

/** Base type for all Twitch EventSub WebHook events. */
sealed trait EventSubEvent {
  def userId: String
  def broadcasterId: String
  def eventTimestamp: String
}

/** Decode the event data from a webhook payload based on the subscription type. */
object EventSubEvent {
  def decode(payload: WebhookPayload): Decoder.Result[EventSubEvent] = {
    payload.subscription.`type` match {
      case "channel.chat.message" =>
        payload.event.as[ChannelChatMessageEvent]
      case "channel.chat.notification" =>
        payload.event.as[ChannelChatNotificationEvent]
      case "stream.online" =>
        payload.event.as[StreamStartEvent]
      case "stream.offline" =>
        payload.event.as[StreamEndEvent]
      case "channel.moderator.add" =>
        payload.event.as[ModeratorAddEvent]
      case "channel.moderator.remove" =>
        payload.event.as[ModeratorRemoveEvent]
      case "channel.vip.add" =>
        payload.event.as[VipAddEvent]
      case "channel.vip.remove" =>
        payload.event.as[VipRemoveEvent]
      case "channel.subscribe" =>
        payload.event.as[SubscribeEvent]
      case "channel.subscription.gift" =>
        payload.event.as[SubscriptionGiftEvent]
      case "channel.subscription.expire" =>
        payload.event.as[SubscriptionExpireEvent]
      case "channel.follow" =>
        payload.event.as[FollowEvent]
      case "channel.channel_points_custom_reward_redemption.add" =>
        payload.event.as[ChannelPointsRedemptionAddEvent]
      case "channel.raid" =>
        payload.event.as[RaidEvent]
      case "channel.cheer" =>
        payload.event.as[CheerEvent]
      case "channel.update" =>
        payload.event.as[ChannelUpdateEvent]
      case "channel.poll.begin" =>
        payload.event.as[PollBeginEvent]
      case "channel.poll.complete" =>
        payload.event.as[PollCompleteEvent]
      case "channel.prediction.begin" =>
        payload.event.as[PredictionBeginEvent]
      case "channel.prediction.complete" =>
        payload.event.as[PredictionCompleteEvent]
      case other =>
        Left(DecodingFailure(s"Unknown EventSub type: $other", List.empty))
    }
  }

  /** Get the event type string from a subscription info. */
  def eventType(sub: WebhookSubscriptionInfo): String = sub.`type`

  /** Get the subscription status. */
  def subscriptionStatus(sub: WebhookSubscriptionInfo): String = sub.status

  /** Check if a subscription is enabled. */
  def isSubscriptionEnabled(sub: WebhookSubscriptionInfo): Boolean =
    sub.status == "enabled"

  /** Get the broadcaster user ID from subscription condition. */
  def broadcasterUserId(sub: WebhookSubscriptionInfo): Option[String] =
    sub.condition.get("broadcaster_user_id")

  /** Get the moderator user ID from subscription condition. */
  def moderatorUserId(sub: WebhookSubscriptionInfo): Option[String] =
    sub.condition.get("moderator_user_id")
}

// ========================================================================
// Event Data Models (snake_case for WebHooks)
// ========================================================================

/** A chat message was sent in a channel. */
case class ChannelChatMessageEvent(
    broadcaster_user_id: String,
    broadcaster_user_login: String,
    broadcaster_user_name: String,
    chatter_user_id: String,
    chatter_user_login: String,
    chatter_user_name: String,
    message_id: String,
    message: ChatMessage,
    message_type: String,
    badges: List[Badge],
    cheer: Option[Cheer],
    color: String,
    reply: Option[Reply],
    channel_points_custom_reward_id: Option[String],
    channel_points_animation_id: Option[String]
) extends EventSubEvent {
  def userId: String = chatter_user_id
  def broadcasterId: String = broadcaster_user_id
  def eventTimestamp: String = ""
}
object ChannelChatMessageEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[ChannelChatMessageEvent] = ConfiguredDecoder.derived
  given Encoder[ChannelChatMessageEvent] = ConfiguredEncoder.derived
}

/** A chat notification event (sub, sub gift, raid, etc.). */
case class ChannelChatNotificationEvent(
    broadcaster_user_id: String,
    broadcaster_user_login: String,
    broadcaster_user_name: String,
    chatter_user_id: String,
    chatter_user_login: String,
    chatter_user_name: String,
    chatter_is_anonymous: Boolean,
    color: String,
    badges: List[Badge],
    system_message: String,
    message_id: String,
    message: ChatMessage,
    notice_type: String,
    sub: Option[NoticeSub],
    resub: Option[NoticeResub],
    sub_gift: Option[NoticeSubGift],
    community_sub_gift: Option[NoticeCommunitySubGift],
    gift_paid_upgrade: Option[NoticeGiftPaidUpgrade],
    prime_paid_upgrade: Option[NoticePrimePaidUpgrade],
    raid: Option[NoticeRaid],
    unraid: Option[NoticeUnraid],
    pay_it_forward: Option[NoticePayItForward],
    announcement: Option[NoticeAnnouncement],
    charity_donation: Option[NoticeCharityDonation],
    bits_badge_tier: Option[NoticeBitsBadgeTier]
) extends EventSubEvent {
  def userId: String = chatter_user_id
  def broadcasterId: String = broadcaster_user_id
  def eventTimestamp: String = ""
}
object ChannelChatNotificationEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[ChannelChatNotificationEvent] = ConfiguredDecoder.derived
  given Encoder[ChannelChatNotificationEvent] = ConfiguredEncoder.derived
}

/** A stream has started. */
case class StreamStartEvent(
    id: String,
    broadcaster_user_id: String,
    broadcaster_user_login: String,
    broadcaster_user_name: String,
    `type`: String,
    started_at: OffsetDateTime
) extends EventSubEvent {
  def userId: String = broadcaster_user_id
  def broadcasterId: String = broadcaster_user_id
  def eventTimestamp: String = started_at.toString
}
object StreamStartEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[StreamStartEvent] = ConfiguredDecoder.derived
  given Encoder[StreamStartEvent] = ConfiguredEncoder.derived
}

/** A stream has ended. */
case class StreamEndEvent(
    broadcaster_user_id: String,
    broadcaster_user_login: String,
    broadcaster_user_name: String
) extends EventSubEvent {
  def userId: String = broadcaster_user_id
  def broadcasterId: String = broadcaster_user_id
  def eventTimestamp: String = ""
}
object StreamEndEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[StreamEndEvent] = ConfiguredDecoder.derived
  given Encoder[StreamEndEvent] = ConfiguredEncoder.derived
}

/** A user was added as a moderator. */
case class ModeratorAddEvent(
    broadcaster_user_id: String,
    broadcaster_user_login: String,
    broadcaster_user_name: String,
    user_id: String,
    user_login: String,
    user_name: String
) extends EventSubEvent {
  def userId: String = user_id
  def broadcasterId: String = broadcaster_user_id
  def eventTimestamp: String = ""
}
object ModeratorAddEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[ModeratorAddEvent] = ConfiguredDecoder.derived
  given Encoder[ModeratorAddEvent] = ConfiguredEncoder.derived
}

/** A user was removed as a moderator. */
case class ModeratorRemoveEvent(
    broadcaster_user_id: String,
    broadcaster_user_login: String,
    broadcaster_user_name: String,
    user_id: String,
    user_login: String,
    user_name: String
) extends EventSubEvent {
  def userId: String = user_id
  def broadcasterId: String = broadcaster_user_id
  def eventTimestamp: String = ""
}
object ModeratorRemoveEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[ModeratorRemoveEvent] = ConfiguredDecoder.derived
  given Encoder[ModeratorRemoveEvent] = ConfiguredEncoder.derived
}

/** A user was added as a VIP. */
case class VipAddEvent(
    user_id: String,
    user_login: String,
    user_name: String,
    broadcaster_user_id: String,
    broadcaster_user_login: String,
    broadcaster_user_name: String
) extends EventSubEvent {
  def userId: String = user_id
  def broadcasterId: String = broadcaster_user_id
  def eventTimestamp: String = ""
}
object VipAddEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[VipAddEvent] = ConfiguredDecoder.derived
  given Encoder[VipAddEvent] = ConfiguredEncoder.derived
}

/** A user was removed as a VIP. */
case class VipRemoveEvent(
    user_id: String,
    user_login: String,
    user_name: String,
    broadcaster_user_id: String,
    broadcaster_user_login: String,
    broadcaster_user_name: String
) extends EventSubEvent {
  def userId: String = user_id
  def broadcasterId: String = broadcaster_user_id
  def eventTimestamp: String = ""
}
object VipRemoveEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[VipRemoveEvent] = ConfiguredDecoder.derived
  given Encoder[VipRemoveEvent] = ConfiguredEncoder.derived
}

/** A user subscribed to the channel. */
case class SubscribeEvent(
    user_id: String,
    user_login: String,
    user_name: String,
    broadcaster_user_id: String,
    broadcaster_user_login: String,
    broadcaster_user_name: String,
    tier: String,
    is_gift: Boolean
) extends EventSubEvent {
  def userId: String = user_id
  def broadcasterId: String = broadcaster_user_id
  def eventTimestamp: String = ""
}
object SubscribeEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[SubscribeEvent] = ConfiguredDecoder.derived
  given Encoder[SubscribeEvent] = ConfiguredEncoder.derived
}

/** A user gifted a subscription. */
case class SubscriptionGiftEvent(
    user_id: Option[String],
    user_login: Option[String],
    user_name: Option[String],
    broadcaster_user_id: String,
    broadcaster_user_login: String,
    broadcaster_user_name: String,
    total: Int,
    tier: String,
    cumulative_total: Option[Int],
    is_anonymous: Boolean
) extends EventSubEvent {
  def userId: String = user_id.getOrElse("")
  def broadcasterId: String = broadcaster_user_id
  def eventTimestamp: String = ""
}
object SubscriptionGiftEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[SubscriptionGiftEvent] = ConfiguredDecoder.derived
  given Encoder[SubscriptionGiftEvent] = ConfiguredEncoder.derived
}

/** A subscription has expired. */
case class SubscriptionExpireEvent(
    user_id: String,
    user_login: String,
    user_name: String,
    broadcaster_user_id: String,
    broadcaster_user_login: String,
    broadcaster_user_name: String,
    tier: String,
    is_gift: Boolean
) extends EventSubEvent {
  def userId: String = user_id
  def broadcasterId: String = broadcaster_user_id
  def eventTimestamp: String = ""
}
object SubscriptionExpireEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[SubscriptionExpireEvent] = ConfiguredDecoder.derived
  given Encoder[SubscriptionExpireEvent] = ConfiguredEncoder.derived
}

/** A user followed the channel. */
case class FollowEvent(
    user_id: String,
    user_login: String,
    user_name: String,
    broadcaster_user_id: String,
    broadcaster_user_login: String,
    broadcaster_user_name: String,
    followed_at: OffsetDateTime
) extends EventSubEvent {
  def userId: String = user_id
  def broadcasterId: String = broadcaster_user_id
  def eventTimestamp: String = followed_at.toString
}
object FollowEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[FollowEvent] = ConfiguredDecoder.derived
  given Encoder[FollowEvent] = ConfiguredEncoder.derived
}

/** A channel point custom reward redemption. */
case class ChannelPointsRedemptionAddEvent(
    id: String,
    broadcaster_user_id: String,
    broadcaster_user_login: String,
    broadcaster_user_name: String,
    user_id: String,
    user_login: String,
    user_name: String,
    user_input: String,
    status: String,
    reward: Reward,
    redeemed_at: OffsetDateTime
) extends EventSubEvent {
  def userId: String = user_id
  def broadcasterId: String = broadcaster_user_id
  def eventTimestamp: String = redeemed_at.toString
}
object ChannelPointsRedemptionAddEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[ChannelPointsRedemptionAddEvent] = ConfiguredDecoder.derived
  given Encoder[ChannelPointsRedemptionAddEvent] = ConfiguredEncoder.derived
}

/** A channel is raiding another channel. */
case class RaidEvent(
    from_broadcaster_user_id: String,
    from_broadcaster_user_login: String,
    from_broadcaster_user_name: String,
    to_broadcaster_user_id: String,
    to_broadcaster_user_login: String,
    to_broadcaster_user_name: String,
    viewers: Int
) extends EventSubEvent {
  def userId: String = from_broadcaster_user_id
  def broadcasterId: String = to_broadcaster_user_id
  def eventTimestamp: String = ""
}
object RaidEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[RaidEvent] = ConfiguredDecoder.derived
  given Encoder[RaidEvent] = ConfiguredEncoder.derived
}

/** A user cheered (Bits). */
case class CheerEvent(
    is_anonymous: Boolean,
    user_id: Option[String],
    user_login: Option[String],
    user_name: Option[String],
    broadcaster_user_id: String,
    broadcaster_user_login: String,
    broadcaster_user_name: String,
    message: String,
    bits: Int
) extends EventSubEvent {
  def userId: String = user_id.getOrElse("")
  def broadcasterId: String = broadcaster_user_id
  def eventTimestamp: String = ""
}
object CheerEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[CheerEvent] = ConfiguredDecoder.derived
  given Encoder[CheerEvent] = ConfiguredEncoder.derived
}

/** A channel's title or category has been updated. */
case class ChannelUpdateEvent(
    broadcaster_user_id: String,
    broadcaster_user_login: String,
    broadcaster_user_name: String,
    title: String,
    language: String,
    category_id: String,
    category_name: String,
    content_classification_labels: List[String]
) extends EventSubEvent {
  def userId: String = broadcaster_user_id
  def broadcasterId: String = broadcaster_user_id
  def eventTimestamp: String = ""
}
object ChannelUpdateEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[ChannelUpdateEvent] = ConfiguredDecoder.derived
  given Encoder[ChannelUpdateEvent] = ConfiguredEncoder.derived
}

/** A poll has started. */
case class PollBeginEvent(
    id: String,
    broadcaster_user_id: String,
    broadcaster_user_login: String,
    broadcaster_user_name: String,
    title: String,
    choices: List[PollChoice],
    bits_voting: BitsVoting,
    channel_points_voting: ChannelPointsVoting,
    started_at: OffsetDateTime,
    ends_at: OffsetDateTime
) extends EventSubEvent {
  def userId: String = broadcaster_user_id
  def broadcasterId: String = broadcaster_user_id
  def eventTimestamp: String = started_at.toString
}
object PollBeginEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[PollBeginEvent] = ConfiguredDecoder.derived
  given Encoder[PollBeginEvent] = ConfiguredEncoder.derived
}

/** A poll has completed. */
case class PollCompleteEvent(
    id: String,
    broadcaster_user_id: String,
    broadcaster_user_login: String,
    broadcaster_user_name: String,
    title: String,
    choices: List[StartedPollChoice],
    bits_voting: BitsVoting,
    channel_points_voting: ChannelPointsVoting,
    status: String,
    started_at: OffsetDateTime,
    ended_at: OffsetDateTime
) extends EventSubEvent {
  def userId: String = broadcaster_user_id
  def broadcasterId: String = broadcaster_user_id
  def eventTimestamp: String = ended_at.toString
}
object PollCompleteEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[PollCompleteEvent] = ConfiguredDecoder.derived
  given Encoder[PollCompleteEvent] = ConfiguredEncoder.derived
}

/** A prediction has started. */
case class PredictionBeginEvent(
    id: String,
    broadcaster_user_id: String,
    broadcaster_user_login: String,
    broadcaster_user_name: String,
    title: String,
    outcomes: List[PredictionOutcome],
    started_at: OffsetDateTime,
    locks_at: OffsetDateTime
) extends EventSubEvent {
  def userId: String = broadcaster_user_id
  def broadcasterId: String = broadcaster_user_id
  def eventTimestamp: String = started_at.toString
}
object PredictionBeginEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[PredictionBeginEvent] = ConfiguredDecoder.derived
  given Encoder[PredictionBeginEvent] = ConfiguredEncoder.derived
}

/** A prediction has resolved. */
case class PredictionCompleteEvent(
    id: String,
    broadcaster_user_id: String,
    broadcaster_user_login: String,
    broadcaster_user_name: String,
    title: String,
    winning_outcome_id: String,
    outcomes: List[StartedPredictionOutcome],
    status: String,
    started_at: OffsetDateTime,
    ended_at: OffsetDateTime
) extends EventSubEvent {
  def userId: String = broadcaster_user_id
  def broadcasterId: String = broadcaster_user_id
  def eventTimestamp: String = ended_at.toString
}
object PredictionCompleteEvent {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[PredictionCompleteEvent] = ConfiguredDecoder.derived
  given Encoder[PredictionCompleteEvent] = ConfiguredEncoder.derived
}

// ========================================================================
// Supporting Types
// ========================================================================

case class ChatMessage(text: String, fragments: List[ChatMessageFragment])
object ChatMessage {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[ChatMessage] = ConfiguredDecoder.derived
  given Encoder[ChatMessage] = ConfiguredEncoder.derived
}

case class ChatMessageFragment(
    `type`: String,
    text: String,
    cheermote: Option[ChatMessageCheermote],
    emote: Option[ChatMessageEmote],
    mention: Option[ChatMessageMention]
)
object ChatMessageFragment {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[ChatMessageFragment] = ConfiguredDecoder.derived
  given Encoder[ChatMessageFragment] = ConfiguredEncoder.derived
}

case class ChatMessageCheermote(prefix: String, bits: Int, tier: Int)
object ChatMessageCheermote {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[ChatMessageCheermote] = ConfiguredDecoder.derived
  given Encoder[ChatMessageCheermote] = ConfiguredEncoder.derived
}

case class ChatMessageEmote(
    id: String,
    emote_set_id: String,
    owner_id: Option[String] = None,
    format: Option[List[String]] = None
)
object ChatMessageEmote {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[ChatMessageEmote] = ConfiguredDecoder.derived
  given Encoder[ChatMessageEmote] = ConfiguredEncoder.derived
}

case class ChatMessageMention(
    user_id: String,
    user_login: String,
    user_name: String
)
object ChatMessageMention {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[ChatMessageMention] = ConfiguredDecoder.derived
  given Encoder[ChatMessageMention] = ConfiguredEncoder.derived
}

case class Badge(set_id: String, id: String, info: String)
object Badge {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[Badge] = ConfiguredDecoder.derived
  given Encoder[Badge] = ConfiguredEncoder.derived
}

case class Cheer(bits: Int)
object Cheer {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[Cheer] = ConfiguredDecoder.derived
  given Encoder[Cheer] = ConfiguredEncoder.derived
}

case class Reply(
    parent_message_id: String,
    parent_message_body: String,
    parent_user_id: String,
    parent_user_login: String,
    parent_user_name: String,
    thread_message_id: String,
    thread_user_id: String,
    thread_user_login: String,
    thread_user_name: String
)
object Reply {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[Reply] = ConfiguredDecoder.derived
  given Encoder[Reply] = ConfiguredEncoder.derived
}

case class NoticeSub(sub_tier: String, is_prime: Boolean, duration_months: Int)
object NoticeSub {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[NoticeSub] = ConfiguredDecoder.derived
  given Encoder[NoticeSub] = ConfiguredEncoder.derived
}

case class NoticeResub(
    cumulative_months: Int,
    duration_months: Int,
    streak_months: Int,
    sub_tier: String,
    is_prime: Boolean,
    is_gift: Boolean,
    gifter_is_anonymous: Option[Boolean],
    gifter_user_id: Option[String],
    gifter_user_login: Option[String],
    gifter_user_name: Option[String]
)
object NoticeResub {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[NoticeResub] = ConfiguredDecoder.derived
  given Encoder[NoticeResub] = ConfiguredEncoder.derived
}

case class NoticeSubGift(
    duration_months: Int,
    cumulative_total: Option[Int],
    recipient_user_id: String,
    recipient_user_login: String,
    recipient_user_name: String,
    sub_tier: String,
    community_gift_id: Option[String]
)
object NoticeSubGift {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[NoticeSubGift] = ConfiguredDecoder.derived
  given Encoder[NoticeSubGift] = ConfiguredEncoder.derived
}

case class NoticeCommunitySubGift(
    id: String,
    total: Int,
    sub_tier: String,
    cumulative_total: Option[Int]
)
object NoticeCommunitySubGift {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[NoticeCommunitySubGift] = ConfiguredDecoder.derived
  given Encoder[NoticeCommunitySubGift] = ConfiguredEncoder.derived
}

case class NoticeGiftPaidUpgrade(
    gifter_is_anonymous: Boolean,
    gifter_user_id: Option[String],
    gifter_user_login: Option[String],
    gifter_user_name: Option[String]
)
object NoticeGiftPaidUpgrade {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[NoticeGiftPaidUpgrade] = ConfiguredDecoder.derived
  given Encoder[NoticeGiftPaidUpgrade] = ConfiguredEncoder.derived
}

case class NoticePrimePaidUpgrade(sub_tier: String)
object NoticePrimePaidUpgrade {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[NoticePrimePaidUpgrade] = ConfiguredDecoder.derived
  given Encoder[NoticePrimePaidUpgrade] = ConfiguredEncoder.derived
}

case class NoticeRaid(
    user_id: String,
    user_login: String,
    user_name: String,
    viewer_count: Int,
    profile_image_url: String
)
object NoticeRaid {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[NoticeRaid] = ConfiguredDecoder.derived
  given Encoder[NoticeRaid] = ConfiguredEncoder.derived
}

case class NoticeUnraid(_empty: Option[String] = None)
object NoticeUnraid {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[NoticeUnraid] = ConfiguredDecoder.derived
  given Encoder[NoticeUnraid] = ConfiguredEncoder.derived
}

case class NoticePayItForward(
    gifter_is_anonymous: Boolean,
    gifter_user_id: Option[String],
    gifter_user_login: Option[String],
    gifter_user_name: Option[String]
)
object NoticePayItForward {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[NoticePayItForward] = ConfiguredDecoder.derived
  given Encoder[NoticePayItForward] = ConfiguredEncoder.derived
}

case class NoticeAnnouncement(color: String)
object NoticeAnnouncement {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[NoticeAnnouncement] = ConfiguredDecoder.derived
  given Encoder[NoticeAnnouncement] = ConfiguredEncoder.derived
}

case class NoticeCharityDonation(charity_name: String, amount: Amount)
object NoticeCharityDonation {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[NoticeCharityDonation] = ConfiguredDecoder.derived
  given Encoder[NoticeCharityDonation] = ConfiguredEncoder.derived
}

case class NoticeBitsBadgeTier(tier: Int)
object NoticeBitsBadgeTier {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[NoticeBitsBadgeTier] = ConfiguredDecoder.derived
  given Encoder[NoticeBitsBadgeTier] = ConfiguredEncoder.derived
}

case class Amount(value: Int, decimal_place: Int, currency: String)
object Amount {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[Amount] = (c: HCursor) => {
    for {
      value <- c.get[Int]("value")
      decimalPlace <- c
        .get[Int]("decimal_place")
        .orElse(c.get[Int]("decimal_places"))
      currency <- c.get[String]("currency")
    } yield Amount(value, decimalPlace, currency)
  }
  given Encoder[Amount] = ConfiguredEncoder.derived
}

case class Reward(id: String, title: String, cost: Int, prompt: String)
object Reward {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[Reward] = ConfiguredDecoder.derived
  given Encoder[Reward] = ConfiguredEncoder.derived
}

case class PollChoice(id: String, title: String)
object PollChoice {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[PollChoice] = ConfiguredDecoder.derived
  given Encoder[PollChoice] = ConfiguredEncoder.derived
}

case class StartedPollChoice(
    id: String,
    title: String,
    bits_votes: Int,
    channel_points_votes: Int,
    votes: Int
)
object StartedPollChoice {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[StartedPollChoice] = ConfiguredDecoder.derived
  given Encoder[StartedPollChoice] = ConfiguredEncoder.derived
}

case class BitsVoting(is_enabled: Boolean, amount_per_vote: Int)
object BitsVoting {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[BitsVoting] = ConfiguredDecoder.derived
  given Encoder[BitsVoting] = ConfiguredEncoder.derived
}

case class ChannelPointsVoting(is_enabled: Boolean, amount_per_vote: Int)
object ChannelPointsVoting {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[ChannelPointsVoting] = ConfiguredDecoder.derived
  given Encoder[ChannelPointsVoting] = ConfiguredEncoder.derived
}

case class PredictionOutcome(id: String, title: String, color: String)
object PredictionOutcome {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[PredictionOutcome] = ConfiguredDecoder.derived
  given Encoder[PredictionOutcome] = ConfiguredEncoder.derived
}

case class StartedPredictionOutcome(
    id: String,
    title: String,
    color: String,
    users: Int,
    channel_points: Int,
    top_predictors: List[TopPredictor]
)
object StartedPredictionOutcome {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[StartedPredictionOutcome] = ConfiguredDecoder.derived
  given Encoder[StartedPredictionOutcome] = ConfiguredEncoder.derived
}

case class TopPredictor(
    user_id: String,
    user_login: String,
    user_name: String,
    channel_points_won: Option[Int],
    channel_points_used: Int
)
object TopPredictor {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[TopPredictor] = ConfiguredDecoder.derived
  given Encoder[TopPredictor] = ConfiguredEncoder.derived
}

// ========================================================================
// Helix API Request/Response Types (for subscription management)
// ========================================================================

case class HelixCreateSubscriptionRequest(
    `type`: String,
    version: String,
    condition: Map[String, String],
    transport: HelixCreateTransport
)
object HelixCreateSubscriptionRequest {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[HelixCreateSubscriptionRequest] = ConfiguredDecoder.derived
  given Encoder[HelixCreateSubscriptionRequest] = ConfiguredEncoder.derived
}

case class HelixCreateTransport(
    method: String,
    callback: String
)
object HelixCreateTransport {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[HelixCreateTransport] = ConfiguredDecoder.derived
  given Encoder[HelixCreateTransport] = ConfiguredEncoder.derived
}

case class HelixSubscription(
    id: String,
    `type`: String,
    version: String,
    status: String,
    condition: Map[String, String],
    transport: HelixTransport,
    created_at: String
)
object HelixSubscription {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[HelixSubscription] = ConfiguredDecoder.derived
  given Encoder[HelixSubscription] = ConfiguredEncoder.derived
}

case class HelixTransport(
    method: String,
    callback: String
)
object HelixTransport {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[HelixTransport] = ConfiguredDecoder.derived
  given Encoder[HelixTransport] = ConfiguredEncoder.derived
}

case class HelixCreateSubscriptionResponse(
    data: List[HelixSubscription],
    total: Int,
    totalCost: Int,
    maxTotalCost: Int
)
object HelixCreateSubscriptionResponse {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[HelixCreateSubscriptionResponse] = ConfiguredDecoder.derived
  given Encoder[HelixCreateSubscriptionResponse] = ConfiguredEncoder.derived
}

case class HelixListSubscriptionResponse(
    data: List[HelixSubscription],
    total: Int,
    totalCost: Int,
    maxTotalCost: Int
)
object HelixListSubscriptionResponse {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[HelixListSubscriptionResponse] = ConfiguredDecoder.derived
  given Encoder[HelixListSubscriptionResponse] = ConfiguredEncoder.derived
}

case class HelixDeleteSubscriptionResponse(
    data: List[HelixSubscriptionId],
    total: Int,
    totalCost: Int,
    maxTotalCost: Int
)
object HelixDeleteSubscriptionResponse {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[HelixDeleteSubscriptionResponse] = ConfiguredDecoder.derived
  given Encoder[HelixDeleteSubscriptionResponse] = ConfiguredEncoder.derived
}

case class HelixSubscriptionId(id: String)
object HelixSubscriptionId {
  given Configuration = EventSubCirce.webhookConfiguration

  given Decoder[HelixSubscriptionId] = ConfiguredDecoder.derived
  given Encoder[HelixSubscriptionId] = ConfiguredEncoder.derived
}
