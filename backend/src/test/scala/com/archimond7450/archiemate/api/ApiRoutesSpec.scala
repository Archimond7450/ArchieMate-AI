package com.archimond7450.archiemate.api

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.archimond7450.archiemate.settings.{AppConfig, DatabaseConfig, ServerConfig}

class ApiRoutesSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest {

  private val testConfig: AppConfig = new AppConfig(
    server = ServerConfig(host = "127.0.0.1", port = 8080, apiVersion = "v1"),
    database = DatabaseConfig(
      url = "jdbc:postgresql://localhost:5432/archiemate",
      user = "test",
      password = "test",
      driver = "org.postgresql.Driver"
    )
  )

  private val routes = new ApiRoutes(testConfig)

  "GET /api/v1/live" should "return No Content" in {
    Get("/api/v1/live") ~> routes.apiRoutes ~> check {
      status shouldBe StatusCodes.NoContent
    }
  }

  "GET /api/v1/ready" should "return No Content" in {
    Get("/api/v1/ready") ~> routes.apiRoutes ~> check {
      status shouldBe StatusCodes.NoContent
    }
  }

  // Note: Non-matching paths return 404 (standard Pekko HTTP behavior)
  // Testing this requires checking the raw response, which is beyond basic route testing
}
