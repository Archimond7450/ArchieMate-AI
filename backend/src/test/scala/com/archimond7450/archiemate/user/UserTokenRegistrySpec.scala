package com.archimond7450.archiemate.user

import com.archimond7450.archiemate.user.UserTokenRegistry.{*, given}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

/** Tests for [[UserTokenRegistry]].
  *
  * Note: The registry spawns per-user [[UserTokenActor]] instances dynamically.
  * These actors are event-sourced and require a persistence plugin. The
  * `pekko-persistence-jpmc-inmem` in-memory plugin referenced in test
  * resources is not available as a dependency for Pekko 1.x / Scala 3.
  *
  * The [[UserTokenActor]] persistence is tested separately in
  * [[UserTokenActorSpec]] using [[org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit]].
  *
  * To unskip these tests, add the in-memory persistence plugin dependency:
  *   "org.apache.pekko.persistence.jpmc" %% "pekko-persistence-jpmc-inmem" % "<version>" % Test
  * and update the test config to reference it.
  */
class UserTokenRegistrySpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers {

  private val testUserId = "test-user-123"
  private val testPlatform = "twitch"
  private val now = Instant.now()
  private val expiresAt = now.plusSeconds(3600)

  "UserTokenRegistry" should {

    "be spawnable" in {
      val registry = testKit.spawn(UserTokenRegistry(), s"user-token-registry-${java.util.UUID.randomUUID().toString.take(8)}")
      registry should not be null
    }

    "forward register command to UserTokenActor" in pending
    "forward get command to UserTokenActor" in pending
    "forward update command to UserTokenActor" in pending
    "forward revoke command to UserTokenActor" in pending
    "handle different users independently" in pending
  }
}
