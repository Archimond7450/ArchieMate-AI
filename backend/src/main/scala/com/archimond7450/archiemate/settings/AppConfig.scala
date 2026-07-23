package com.archimond7450.archiemate.settings

import com.typesafe.config.{Config, ConfigResolveOptions}
import scala.jdk.CollectionConverters._
import scala.concurrent.duration.*

case class ServerConfig(
    host: String,
    port: Int,
    apiVersion: String
)

case class DatabaseConfig(
    url: String,
    user: String,
    password: String,
    driver: String
)

case class JwtConfig(
    secret: String,
    tokenLifetimeMinutes: Int
)

case class TwitchConfig(
    clientId: String,
    clientSecret: String,
    callbackPath: String,
    scopes: List[String]
)

case class KickConfig(
    clientId: String,
    clientSecret: String,
    callbackPath: String
)

case class YoutubeConfig(
    clientId: String,
    clientSecret: String,
    callbackPath: String
)

case class WebSocketConfig(
    reconnectDelay: FiniteDuration,
    maxReconnectAttempts: Int
)

object WebSocketConfig {
  def apply(): WebSocketConfig = WebSocketConfig(1.second, 5)
}

case class HttpClientConfig(
    maxConnections: Int,
    maxIdleTimeoutMinutes: Int
)

case class AppConfig(
    server: ServerConfig,
    database: DatabaseConfig,
    jwt: JwtConfig,
    twitch: TwitchConfig,
    kick: KickConfig,
    youtube: YoutubeConfig,
    websocket: WebSocketConfig,
    httpClient: HttpClientConfig,
    callbackBaseUrl: String,
    adminUserId: String,
    askTimeout: FiniteDuration
)

object AppConfig {
  def apply(config: Config): AppConfig = {
    // Resolve environment variable substitutions
    val resolved = config.resolve(ConfigResolveOptions.defaults())
    val serverConf = resolved.getConfig("archiemate.server")
    val dbConf = resolved.getConfig("archiemate.database")
    val twitchConf = resolved.getConfig("archiemate.twitch")

    AppConfig(
      server = ServerConfig(
        host = resolveString(serverConf, "host", "0.0.0.0"),
        port = resolveInt(serverConf, "port", 8080),
        apiVersion = resolveString(serverConf, "api-version", "v1")
      ),
      database = DatabaseConfig(
        url = resolveString(dbConf, "url", ""),
        user = resolveString(dbConf, "user", ""),
        password = resolveString(dbConf, "password", ""),
        driver = resolveString(dbConf, "driver", "org.postgresql.Driver")
      ),
      jwt = JwtConfig(
        secret = resolveString(resolved.getConfig("archiemate.jwt"), "secret", ""),
        tokenLifetimeMinutes = resolveInt(resolved.getConfig("archiemate.jwt"), "token-lifetime-minutes", 15)
      ),
      twitch = TwitchConfig(
        clientId = resolveString(twitchConf, "client-id", ""),
        clientSecret = resolveString(twitchConf, "client-secret", ""),
        callbackPath = resolveString(twitchConf, "callback-path", ""),
        scopes = resolveScopes(twitchConf)
      ),
      kick = {
        val kickConf = resolved.getConfig("archiemate.kick")
        KickConfig(
          clientId = resolveString(kickConf, "client-id", ""),
          clientSecret = resolveString(kickConf, "client-secret", ""),
          callbackPath = resolveString(kickConf, "callback-path", "")
        )
      },
      youtube = {
        val youtubeConf = resolved.getConfig("archiemate.youtube")
        YoutubeConfig(
          clientId = resolveString(youtubeConf, "client-id", ""),
          clientSecret = resolveString(youtubeConf, "client-secret", ""),
          callbackPath = resolveString(youtubeConf, "callback-path", "")
        )
      },
      websocket = WebSocketConfig(
        reconnectDelay = resolveDuration(resolved.getConfig("archiemate.websocket"), "reconnect-delay", 1.second),
        maxReconnectAttempts = resolveInt(resolved.getConfig("archiemate.websocket"), "max-reconnect-attempts", 5)
      ),
      httpClient = HttpClientConfig(
        maxConnections = resolveInt(resolved.getConfig("archiemate.http-client"), "max-connections", 10),
        maxIdleTimeoutMinutes = resolveInt(resolved.getConfig("archiemate.http-client"), "max-idle-timeout-minutes", 60)
      ),
      callbackBaseUrl = resolveString(resolved.getConfig("archiemate"), "callback-base-url", ""),
      adminUserId = resolveString(resolved.getConfig("archiemate"), "admin-user-id", ""),
      askTimeout = resolveDuration(resolved.getConfig("archiemate"), "ask-timeout", 5.seconds)
    )
  }

  private def isUnresolvedSubstitution(value: String): Boolean = {
    value.trim.startsWith("${") && value.trim.endsWith("}")
  }

  private def resolveString(conf: Config, key: String, default: String): String = {
    val envKey = key.toUpperCase.replace("-", "_")
    sys.env.get(envKey).filter(_.nonEmpty).getOrElse {
      if (conf.hasPathOrNull(key)) {
        val value = conf.getString(key)
        if (isUnresolvedSubstitution(value)) default else value
      } else {
        default
      }
    }
  }

  private def resolveScopes(conf: Config, key: String = "scopes", default: List[String] = List.empty): List[String] = {
    val envKey = key.toUpperCase.replace("-", "_")
    sys.env.get(envKey).filter(_.nonEmpty).map(_.split(",").map(_.trim).toList).getOrElse {
      if (conf.hasPathOrNull(key)) {
        if (conf.hasPathOrNull(key) && conf.getValue(key).unwrapped().isInstanceOf[java.util.List[_]]) {
          conf.getStringList(key).asScala.toList
        } else {
          val value = conf.getString(key)
          if (isUnresolvedSubstitution(value) || value.trim.isEmpty) default
          else value.split(",").map(_.trim).toList
        }
      } else {
        default
      }
    }
  }

  private def resolveInt(conf: Config, key: String, default: Int): Int = {
    val envKey = key.toUpperCase.replace("-", "_")
    sys.env.get(envKey).filter(_.nonEmpty).map(_.toInt).getOrElse {
      if (conf.hasPathOrNull(key)) {
        val value = conf.getString(key)
        if (isUnresolvedSubstitution(value)) default else conf.getInt(key)
      } else {
        default
      }
    }
  }

  private def resolveDuration(conf: Config, key: String, default: FiniteDuration): FiniteDuration = {
    val envKey = key.toUpperCase.replace("-", "_")
    sys.env.get(envKey).filter(_.nonEmpty).map(parseDuration).getOrElse {
      if (conf.hasPathOrNull(key)) {
        val value = conf.getString(key)
        if (isUnresolvedSubstitution(value)) default else parseDuration(value)
      } else {
        default
      }
    }
  }

  private def parseDuration(s: String): FiniteDuration = {
    val regex = """^(\d+)([smh])$""".r
    s.trim match {
      case regex(n, "s") => n.toLong.seconds
      case regex(n, "m") => n.toLong.minutes
      case regex(n, "h") => n.toLong.hours
      case _ => throw new IllegalArgumentException(s"Invalid duration format: $s (expected e.g. '5s', '10m', '1h')")
    }
  }
}
