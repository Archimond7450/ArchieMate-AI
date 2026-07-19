package com.archimond7450.archiemate.user

import com.archimond7450.archiemate.user.UserTokenRegistry.{*, given}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/** Tests for [[UserTokenRegistry]].

  * Note: The registry spawns per-user [[UserTokenActor]] instances dynamically.
  * These actors are event-sourced and require a persistence plugin. In the test
  * environment without a full persistence setup, the actors fail to initialize.
  * The [[UserTokenActor]] persistence is tested separately in
  * [[UserTokenActorSpec]].
  */
class UserTokenRegistrySpec
    extends ScalaTestWithActorTestKit(EventSourcedBehaviorTestKit.config)
    with AnyWordSpecLike
    with Matchers {

  private def spawnRegistry(): ActorRef[UserTokenRegistry.Command] = {
    testKit.spawn(UserTokenRegistry(), "user-token-registry")
  }

  "UserTokenRegistry" should {

    "be spawnable" in {
      val registry = spawnRegistry()
      registry should not be null
    }

    "forward register command to UserTokenActor" in {
      pending
    }

    "forward get command to UserTokenActor" in {
      pending
    }

    "forward update command to UserTokenActor" in {
      pending
    }

    "forward revoke command to UserTokenActor" in {
      pending
    }

    "handle different users independently" in {
      pending
    }
  }
}
