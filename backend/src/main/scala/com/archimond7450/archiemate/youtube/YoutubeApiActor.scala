package com.archimond7450.archiemate.youtube

import com.archimond7450.archiemate.actors.http.HttpRequestActor
import com.archimond7450.archiemate.settings.YoutubeConfig
import com.archimond7450.archiemate.user.UserTokenRegistry
import com.archimond7450.archiemate.user.UserTokenRegistry.{*, given}
import io.circe.Decoder
import io.circe.parser.decode
import org.apache.pekko.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.http.scaladsl.model.HttpMethods
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.util.Timeout

import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext

/** Dedicated actor for YouTube API operations: token management and user
  * info retrieval.
  *
  * This actor encapsulates all YouTube API HTTP calls, including:
  *   - Token refresh (automatic on HTTP 401)
  *   - User info retrieval (by access token)
  *   - Proper header construction (Bearer)
  *
  * @note
  * This actor must be supervised by its parent with a resume strategy on
  * [[Throwable]] to preserve state across failures:
  * {{{supervise(YoutubeApiActor(...)).onFailure[Throwable](SupervisorStrategy.resume)}}}
  */
object YoutubeApiActor {

  private val actorName = "youtube-api-actor"

  // ----------------------------------------------------------------
  // Commands
  // ----------------------------------------------------------------

  sealed trait Command

  /** Internal command to signal HTTP response received. */
  private final case class HttpRequestReply(reply: StatusReply[Any]) extends Command

  /** Refresh an access token using its refresh token. */
  final case class RefreshToken(
      refreshToken: String,
      replyTo: ActorRef[TokenResponse]
  ) extends Command

  /** Get current user info by access token. */
  final case class GetCurrentUser(
      accessToken: String,
      replyTo: ActorRef[TokenResponse]
  ) extends Command

  /** Get latest uploaded videos from a channel.
    *
    * @param channelId   YouTube channel ID to fetch videos from
    * @param accessToken OAuth access token with appropriate scopes
    * @param maxResults  Maximum number of videos to return (default: 10)
    */
  final case class GetLatestVideos(
      channelId: String,
      accessToken: String,
      maxResults: Int = 10,
      replyTo: ActorRef[TokenResponse]
  ) extends Command

  // ----------------------------------------------------------------
  // Responses
  // ----------------------------------------------------------------

  sealed trait TokenResponse

  final case class TokenRefreshed(
      accessToken: String,
      refreshToken: String,
      expiresIn: Long
  ) extends TokenResponse

  final case class UserFound(
      id: String,
      email: String,
      verifiedEmail: Boolean,
      name: String,
      picture: Option[String]
  ) extends TokenResponse

  final case class VideoList(
      videos: List[VideoInfo]
  ) extends TokenResponse

  final case class VideoInfo(
      videoId: String,
      title: String,
      publishedAt: String,
      thumbnailUrl: String
  )

  final case class Error(message: String) extends TokenResponse

  // ----------------------------------------------------------------
  // Internal response models
  // ----------------------------------------------------------------

  private case class YoutubeTokenResponse(
      accessToken: String,
      refreshToken: String,
      expiresIn: Long,
      scope: String
  )

  private object YoutubeTokenResponse {
    implicit val decoder: Decoder[YoutubeTokenResponse] = Decoder.forProduct4(
      "access_token",
      "refresh_token",
      "expires_in",
      "scope"
    )(YoutubeTokenResponse.apply)
  }

  private case class YoutubeUserInfoResponse(
      id: String,
      email: String,
      verifiedEmail: Boolean,
      name: String,
      picture: Option[String]
  )

  private object YoutubeUserInfoResponse {
    implicit val decoder: Decoder[YoutubeUserInfoResponse] = Decoder.instance { c =>
      for {
        id <- c.downField("id").as[String]
        email <- c.downField("email").as[String]
        verifiedEmail <- c.downField("verified_email").as[Boolean]
        name <- c.downField("name").as[String]
        picture <- c.downField("picture").as[Option[String]]
      } yield YoutubeUserInfoResponse(id, email, verifiedEmail, name, picture)
    }
  }

