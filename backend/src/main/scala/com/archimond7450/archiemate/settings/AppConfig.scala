package com.archimond7450.archiemate.settings

import com.typesafe.config.{Config, ConfigResolveOptions}

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
    redirectUriPostfix: String
)

case class HttpClientConfig(
    maxConnections: Int,
    maxIdleTimeoutMinutes: Int
)

case class AppConfig(
    server: ServerConfig,
    database: DatabaseConfig,
    jwt: JwtConfig,
    twitch: TwitchConfig,
    httpClient: HttpClientConfig
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
        redirectUriPostfix = resolveString(twitchConf, "redirect-uri-postfix", "")
      ),
      httpClient = HttpClientConfig(
        maxConnections = resolveInt(resolved.getConfig("archiemate.http-client"), "max-connections", 10),
        maxIdleTimeoutMinutes = resolveInt(resolved.getConfig("archiemate.http-client"), "max-idle-timeout-minutes", 60)
      )
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
}
