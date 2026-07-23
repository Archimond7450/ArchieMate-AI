package com.archimond7450.archiemate.twitch.eventsub

import com.archimond7450.archiemate.actors.http.HttpRequestActor
import com.archimond7450.archiemate.twitch.eventsub.EventSubCirce.*
import com.archimond7450.archiemate.twitch.eventsub.EventSubActor.*
import com.archimond7450.archiemate.user.UserTokenRegistry
import com.archimond7450.archiemate.user.UserTokenRegistry.{*, given}
import io.circe.Encoder
import io.circe.parser
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.http.scaladsl.model.HttpMethods
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.model.headers.{Authorization, RawHeader}
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.util.Timeout

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

/** Manages Twitch EventSub subscriptions via the Helix API.
  *
  * Creates, lists, and revokes subscriptions for a given broadcaster.
  *
  * @note This actor must be supervised by its parent with a resume strategy.
  */
object EventSubActor {

  private val actorName = "eventsub-actor"

  // ----------------------------------------------------------------
  // Commands
  // ----------------------------------------------------------------

  sealed trait Command

  /** Create an EventSub subscription for a broadcaster. */
  final case class CreateSubscription(
      broadcasterUserId: String,
      eventType: String,
      eventVersion: String,
      accessToken: String,
      replyTo: ActorRef[CreateResponse]
  ) extends Command

  /** List all EventSub subscriptions for a broadcaster. */
  final case class ListSubscriptions(
      broadcasterUserId: String,
      accessToken: String,
      replyTo: ActorRef[ListResponse]
  ) extends Command

  /** Revoke an EventSub subscription by ID. */
  final case class RevokeSubscription(
      subscriptionId: String,
      broadcasterUserId: String,
      accessToken: String,
      replyTo: ActorRef[RevokeResponse]
  ) extends Command

  /** Create all required subscriptions for a broadcaster. */
  final case class CreateAllSubscriptions(
      broadcasterUserId: String,
      accessToken: String,
      replyTo: ActorRef[CreateAllResponse]
  ) extends Command

  /** Revoke all subscriptions for a broadcaster. */
  final case class RevokeAllSubscriptions(
      broadcasterUserId: String,
      accessToken: String,
      replyTo: ActorRef[RevokeAllResponse]
  ) extends Command

  // ----------------------------------------------------------------
  // Responses
  // ----------------------------------------------------------------

  sealed trait Response

  sealed trait CreateResponse extends Response
  sealed trait ListResponse extends Response
  sealed trait RevokeResponse extends Response
  sealed trait CreateAllResponse extends Response
  sealed trait RevokeAllResponse extends Response

  final case class CreateSuccess(subscriptionId: String) extends CreateResponse
  final case class CreateFailed(message: String) extends CreateResponse

  final case class ListSuccess(subscriptions: List[HelixSubscription]) extends ListResponse
  final case class ListFailed(message: String) extends ListResponse

  final case class RevokeSuccess(subscriptionId: String) extends RevokeResponse
  final case class RevokeFailed(message: String) extends RevokeResponse

  final case class CreateAllSuccess(created: List[String], failed: List[String]) extends CreateAllResponse
  final case class CreateAllFailed(message: String) extends CreateAllResponse

  final case class RevokeAllSuccess(revoked: List[String], failed: List[String]) extends RevokeAllResponse
  final case class RevokeAllFailed(message: String) extends RevokeAllResponse

  // ----------------------------------------------------------------
  // Internal
  // ----------------------------------------------------------------

  private final case class HttpRequestReply(reply: StatusReply[Any]) extends Command

  // ----------------------------------------------------------------
  // Factory
  // ----------------------------------------------------------------

  def apply(
      config: EventSubConfig,
      twitchConfig: com.archimond7450.archiemate.settings.TwitchConfig,
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      userTokenRegistry: ActorRef[UserTokenRegistry.Command]
  )(using classicProvider: org.apache.pekko.actor.ClassicActorSystemProvider, timeout: Timeout): Behavior[Command] =
    Behaviors.supervise[Command] {
      Behaviors.setup { ctx =>
        implicit val ec: ExecutionContext = classicProvider.classicSystem.dispatcher
        ctx.log.info("EventSubActor initialized with callback path: {}", config.callbackPath)
        mainBehavior(ctx, config, twitchConfig, httpRequestActor, userTokenRegistry)
      }
    }.onFailure[Throwable](SupervisorStrategy.resume)