  // YouTube Data API search response models

  private case class YoutubeSearchResponse(
      items: List[SearchItem]
  )

  private object YoutubeSearchResponse {
    implicit val decoder: Decoder[YoutubeSearchResponse] = Decoder.instance { c =>
      for {
        items <- c.downField("items").as[List[SearchItem]]
      } yield YoutubeSearchResponse(items)
    }
  }

  private case class SearchItem(
      id: SearchId,
      snippet: VideoSnippet
  )

  private object SearchItem {
    implicit val decoder: Decoder[SearchItem] = Decoder.instance { c =>
      for {
        id <- c.downField("id").as[SearchId]
        snippet <- c.downField("snippet").as[VideoSnippet]
      } yield SearchItem(id, snippet)
    }
  }

  private case class SearchId(
      videoId: Option[String]
  )

  private object SearchId {
    implicit val decoder: Decoder[SearchId] = Decoder.instance { c =>
      for {
        videoId <- c.downField("videoId").as[Option[String]]
      } yield SearchId(videoId)
    }
  }

  private case class VideoSnippet(
      title: String,
      publishedAt: String,
      thumbnails: ThumbnailSet
  )

  private object VideoSnippet {
    implicit val decoder: Decoder[VideoSnippet] = Decoder.instance { c =>
      for {
        title <- c.downField("title").as[String]
        publishedAt <- c.downField("publishedAt").as[String]
        thumbnails <- c.downField("thumbnails").as[ThumbnailSet]
      } yield VideoSnippet(title, publishedAt, thumbnails)
    }
  }

  private case class ThumbnailSet(
      high: Option[Thumbnail]
  )

  private object ThumbnailSet {
    implicit val decoder: Decoder[ThumbnailSet] = Decoder.instance { c =>
      for {
        high <- c.downField("high").as[Option[Thumbnail]]
      } yield ThumbnailSet(high)
    }
  }

  private case class Thumbnail(
      url: String
  )

  private object Thumbnail {
    implicit val decoder: Decoder[Thumbnail] = Decoder.instance { c =>
      for {
        url <- c.downField("url").as[String]
      } yield Thumbnail(url)
    }
  }

  // YouTube Data API channel response
  private case class YoutubeChannelResponse(
      items: List[ChannelItem]
  )

  private object YoutubeChannelResponse {
    implicit val decoder: Decoder[YoutubeChannelResponse] = Decoder.instance { c =>
      for {
        items <- c.downField("items").as[List[ChannelItem]]
      } yield YoutubeChannelResponse(items)
    }
  }

  private case class ChannelItem(
      contentDetails: ChannelContentDetails
  )

  private object ChannelItem {
    implicit val decoder: Decoder[ChannelItem] = Decoder.instance { c =>
      for {
        contentDetails <- c.downField("contentDetails").as[ChannelContentDetails]
      } yield ChannelItem(contentDetails)
    }
  }

  private case class ChannelContentDetails(
      uploadPlaylistId: String
  )

  private object ChannelContentDetails {
    implicit val decoder: Decoder[ChannelContentDetails] = Decoder.instance { c =>
      for {
        uploadPlaylistId <- c.downField("uploadPlaylistId").as[String]
      } yield ChannelContentDetails(uploadPlaylistId)
    }
  }

  // YouTube Data API playlist items response
  private case class YoutubePlaylistResponse(
      items: List[PlaylistItem]
  )

  private object YoutubePlaylistResponse {
    implicit val decoder: Decoder[YoutubePlaylistResponse] = Decoder.instance { c =>
      for {
        items <- c.downField("items").as[List[PlaylistItem]]
      } yield YoutubePlaylistResponse(items)
    }
  }

  private case class PlaylistItem(
      snippet: PlaylistItemSnippet
  )

