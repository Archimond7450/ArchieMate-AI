package com.archimond7450.archiemate.api

import com.archimond7450.archiemate.ReadinessTracker
import com.archimond7450.archiemate.ReadinessTracker.ReadyResponse
import com.archimond7450.archiemate.ReadinessTracker.NotReadyResponse
import com.archimond7450.archiemate.settings.{AppConfig, DatabaseConfig, ServerConfig}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import org.apache.pekko.actor.typed.ActorSystem as TypedActorSystem
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

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

  private val classicSystem: ActorSystem = ActorSystem("test-classic")
  private given typedSystem: TypedActorSystem[Nothing] = classicSystem.toTyped
  private given Scheduler = typedSystem.scheduler
  private given Timeout = Timeout(3.seconds)

  private val readinessProbe: TestProbe[ReadinessTracker.Command] =
    TestProbe[ReadinessTracker.Command]("readiness-tracker")
  private val readinessTracker = readinessProbe.ref

  private val apiRoutes = new ApiRoutes(
    testConfig,
    readinessTracker,
    classicSystem
  ).apiRoutes

  "GET /api/v1/live" should {
    "return No Content" in {
      Get("/api/v1/live") ~> apiRoutes ~> check {
        status shouldEqual StatusCodes.NoContent
      }
    }
  }

  "GET /api/v1/ready" should {
    "return No Content when readinessTracker responds Ready" in {
      val test = Get("/api/v1/ready") ~> apiRoutes

      readinessProbe.expectMessageType[ReadinessTracker.CheckReadiness] match {
        case ReadinessTracker.CheckReadiness(replyTo) =>
          replyTo ! ReadyResponse
      }

      test ~> check {
        status shouldEqual StatusCodes.NoContent
      }
    }

    "return 503 ServiceUnavailable when readinessTracker responds NotReady" in {
      val test = Get("/api/v1/ready") ~> apiRoutes

      readinessProbe.expectMessageType[ReadinessTracker.CheckReadiness] match {
        case ReadinessTracker.CheckReadiness(replyTo) =>
          replyTo ! NotReadyResponse
      }

      test ~> check {
        status shouldEqual StatusCodes.ServiceUnavailable
      }
    }
  }
}
