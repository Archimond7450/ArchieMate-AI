package com.archimond7450.archiemate

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ReadinessTrackerSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers {

  private val probe = testKit.createTestProbe[ReadinessTracker.ReadinessResponse]("response")

  private def spawnTracker(name: String): ActorRef[ReadinessTracker.Command] =
    testKit.spawn(ReadinessTracker.supervised(), s"tracker-$name")

  "ReadinessTracker" should {

    "return NotReadyResponse when no actors are registered" in {
      val tracker = spawnTracker("1")
      tracker ! ReadinessTracker.CheckReadiness(probe.ref)
      probe.expectMessage(ReadinessTracker.NotReadyResponse)
    }

    "return NotReadyResponse when a registered actor has not sent Ready" in {
      val tracker = spawnTracker("2")
      val actor = testKit.createTestProbe[ReadinessTracker.ReadySignal]("actor").ref
      tracker ! ReadinessTracker.Register(actor)
      tracker ! ReadinessTracker.CheckReadiness(probe.ref)
      probe.expectMessage(ReadinessTracker.NotReadyResponse)
    }

    "return ReadyResponse when the only registered actor has sent Ready" in {
      val tracker = spawnTracker("3")
      val actor = testKit.createTestProbe[ReadinessTracker.ReadySignal]("actor").ref

      tracker ! ReadinessTracker.Register(actor)
      tracker ! ReadinessTracker.Ready(actor)
      tracker ! ReadinessTracker.CheckReadiness(probe.ref)
      probe.expectMessage(ReadinessTracker.ReadyResponse)
    }

    "return NotReadyResponse when only some registered actors have sent Ready" in {
      val tracker = spawnTracker("4")
      val actorA = testKit.createTestProbe[ReadinessTracker.ReadySignal]("actorA").ref
      val actorB = testKit.createTestProbe[ReadinessTracker.ReadySignal]("actorB").ref

      tracker ! ReadinessTracker.Register(actorA)
      tracker ! ReadinessTracker.Register(actorB)
      tracker ! ReadinessTracker.Ready(actorA)
      tracker ! ReadinessTracker.CheckReadiness(probe.ref)
      probe.expectMessage(ReadinessTracker.NotReadyResponse)
    }

    "return ReadyResponse when all registered actors have sent Ready" in {
      val tracker = spawnTracker("5")
      val actorA = testKit.createTestProbe[ReadinessTracker.ReadySignal]("actorA").ref
      val actorB = testKit.createTestProbe[ReadinessTracker.ReadySignal]("actorB").ref

      tracker ! ReadinessTracker.Register(actorA)
      tracker ! ReadinessTracker.Register(actorB)
      tracker ! ReadinessTracker.Ready(actorA)
      tracker ! ReadinessTracker.Ready(actorB)
      tracker ! ReadinessTracker.CheckReadiness(probe.ref)
      probe.expectMessage(ReadinessTracker.ReadyResponse)
    }

    "ignore Ready from actors that are not registered" in {
      val tracker = spawnTracker("6")
      val registered = testKit.createTestProbe[ReadinessTracker.ReadySignal]("registered").ref
      val unregistered = testKit.createTestProbe[ReadinessTracker.ReadySignal]("unregistered").ref

      tracker ! ReadinessTracker.Register(registered)
      tracker ! ReadinessTracker.Ready(unregistered)
      tracker ! ReadinessTracker.CheckReadiness(probe.ref)
      probe.expectMessage(ReadinessTracker.NotReadyResponse)
    }
  }
}