  private object PlaylistItem {
    implicit val decoder: Decoder[PlaylistItem] = Decoder.instance { c =>
      for {
        snippet <- c.downField("snippet").as[PlaylistItemSnippet]
      } yield PlaylistItem(snippet)
    }
  }

  private case class PlaylistItemSnippet(
      title: String,
      publishedAt: String,
      resourceId: ResourceId,
      thumbnails: ThumbnailSet
  )

  private object PlaylistItemSnippet {
    implicit val decoder: Decoder[PlaylistItemSnippet] = Decoder.instance { c =>
      for {
        title <- c.downField("title").as[String]
        publishedAt <- c.downField("publishedAt").as[String]
        resourceId <- c.downField("resourceId").as[ResourceId]
        thumbnails <- c.downField("thumbnails").as[ThumbnailSet]
      } yield PlaylistItemSnippet(title, publishedAt, resourceId, thumbnails)
    }
  }

  private case class ResourceId(
      videoId: Option[String]
  )

  private object ResourceId {
    implicit val decoder: Decoder[ResourceId] = Decoder.instance { c =>
      for {
        videoId <- c.downField("videoId").as[Option[String]]
      } yield ResourceId(videoId)
    }
  }

  // ----------------------------------------------------------------
  // Default YouTube API base URLs (overridable for testing)
  // ----------------------------------------------------------------

  val DefaultBaseUrl = "https://www.googleapis.com"
  val DefaultAuthBaseUrl = "https://oauth2.googleapis.com"

  // ----------------------------------------------------------------
  // Factory
  // ----------------------------------------------------------------

  def apply(
      config: YoutubeConfig,
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      userTokenRegistry: ActorRef[UserTokenRegistry.Command],
      baseUrl: String = DefaultBaseUrl,
      authBaseUrl: String = DefaultAuthBaseUrl
  ): Behavior[Command] =
    Behaviors.supervise[Command] {
      Behaviors.setup { ctx =>
        ctx.log.info(
          "YoutubeApiActor initialized for client {}",
          config.clientId
        )
        mainBehavior(
          config,
          httpRequestActor,
          userTokenRegistry,
          baseUrl,
          authBaseUrl
        )(using
          ctx,
          scheduler = ctx.system.scheduler,
          timeout = Timeout(5.seconds),
          execEc = scala.concurrent.ExecutionContext.global
        )
      }
    }.onFailure[Throwable](SupervisorStrategy.resume)

  // ----------------------------------------------------------------
  // Behavior
  // ----------------------------------------------------------------

  private def mainBehavior(
      config: YoutubeConfig,
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      userTokenRegistry: ActorRef[UserTokenRegistry.Command],
      baseUrl: String,
      authBaseUrl: String
  )(using
      ctx: ActorContext[Command],
      scheduler: Scheduler,
      timeout: Timeout,
      execEc: ExecutionContext
  ): Behavior[Command] =
    Behaviors.withMdc(Map("actor" -> actorName))(
      Behaviors.receiveMessage {
        case refresh: RefreshToken =>
          refreshToken(config, refresh.refreshToken, refresh.replyTo, httpRequestActor, authBaseUrl)
          Behaviors.same

        case getCurrentUserCmd: GetCurrentUser =>
          getCurrentUser(config, getCurrentUserCmd.accessToken, getCurrentUserCmd.replyTo, httpRequestActor, baseUrl)
          Behaviors.same

        case getVideos: GetLatestVideos =>
          getLatestVideos(
            config,
            getVideos.channelId,
            getVideos.accessToken,
            getVideos.maxResults,
            getVideos.replyTo,
            httpRequestActor,
            baseUrl
          )
          Behaviors.same

        case HttpRequestReply(_) =>
          // Internal reply from message adapter — should not reach here
          Behaviors.same
      }
    )

  // ----------------------------------------------------------------
  // Helpers
  // ----------------------------------------------------------------

