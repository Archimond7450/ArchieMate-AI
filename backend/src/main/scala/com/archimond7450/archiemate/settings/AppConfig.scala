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

case class AppConfig(
    server: ServerConfig,
    database: DatabaseConfig
)

object AppConfig {
  def apply(config: Config): AppConfig = {
    // Resolve environment variable substitutions
    val resolved = config.resolve(ConfigResolveOptions.defaults())
    val serverConf = resolved.getConfig("archiemate.server")
    val dbConf = resolved.getConfig("archiemate.database")

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