  // ----------------------------------------------------------------
  // Behavior
  // ----------------------------------------------------------------

  private def mainBehavior(
      ctx: ActorContext[Command],
      config: EventSubConfig,
      twitchConfig: com.archimond7450.archiemate.settings.TwitchConfig,
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      userTokenRegistry: ActorRef[UserTokenRegistry.Command]
  )(using timeout: Timeout, ec: ExecutionContext): Behavior[Command] =
    Behaviors.withMdc(Map("actor" -> actorName))(
      Behaviors.receiveMessage {
        case create: CreateSubscription =>
          createSubscription(ctx, config, create.broadcasterUserId, create.eventType, create.eventVersion, create.accessToken, create.replyTo, httpRequestActor, twitchConfig)
          Behaviors.same
        case list: ListSubscriptions =>
          listSubscriptions(ctx, config, list.broadcasterUserId, list.accessToken, list.replyTo, httpRequestActor, twitchConfig)
          Behaviors.same
        case revoke: RevokeSubscription =>
          revokeSubscription(ctx, config, revoke.subscriptionId, revoke.broadcasterUserId, revoke.accessToken, revoke.replyTo, httpRequestActor, twitchConfig)
          Behaviors.same
        case createAll: CreateAllSubscriptions =>
          createAllSubscriptions(ctx, config, createAll.broadcasterUserId, createAll.accessToken, createAll.replyTo, httpRequestActor, twitchConfig)
          Behaviors.same
        case revokeAll: RevokeAllSubscriptions =>
          revokeAllSubscriptions(ctx, config, revokeAll.broadcasterUserId, revokeAll.accessToken, revokeAll.replyTo, httpRequestActor, twitchConfig)
          Behaviors.same
        case HttpRequestReply(_) =>
          // Response from Helix API handled via callback, not here
          Behaviors.same
      }
    )

  // ----------------------------------------------------------------
  // Subscription creation
  // ----------------------------------------------------------------

  private def createAllSubscriptions(
      ctx: ActorContext[Command],
      config: EventSubConfig,
      broadcasterUserId: String,
      accessToken: String,
      replyTo: ActorRef[CreateAllResponse],
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      twitchConfig: com.archimond7450.archiemate.settings.TwitchConfig
  )(using timeout: Timeout, ec: ExecutionContext): Unit = {
    val eventTypes = List(
      ("channel.chat.message", "1"),
      ("channel.chat.notification", "1"),
      ("stream.online", "1"),
      ("stream.offline", "1"),
      ("channel.moderator.add", "1"),
      ("channel.moderator.remove", "1"),
      ("channel.vip.add", "1"),
      ("channel.vip.remove", "1"),
      ("channel.subscribe", "1"),
      ("channel.subscription.gift", "1"),
      ("channel.subscription.expire", "1"),
      ("channel.follow", "2"),
      ("channel.channel_points_custom_reward_redemption.add", "1"),
      ("channel.raid", "1"),
      ("channel.cheer", "1"),
      ("channel.update", "2"),
      ("channel.poll.begin", "1"),
      ("channel.poll.complete", "1"),
      ("channel.prediction.begin", "1"),
      ("channel.prediction.complete", "1")
    )

    val transport = HelixCreateTransport("webhook", config.callbackPath)
    val condition = Map("broadcaster_user_id" -> broadcasterUserId)

    // Create subscriptions sequentially (Twitch rate limits concurrent requests)
    def createNext(index: Int, created: List[String], failed: List[String]): Unit = {
      if (index >= eventTypes.size) {
        replyTo ! CreateAllSuccess(created, failed)
      } else {
        val (eventType, version) = eventTypes(index)
        val request = HelixCreateSubscriptionRequest(eventType, version, condition, transport)
        createSingleSubscription(ctx, config, httpRequestActor, accessToken, request, twitchConfig) {
          case Right(id) => createNext(index + 1, id :: created, failed)
          case Left(msg) => createNext(index + 1, created, msg :: failed)
        }
      }
    }

    createNext(0, List.empty, List.empty)
  }