  private def handleApiError(
      err: Throwable,
      replyTo: ActorRef[TokenResponse],
      context: String
  )(using
      ctx: ActorContext[Command]
  ): Unit = {
    val isUnauthorized = err.getMessage.toLowerCase.contains("401")
    if (isUnauthorized) {
      replyTo ! Error(s"$context: token expired or invalid (HTTP 401)")
    } else {
      replyTo ! Error(s"$context: ${err.getMessage}")
    }
  }

  // ----------------------------------------------------------------
  // HTTP Request Handlers
  // ----------------------------------------------------------------

  private def refreshToken(
      config: YoutubeConfig,
      refreshToken: String,
      replyTo: ActorRef[TokenResponse],
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      authBaseUrl: String
  )(using
      ctx: ActorContext[Command],
      scheduler: Scheduler,
      timeout: Timeout,
      execEc: ExecutionContext
  ): Unit = {
    val body = Seq(
      s"client_id=${config.clientId}",
      s"client_secret=${config.clientSecret}",
      s"grant_type=refresh_token",
      s"refresh_token=${refreshToken}"
    ).mkString("&")

    val probeRef: ActorRef[StatusReply[Any]] = ctx.messageAdapter[StatusReply[Any]] { statusReply =>
      statusReply match {
        case StatusReply.Success(value) =>
          value match {
            case resp: YoutubeTokenResponse =>
              replyTo ! TokenRefreshed(resp.accessToken, resp.refreshToken, resp.expiresIn)
            case _ =>
              replyTo ! Error(s"Token refresh failed: unexpected response type")
          }
        case StatusReply.Error(err) =>
          replyTo ! Error(s"Token refresh failed: ${err.getMessage}")
      }
      HttpRequestReply(statusReply)
    }

    httpRequestActor ! HttpRequestActor.Request[YoutubeTokenResponse](
      method = HttpMethods.POST,
      uri = Uri(s"$authBaseUrl/oauth2/token"),
      headers = Seq(RawHeader("Content-Type", "application/x-www-form-urlencoded")),
      entity = org.apache.pekko.http.scaladsl.model.HttpEntity(
        org.apache.pekko.http.scaladsl.model.MediaTypes.`application/x-www-form-urlencoded`,
        body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
      ),
      decode = str => decode[YoutubeTokenResponse](str).toTry,
      replyTo = probeRef
    )
  }

  private def getCurrentUser(
      config: YoutubeConfig,
      accessToken: String,
      replyTo: ActorRef[TokenResponse],
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      baseUrl: String
  )(using
      ctx: ActorContext[Command],
      scheduler: Scheduler,
      timeout: Timeout,
      execEc: ExecutionContext
  ): Unit = {
    val query = Uri.Query("access_token" -> accessToken)
    val uri = Uri(s"$baseUrl/oauth2/v3/userinfo").withQuery(query)

    val probeRef: ActorRef[StatusReply[Any]] = ctx.messageAdapter[StatusReply[Any]] { statusReply =>
      statusReply match {
        case StatusReply.Success(value) =>
          value match {
            case user: YoutubeUserInfoResponse =>
              replyTo ! UserFound(user.id, user.email, user.verifiedEmail, user.name, user.picture)
            case _ =>
              replyTo ! Error("Get current user: unexpected response type")
          }
        case StatusReply.Error(err) =>
          handleApiError(err, replyTo, "Get current user")
      }
      HttpRequestReply(statusReply)
    }

    httpRequestActor ! HttpRequestActor.Request[YoutubeUserInfoResponse](
      method = HttpMethods.GET,
      uri = uri,
      headers = Seq(
        RawHeader("Authorization", s"Bearer $accessToken")
      ),
      entity = org.apache.pekko.http.scaladsl.model.HttpEntity.Empty,
      decode = str => decode[YoutubeUserInfoResponse](str).toTry,
      replyTo = probeRef
    )
  }

