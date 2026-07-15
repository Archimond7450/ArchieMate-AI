package com.archimond7450.archiemate.api

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.archimond7450.archiemate.ReadinessTracker
import com.archimond7450.archiemate.settings.{AppConfig, DatabaseConfig, ServerConfig}

class ApiRoutesSpec
    extends AnyWordSpecLike
    with Matchers
    with ScalatestRouteTest {

  private val testConfig: AppConfig = new AppConfig(
    server = ServerConfig(host = "127.0.0.1", port = 8080, apiVersion = "v1"),
    database = DatabaseConfig(
      url = "jdbc:postgresql://localhost:5432/archiemate",
      user = "test",
      password = "test",
      driver = "org.postgresql.Driver"
    )
  )

  // Provide typed ActorSystem as implicit for TestProbe
  private val typedSystem: org.apache.pekko.actor.typed.ActorSystem[Nothing] = system.toTyped
  given org.apache.pekko.actor.typed.ActorSystem[Nothing] = typedSystem

  private val readinessProbe: TestProbe[ReadinessTracker.ReadinessResponse] =
    TestProbe[ReadinessTracker.ReadinessResponse]("readiness-response")
  private val readinessTracker: ActorRef[ReadinessTracker.Command] = {
    val trackerSystem = org.apache.pekko.actor.typed.ActorSystem(
      ReadinessTracker.supervised(),
      "readiness-tracker"
    )
    trackerSystem.asInstanceOf[ActorRef[ReadinessTracker.Command]]
  }

  private val routes = new ApiRoutes(
    testConfig,
    readinessTracker,
    system
  )

  "GET /api/v1/live" should {
    "return No Content" in {
      Get("/api/v1/live") ~> routes.apiRoutes ~> check {
        status shouldBe StatusCodes.NoContent
      }
    }
  }

  "GET /api/v1/ready" should {
    "return ServiceUnavailable when no actors are registered" in {
      Get("/api/v1/ready") ~> routes.apiRoutes ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
      }
    }
  }

  // Note: Non-matching paths return 404 (standard Pekko HTTP behavior)
  // Testing this requires checking the raw response, which is beyond basic route testing
}