  private def revokeAllSubscriptions(
      ctx: ActorContext[Command],
      config: EventSubConfig,
      broadcasterUserId: String,
      accessToken: String,
      replyTo: ActorRef[RevokeAllResponse],
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      twitchConfig: com.archimond7450.archiemate.settings.TwitchConfig
  )(using timeout: Timeout, ec: ExecutionContext): Unit = {
    listSubscriptionsForBroadcaster(ctx, config, httpRequestActor, broadcasterUserId, accessToken, twitchConfig) {
      case Right(subs) =>
        def revokeNext(index: Int, revoked: List[String], failed: List[String]): Unit = {
          if (index >= subs.size) {
            replyTo ! RevokeAllSuccess(revoked, failed)
          } else {
            val sub = subs(index)
            revokeSingleSubscription(ctx, config, httpRequestActor, accessToken, sub.id, sub.condition.get("broadcaster_user_id").getOrElse(""), twitchConfig) {
              case Right(id) => revokeNext(index + 1, id :: revoked, failed)
              case Left(msg) => revokeNext(index + 1, revoked, msg :: failed)
            }
          }
        }
        revokeNext(0, List.empty, List.empty)
      case Left(msg) =>
        replyTo ! RevokeAllFailed(msg)
    }
  }

  // ----------------------------------------------------------------
  // Helix API helpers
  // ----------------------------------------------------------------

  private def createSubscription(
      ctx: ActorContext[Command],
      config: EventSubConfig,
      broadcasterUserId: String,
      eventType: String,
      eventVersion: String,
      accessToken: String,
      replyTo: ActorRef[CreateResponse],
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      twitchConfig: com.archimond7450.archiemate.settings.TwitchConfig
  )(using timeout: Timeout, ec: ExecutionContext): Unit = {
    val transport = HelixCreateTransport("webhook", config.callbackPath)
    val condition = Map("broadcaster_user_id" -> broadcasterUserId)
    val request = HelixCreateSubscriptionRequest(eventType, eventVersion, condition, transport)

    createSingleSubscription(ctx, config, httpRequestActor, accessToken, request, twitchConfig) {
      case Right(id) => replyTo ! CreateSuccess(id)
      case Left(msg) => replyTo ! CreateFailed(msg)
    }
  }

  private def createSingleSubscription(
      ctx: ActorContext[Command],
      config: EventSubConfig,
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      accessToken: String,
      request: HelixCreateSubscriptionRequest,
      twitchConfig: com.archimond7450.archiemate.settings.TwitchConfig
  )(onComplete: Either[String, String] => Unit)(using timeout: Timeout, ec: ExecutionContext): Unit = {
    val uri = Uri(config.helixBaseUrl).withPath(Uri.Path("/eventsub/subscriptions"))

    val probeRef: ActorRef[StatusReply[Any]] = ctx.messageAdapter[StatusReply[Any]] { statusReply =>
      statusReply match {
        case StatusReply.Success(value) =>
          value match {
            case resp: HelixCreateSubscriptionResponse =>
              onComplete(Right(resp.data.headOption.map(_.id).getOrElse("")))
            case _ =>
              onComplete(Left("Unexpected response type"))
          }
        case StatusReply.Error(err) =>
          onComplete(Left(err.getMessage))
      }
      HttpRequestReply(statusReply)
    }

    httpRequestActor ! HttpRequestActor.Request[HelixCreateSubscriptionResponse](
      method = HttpMethods.POST,
      uri = uri,
      headers = Seq(
        Authorization(org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken(accessToken)),
        RawHeader("Client-Id", twitchConfig.clientId),
        RawHeader("Content-Type", "application/json")
      ),
      entity = org.apache.pekko.http.scaladsl.model.HttpEntity(
        org.apache.pekko.http.scaladsl.model.MediaTypes.`application/json`,
        io.circe.syntax.EncoderOps[HelixCreateSubscriptionRequest](request).asJson.noSpaces.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      ),
      decode = str => parser.decode[HelixCreateSubscriptionResponse](str).toTry,
      replyTo = probeRef
    )
  }