  private def getLatestVideos(
      config: YoutubeConfig,
      channelId: String,
      accessToken: String,
      maxResults: Int,
      replyTo: ActorRef[TokenResponse],
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      baseUrl: String
  )(using
      ctx: ActorContext[Command],
      scheduler: Scheduler,
      timeout: Timeout,
      execEc: ExecutionContext
  ): Unit = {
    // First, get the channel's uploads playlist ID
    val channelQuery = Uri.Query(
      "part" -> "contentDetails",
      "forUsername" -> channelId,
      "key" -> config.clientId
    )
    val channelUri = Uri(s"$baseUrl/youtube/v3/channels").withQuery(channelQuery)

    val probeRef: ActorRef[StatusReply[Any]] = ctx.messageAdapter[StatusReply[Any]] { statusReply =>
      statusReply match {
        case StatusReply.Success(value) =>
          value match {
            case channelResp: YoutubeChannelResponse =>
              channelResp.items.headOption match {
                case Some(channel) =>
                  // Got the channel, now fetch its uploads
                  val uploadsPlaylistId = channel.contentDetails.uploadPlaylistId
                  fetchPlaylistItems(config, uploadsPlaylistId, accessToken, maxResults, replyTo, httpRequestActor, baseUrl)
                case None =>
                  replyTo ! Error(s"No channel found for username: $channelId")
              }
            case _ =>
              replyTo ! Error(s"Get latest videos: unexpected response type for channel lookup")
          }
        case StatusReply.Error(err) =>
          handleApiError(err, replyTo, s"Get latest videos (channel lookup for $channelId)")
      }
      HttpRequestReply(statusReply)
    }

    httpRequestActor ! HttpRequestActor.Request[YoutubeChannelResponse](
      method = HttpMethods.GET,
      uri = channelUri,
      headers = Seq(RawHeader("Authorization", s"Bearer $accessToken")),
      entity = org.apache.pekko.http.scaladsl.model.HttpEntity.Empty,
      decode = str => decode[YoutubeChannelResponse](str).toTry,
      replyTo = probeRef
    )
  }

  /** Fetch playlist items from the channel's uploads playlist. */
  private def fetchPlaylistItems(
      config: YoutubeConfig,
      playlistId: String,
      accessToken: String,
      maxResults: Int,
      replyTo: ActorRef[TokenResponse],
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      baseUrl: String
  )(using
      ctx: ActorContext[Command],
      scheduler: Scheduler,
      timeout: Timeout,
      execEc: ExecutionContext
  ): Unit = {
    val query = Uri.Query(
      "part" -> "snippet",
      "playlistId" -> playlistId,
      "maxResults" -> maxResults.toString,
      "type" -> "video"
    )
    val uri = Uri(s"$baseUrl/youtube/v3/playlistItems").withQuery(query)

    val probeRef: ActorRef[StatusReply[Any]] = ctx.messageAdapter[StatusReply[Any]] { statusReply =>
      statusReply match {
        case StatusReply.Success(value) =>
          value match {
            case playlistResp: YoutubePlaylistResponse =>
              val videos = playlistResp.items.flatMap { item =>
                item.snippet.thumbnails.high.map { thumb =>
                  VideoInfo(
                    videoId = item.snippet.resourceId.videoId.getOrElse("unknown"),
                    title = item.snippet.title,
                    publishedAt = item.snippet.publishedAt,
                    thumbnailUrl = thumb.url
                  )
                }
              }
              replyTo ! VideoList(videos)
            case _ =>
              replyTo ! Error(s"Get latest videos: unexpected response type for playlist lookup")
          }
        case StatusReply.Error(err) =>
          handleApiError(err, replyTo, s"Get latest videos (playlist lookup for $playlistId)")
      }
      HttpRequestReply(statusReply)
    }

    httpRequestActor ! HttpRequestActor.Request[YoutubePlaylistResponse](
      method = HttpMethods.GET,
      uri = uri,
      headers = Seq(RawHeader("Authorization", s"Bearer $accessToken")),
      entity = org.apache.pekko.http.scaladsl.model.HttpEntity.Empty,
      decode = str => decode[YoutubePlaylistResponse](str).toTry,
      replyTo = probeRef
    )
  }

}
