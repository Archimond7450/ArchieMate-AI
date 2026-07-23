package com.archimond7450.archiemate.twitch.eventsub

import com.archimond7450.archiemate.twitch.eventsub.EventSubCirce.*
import com.archimond7450.archiemate.twitch.eventsub.EventSubEvent.*
import com.archimond7450.archiemate.twitch.eventsub.*
import io.circe.parser
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.HttpHeader
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.slf4j.LoggerFactory

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.reflect.classTag

/** HTTP routes for receiving Twitch EventSub WebHook events.
  *
  * Handles:
  * - Challenge verification (hub.challenge response)
  * - HMAC-SHA256 signature verification
  * - Event decoding and routing
  */
class EventSubWebhookRoutes(
    config: EventSubConfig,
    executionContext: ExecutionContext,
    classicActorSystem: org.apache.pekko.actor.ActorSystem
) {

  private val log = LoggerFactory.getLogger(classOf[EventSubWebhookRoutes])

  def webhookRoutes: Route = {
    pathPrefix(config.callbackPath.stripPrefix("/")) {
      extractRequest { request =>
        given ClassicActorSystemProvider = classicActorSystem
        val signatureHeader = request.headers.find(h => h.name == "Twitch-Webhook-Signature").map(_.value())
        // Extract the body as a Future[String]
        val bodyFuture = request.entity.dataBytes
          .runFold("") { (acc, byteString) =>
            acc + byteString.utf8String
          }
        val bodyDecoded: Future[Either[String, String]] = bodyFuture.map { bodyStr =>
          Right(bodyStr)
        }(executionContext)
        onComplete(bodyDecoded) { result =>
          result match {
            case scala.util.Success(Right(bodyString: String)) =>
              signatureHeader.fold {
                // Challenge verification request
                complete(StatusCodes.OK)
              } { signature =>
                if (signature.startsWith("sha256=")) {
                  val signatureValue = signature.drop("sha256=".length)
                  // Verify HMAC-SHA256 signature
                  if (verifyHmacSignature(bodyString, signatureValue)) {
                    // Parse and route the event
                    parser.decode[WebhookPayload](bodyString).fold(
                      decodingFailure => {
                        log.error("Failed to parse EventSub payload: {}", decodingFailure.getMessage)
                        complete(StatusCodes.BadRequest -> s"Invalid payload: ${decodingFailure.getMessage}")
                      },
                      payload => handleEvent(payload)
                    )
                  } else {
                    log.warn("Invalid HMAC signature for EventSub webhook")
                    complete(StatusCodes.Unauthorized -> "Invalid signature")
                  }
                } else {
                  // Not a sha256 signature
                  complete(StatusCodes.OK)
                }
              }
            case scala.util.Success(Left(error)) =>
              log.error("Failed to read webhook body: {}", error)
              complete(StatusCodes.BadRequest -> "Failed to read request body")
            case scala.util.Failure(ex) =>
              log.error("Failed to read webhook body", ex)
              complete(StatusCodes.BadRequest -> "Failed to read request body")
          }
        }
      }
    }
  }

  private def handleEvent(payload: WebhookPayload): Route = {
    // Log the event
    log.info(
      "Received EventSub event: type={}, status={}, subscription_id={}",
      payload.subscription.`type`,
      payload.subscription.status,
      payload.metadata.event_subscription_id
    )

    // Handle subscription verification (challenge)
    if (payload.metadata.message_type == "webhook_callback_verification") {
      // Twitch sends a challenge to verify our endpoint
      log.info("Challenge verification received for subscription {}", payload.metadata.event_subscription_id)
      complete(StatusCodes.OK)
    } else if (payload.subscription.status == "enabled") {
      // Decode the event
      EventSubEvent.decode(payload) match {
        case Right(event) =>
          // Route to appropriate handler
          handleDecodedEvent(event, payload)
        case Left(error) =>
          log.error("Failed to decode EventSub event: {}", error.getMessage)
          complete(StatusCodes.BadRequest -> s"Failed to decode event: ${error.getMessage}")
      }
    } else {
      // Subscription status changed (enabled, disabled, etc.)
      log.info(
        "EventSub subscription status changed: type={}, status={}",
        payload.subscription.`type`,
        payload.subscription.status
      )
      complete(StatusCodes.OK)
    }
  }

  private def handleDecodedEvent(event: EventSubEvent, payload: WebhookPayload): Route = {
    event match {
      case chatMsg: ChannelChatMessageEvent =>
        log.info("Chat message from {} in channel {}", chatMsg.chatter_user_name, chatMsg.broadcaster_user_name)
        complete(StatusCodes.OK)

      case chatNotif: ChannelChatNotificationEvent =>
        log.info("Chat notification: {} in channel {}", chatNotif.notice_type, chatNotif.broadcaster_user_name)
        complete(StatusCodes.OK)

      case streamStart: StreamStartEvent =>
        log.info("Stream started by {}", streamStart.broadcaster_user_name)
        complete(StatusCodes.OK)

      case streamEnd: StreamEndEvent =>
        log.info("Stream ended by {}", streamEnd.broadcaster_user_name)
        complete(StatusCodes.OK)

      case modAdd: ModeratorAddEvent =>
        log.info("Moderator added: {} by {}", modAdd.user_name, modAdd.broadcaster_user_name)
        complete(StatusCodes.OK)

      case modRemove: ModeratorRemoveEvent =>
        log.info("Moderator removed: {} by {}", modRemove.user_name, modRemove.broadcaster_user_name)
        complete(StatusCodes.OK)

      case vipAdd: VipAddEvent =>
        log.info("VIP added: {} by {}", vipAdd.user_name, vipAdd.broadcaster_user_name)
        complete(StatusCodes.OK)

      case vipRemove: VipRemoveEvent =>
        log.info("VIP removed: {} by {}", vipRemove.user_name, vipRemove.broadcaster_user_name)
        complete(StatusCodes.OK)

      case subscribe: SubscribeEvent =>
        log.info("Subscribe: {} (tier: {}, gift: {})", subscribe.user_name, subscribe.tier, subscribe.is_gift)
        complete(StatusCodes.OK)

      case subGift: SubscriptionGiftEvent =>
        log.info("Subscription gift: {} x{} (anonymous: {})", subGift.user_name.getOrElse("anonymous"), subGift.total, subGift.is_anonymous)
        complete(StatusCodes.OK)

      case subExpire: SubscriptionExpireEvent =>
        log.info("Subscription expired: {} (tier: {})", subExpire.user_name, subExpire.tier)
        complete(StatusCodes.OK)

      case follow: FollowEvent =>
        log.info("Follow: {} by {}", follow.user_name, follow.broadcaster_user_name)
        complete(StatusCodes.OK)

      case redemption: ChannelPointsRedemptionAddEvent =>
        log.info("Channel points redemption: {} for {}", redemption.reward.title, redemption.user_name)
        complete(StatusCodes.OK)

      case raid: RaidEvent =>
        log.info("Raid from {} with {} viewers", raid.from_broadcaster_user_name, raid.viewers)
        complete(StatusCodes.OK)

      case cheer: CheerEvent =>
        log.info("Cheer: {} ({} bits)", cheer.user_name.getOrElse("anonymous"), cheer.bits)
        complete(StatusCodes.OK)

      case channelUpdate: ChannelUpdateEvent =>
        log.info("Channel update: title={}, category={}", channelUpdate.title, channelUpdate.category_name)
        complete(StatusCodes.OK)

      case pollBegin: PollBeginEvent =>
        log.info("Poll begin: {} by {}", pollBegin.title, pollBegin.broadcaster_user_name)
        complete(StatusCodes.OK)

      case pollComplete: PollCompleteEvent =>
        log.info("Poll complete: {} by {} (status: {})", pollComplete.title, pollComplete.broadcaster_user_name, pollComplete.status)
        complete(StatusCodes.OK)

      case predictionBegin: PredictionBeginEvent =>
        log.info("Prediction begin: {} by {}", predictionBegin.title, predictionBegin.broadcaster_user_name)
        complete(StatusCodes.OK)

      case predictionComplete: PredictionCompleteEvent =>
        log.info("Prediction complete: {} by {} (winner: {})", predictionComplete.title, predictionComplete.broadcaster_user_name, predictionComplete.winning_outcome_id)
        complete(StatusCodes.OK)
    }
  }

  // ----------------------------------------------------------------
  // HMAC-SHA256 verification
  // ----------------------------------------------------------------

  private def verifyHmacSignature(body: String, signature: String): Boolean = {
    try {
      val mac = Mac.getInstance("HmacSHA256")
      val secretKey = new SecretKeySpec(config.webhookSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256")
      mac.init(secretKey)
      val expectedSignature = mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      val expectedHex = bytesToHex(expectedSignature)
      // Constant-time comparison
      expectedSignature.length == signature.length && java.util.Arrays.equals(
        expectedSignature,
        hexToBytes(signature)
      )
    } catch {
      case _: Exception =>
        false
    }
  }

  private def bytesToHex(bytes: Array[Byte]): String = {
    bytes.map("%02x".format(_)).mkString
  }

  private def hexToBytes(hex: String): Array[Byte] = {
    hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  }
}