  private def listSubscriptions(
      ctx: ActorContext[Command],
      config: EventSubConfig,
      broadcasterUserId: String,
      accessToken: String,
      replyTo: ActorRef[ListResponse],
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      twitchConfig: com.archimond7450.archiemate.settings.TwitchConfig
  )(using timeout: Timeout, ec: ExecutionContext): Unit = {
    listSubscriptionsForBroadcaster(ctx, config, httpRequestActor, broadcasterUserId, accessToken, twitchConfig) {
      case Right(subs) => replyTo ! ListSuccess(subs)
      case Left(msg) => replyTo ! ListFailed(msg)
    }
  }

  private def listSubscriptionsForBroadcaster(
      ctx: ActorContext[Command],
      config: EventSubConfig,
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      broadcasterUserId: String,
      accessToken: String,
      twitchConfig: com.archimond7450.archiemate.settings.TwitchConfig
  )(onComplete: Either[String, List[HelixSubscription]] => Unit)(using timeout: Timeout, ec: ExecutionContext): Unit = {
    val uri = Uri(config.helixBaseUrl)
      .withPath(Uri.Path("/eventsub/subscriptions"))
      .withQuery(Uri.Query("broadcaster_user_id" -> broadcasterUserId))

    val probeRef: ActorRef[StatusReply[Any]] = ctx.messageAdapter[StatusReply[Any]] { statusReply =>
      statusReply match {
        case StatusReply.Success(value) =>
          value match {
            case resp: HelixListSubscriptionResponse =>
              onComplete(Right(resp.data))
            case _ =>
              onComplete(Left("Unexpected response type"))
          }
        case StatusReply.Error(err) =>
          onComplete(Left(err.getMessage))
      }
      HttpRequestReply(statusReply)
    }

    httpRequestActor ! HttpRequestActor.Request[HelixListSubscriptionResponse](
      method = HttpMethods.GET,
      uri = uri,
      headers = Seq(
        Authorization(org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken(accessToken)),
        RawHeader("Client-Id", twitchConfig.clientId)
      ),
      entity = org.apache.pekko.http.scaladsl.model.HttpEntity.Empty,
      decode = str => parser.decode[HelixListSubscriptionResponse](str).toTry,
      replyTo = probeRef
    )
  }

  private def revokeSubscription(
      ctx: ActorContext[Command],
      config: EventSubConfig,
      subscriptionId: String,
      broadcasterUserId: String,
      accessToken: String,
      replyTo: ActorRef[RevokeResponse],
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      twitchConfig: com.archimond7450.archiemate.settings.TwitchConfig
  )(using timeout: Timeout, ec: ExecutionContext): Unit = {
    revokeSingleSubscription(ctx, config, httpRequestActor, accessToken, subscriptionId, broadcasterUserId, twitchConfig) {
      case Right(id) => replyTo ! RevokeSuccess(id)
      case Left(msg) => replyTo ! RevokeFailed(msg)
    }
  }

  private def revokeSingleSubscription(
      ctx: ActorContext[Command],
      config: EventSubConfig,
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      accessToken: String,
      subscriptionId: String,
      broadcasterUserId: String,
      twitchConfig: com.archimond7450.archiemate.settings.TwitchConfig
  )(onComplete: Either[String, String] => Unit)(using timeout: Timeout, ec: ExecutionContext): Unit = {
    val uri = Uri(config.helixBaseUrl)
      .withPath(Uri.Path(s"/eventsub/subscriptions/${subscriptionId}"))

    val probeRef: ActorRef[StatusReply[Any]] = ctx.messageAdapter[StatusReply[Any]] { statusReply =>
      statusReply match {
        case StatusReply.Success(value) =>
          value match {
            case resp: HelixDeleteSubscriptionResponse =>
              onComplete(Right(resp.data.headOption.map(_.id).getOrElse(subscriptionId)))
            case _ =>
              onComplete(Left("Unexpected response type"))
          }
        case StatusReply.Error(err) =>
          onComplete(Left(err.getMessage))
      }
      HttpRequestReply(statusReply)
    }

    httpRequestActor ! HttpRequestActor.Request[HelixDeleteSubscriptionResponse](
      method = HttpMethods.DELETE,
      uri = uri,
      headers = Seq(
        Authorization(org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken(accessToken)),
        RawHeader("Client-Id", twitchConfig.clientId)
      ),
      entity = org.apache.pekko.http.scaladsl.model.HttpEntity.Empty,
      decode = str => parser.decode[HelixDeleteSubscriptionResponse](str).toTry,
      replyTo = probeRef
    )
  }
}
