package com.archimond7450.archiemate.user

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

class UserTokenActorSpec
    extends ScalaTestWithActorTestKit(EventSourcedBehaviorTestKit.config)
    with AnyWordSpecLike {

  private val testUserId = "test-user-123"

  private def createTestKit(): EventSourcedBehaviorTestKit[
    UserTokenActor.Command,
    UserTokenEvent,
    UserTokenActor.State
  ] = {
    EventSourcedBehaviorTestKit(
      testKit.system,
      UserTokenActor(testUserId),
      EventSourcedBehaviorTestKit.SerializationSettings.disabled
    )
  }

  private val twitchPlatform = "twitch"
  private val kickPlatform = "kick"
  private val now = Instant.now()
  private val expiresAt = now.plusSeconds(3600)

  "UserTokenActor" should {

    // --- Twitch auth token tests ---

    "register a new Twitch auth token" in {
      val testKit = createTestKit()
      val result = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterTwitchAuthToken(
          "access-token-1",
          "refresh-token-1",
          expiresAt,
          "twitch-user-456",
          replyTo
        )
      }

      result.reply match {
        case UserTokenActor.AuthRegistered(id, platformUserId) =>
          id should not be empty
          platformUserId shouldEqual "twitch-user-456"
        case other => fail(s"Expected AuthRegistered, got $other")
      }
    }

    "register multiple Twitch auth tokens (same user, different devices)" in {
      val testKit = createTestKit()

      val reg1Result = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterTwitchAuthToken(
          "device-1-access",
          "device-1-refresh",
          expiresAt,
          "twitch-user-456",
          replyTo
        )
      }
      val token1Id = reg1Result.reply match {
        case UserTokenActor.AuthRegistered(id, _) => id
        case other => fail(s"Expected AuthRegistered, got $other")
      }

      val reg2Result = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterTwitchAuthToken(
          "device-2-access",
          "device-2-refresh",
          expiresAt,
          "twitch-user-456",
          replyTo
        )
      }
      val token2Id = reg2Result.reply match {
        case UserTokenActor.AuthRegistered(id, _) => id
        case other => fail(s"Expected AuthRegistered, got $other")
      }

      // Both tokens should be present and distinct
      val state = testKit.getState()
      state.twitchTokens.size shouldEqual 2
      state.twitchTokens(token1Id).accessToken shouldEqual "device-1-access"
      state.twitchTokens(token2Id).accessToken shouldEqual "device-2-access"

      // Can fetch all tokens
      val getAllResult = testKit.runCommand { replyTo =>
        UserTokenActor.GetAllTwitchAuthTokens(replyTo)
      }
      getAllResult.reply match {
        case UserTokenActor.AllAuthTokensFound(tokens) =>
          tokens.size shouldEqual 2
          tokens.map(_.accessToken).toSet shouldEqual Set("device-1-access", "device-2-access")
        case other => fail(s"Expected AllAuthTokensFound, got $other")
      }
    }

    "get a Twitch auth token" in {
      val testKit = createTestKit()

      val regResult = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterTwitchAuthToken(
          "access-123",
          "refresh-456",
          expiresAt,
          "twitch-user-789",
          replyTo
        )
      }
      val tokenId = regResult.reply match {
        case UserTokenActor.AuthRegistered(id, _) => id
        case other => fail(s"Expected AuthRegistered, got $other")
      }

      val result = testKit.runCommand { replyTo =>
        UserTokenActor.GetTwitchAuthToken(tokenId, replyTo)
      }

      result.reply match {
        case UserTokenActor.AuthFound(token) =>
          token.platform shouldEqual "twitch"
          token.accessToken shouldEqual "access-123"
          token.refreshToken shouldEqual "refresh-456"
          token.platformUserId shouldEqual "twitch-user-789"
        case other => fail(s"Expected AuthFound, got $other")
      }
    }

    "return NotFound for missing Twitch auth token" in {
      val testKit = createTestKit()

      val result = testKit.runCommand { replyTo =>
        UserTokenActor.GetTwitchAuthToken("nonexistent-id", replyTo)
      }

      result.reply match {
        case UserTokenActor.AuthNotFound => // expected
        case other => fail(s"Expected AuthNotFound, got $other")
      }
    }

    "update a Twitch auth token" in {
      val testKit = createTestKit()

      val regResult = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterTwitchAuthToken(
          "old-access",
          "old-refresh",
          expiresAt,
          "twitch-user-456",
          replyTo
        )
      }
      val oldId = regResult.reply match {
        case UserTokenActor.AuthRegistered(id, _) => id
        case other => fail(s"Expected AuthRegistered, got $other")
      }

      val result = testKit.runCommand { replyTo =>
        UserTokenActor.UpdateTwitchAuthToken(oldId, "new-access", "new-refresh", expiresAt, replyTo)
      }

      result.reply match {
        case UserTokenActor.AuthUpdated(id) =>
          id shouldEqual oldId
        case other => fail(s"Expected AuthUpdated, got $other")
      }

      // Verify the update persisted
      val getResult = testKit.runCommand { replyTo =>
        UserTokenActor.GetTwitchAuthToken(oldId, replyTo)
      }
      getResult.reply match {
        case UserTokenActor.AuthFound(token) =>
          token.accessToken shouldEqual "new-access"
          token.refreshToken shouldEqual "new-refresh"
        case other => fail(s"Expected AuthFound, got $other")
      }
    }

    "reject update for non-existent Twitch auth token" in {
      val testKit = createTestKit()

      val result = testKit.runCommand { replyTo =>
        UserTokenActor.UpdateTwitchAuthToken("fake-id", "access", "refresh", expiresAt, replyTo)
      }

      result.reply match {
        case UserTokenActor.Error(message) =>
          message.toLowerCase should include ("not found")
        case other => fail(s"Expected Error, got $other")
      }
    }

    "revoke a Twitch auth token" in {
      val testKit = createTestKit()

      val regResult = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterTwitchAuthToken(
          "access",
          "refresh",
          expiresAt,
          "twitch-user-456",
          replyTo
        )
      }
      val oldId = regResult.reply match {
        case UserTokenActor.AuthRegistered(id, _) => id
        case other => fail(s"Expected AuthRegistered, got $other")
      }

      val result = testKit.runCommand { replyTo =>
        UserTokenActor.RevokeTwitchAuthToken(oldId, replyTo)
      }

      result.reply match {
        case UserTokenActor.AuthRevoked(id) =>
          id shouldEqual oldId
        case other => fail(s"Expected AuthRevoked, got $other")
      }

      // Verify the token is gone
      val getResult = testKit.runCommand { replyTo =>
        UserTokenActor.GetTwitchAuthToken(oldId, replyTo)
      }
      getResult.reply match {
        case UserTokenActor.AuthNotFound => // expected
        case other => fail(s"Expected AuthNotFound, got $other")
      }
    }

    "reject revoke for non-existent Twitch auth token" in {
      val testKit = createTestKit()

      val result = testKit.runCommand { replyTo =>
        UserTokenActor.RevokeTwitchAuthToken("fake-id", replyTo)
      }

      result.reply match {
        case UserTokenActor.Error(message) =>
          message.toLowerCase should include ("not found")
        case other => fail(s"Expected Error, got $other")
      }
    }

    // --- Platform connection tests ---

    "register a new platform connection" in {
      val testKit = createTestKit()
      val result = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterPlatformConnection(
          twitchPlatform,
          "channel-1",
          "access-token-1",
          "refresh-token-1",
          expiresAt,
          replyTo
        )
      }

      result.reply match {
        case UserTokenActor.ConnectionRegistered(platform, channelId) =>
          platform shouldEqual twitchPlatform
          channelId shouldEqual "channel-1"
        case other => fail(s"Expected ConnectionRegistered, got $other")
      }
    }

    "reject duplicate platform connection registration" in {
      val testKit = createTestKit()

      val regResult = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterPlatformConnection(
          twitchPlatform,
          "channel-1",
          "access-token-1",
          "refresh-token-1",
          expiresAt,
          replyTo
        )
      }
      regResult.reply match {
        case UserTokenActor.ConnectionRegistered(_, _) => // success
        case other => fail(s"Expected ConnectionRegistered, got $other")
      }

      val dupResult = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterPlatformConnection(
          twitchPlatform,
          "channel-1",
          "access-token-2",
          "refresh-token-2",
          expiresAt,
          replyTo
        )
      }

      dupResult.reply match {
        case UserTokenActor.Error(message) =>
          message.toLowerCase should include ("already")
        case other => fail(s"Expected Error, got $other")
      }
    }

    "update an existing platform connection" in {
      val testKit = createTestKit()

      val regResult = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterPlatformConnection(
          twitchPlatform,
          "channel-1",
          "old-access",
          "old-refresh",
          expiresAt,
          replyTo
        )
      }
      regResult.reply match {
        case UserTokenActor.ConnectionRegistered(_, _) => // success
        case other => fail(s"Expected ConnectionRegistered, got $other")
      }

      val result = testKit.runCommand { replyTo =>
        UserTokenActor.UpdatePlatformConnection(
          twitchPlatform,
          "channel-1",
          "new-access",
          "new-refresh",
          expiresAt,
          replyTo
        )
      }

      result.reply match {
        case UserTokenActor.ConnectionUpdated(platform, channelId) =>
          platform shouldEqual twitchPlatform
          channelId shouldEqual "channel-1"
        case other => fail(s"Expected ConnectionUpdated, got $other")
      }
    }

    "reject update for non-existent platform connection" in {
      val testKit = createTestKit()

      val result = testKit.runCommand { replyTo =>
        UserTokenActor.UpdatePlatformConnection(
          twitchPlatform,
          "channel-1",
          "access",
          "refresh",
          expiresAt,
          replyTo
        )
      }

      result.reply match {
        case UserTokenActor.Error(message) =>
          message.toLowerCase should include ("not found")
        case other => fail(s"Expected Error, got $other")
      }
    }

    "revoke a platform connection" in {
      val testKit = createTestKit()

      val regResult = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterPlatformConnection(
          twitchPlatform,
          "channel-1",
          "access",
          "refresh",
          expiresAt,
          replyTo
        )
      }
      regResult.reply match {
        case UserTokenActor.ConnectionRegistered(_, _) => // success
        case other => fail(s"Expected ConnectionRegistered, got $other")
      }

      val result = testKit.runCommand { replyTo =>
        UserTokenActor.RevokePlatformConnection(twitchPlatform, "channel-1", replyTo)
      }

      result.reply match {
        case UserTokenActor.ConnectionRevoked(platform, channelId) =>
          platform shouldEqual twitchPlatform
          channelId shouldEqual "channel-1"
        case other => fail(s"Expected ConnectionRevoked, got $other")
      }
    }

    "reject revoke for non-existent platform connection" in {
      val testKit = createTestKit()

      val result = testKit.runCommand { replyTo =>
        UserTokenActor.RevokePlatformConnection(twitchPlatform, "channel-1", replyTo)
      }

      result.reply match {
        case UserTokenActor.Error(message) =>
          message.toLowerCase should include ("not found")
        case other => fail(s"Expected Error, got $other")
      }
    }

    "get a specific platform connection" in {
      val testKit = createTestKit()

      val regResult = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterPlatformConnection(
          twitchPlatform,
          "channel-1",
          "access-123",
          "refresh-456",
          expiresAt,
          replyTo
        )
      }
      regResult.reply match {
        case UserTokenActor.ConnectionRegistered(_, _) => // success
        case other => fail(s"Expected ConnectionRegistered, got $other")
      }

      val result = testKit.runCommand { replyTo =>
        UserTokenActor.GetPlatformConnection(twitchPlatform, "channel-1", replyTo)
      }

      result.reply match {
        case UserTokenActor.ConnectionFound(conn) =>
          conn.platform shouldEqual twitchPlatform
          conn.channelId shouldEqual "channel-1"
          conn.accessToken shouldEqual "access-123"
        case other => fail(s"Expected ConnectionFound, got $other")
      }
    }

    "return NotFound for non-existent platform connection" in {
      val testKit = createTestKit()

      val result = testKit.runCommand { replyTo =>
        UserTokenActor.GetPlatformConnection(twitchPlatform, "channel-1", replyTo)
      }

      result.reply match {
        case UserTokenActor.ConnectionNotFound(platform, channelId) =>
          platform shouldEqual twitchPlatform
          channelId shouldEqual "channel-1"
        case other => fail(s"Expected ConnectionNotFound, got $other")
      }
    }

    "get all platform connections for a platform" in {
      val testKit = createTestKit()

      val reg1Result = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterPlatformConnection(
          twitchPlatform,
          "channel-1",
          "twitch-access-1",
          "twitch-refresh-1",
          expiresAt,
          replyTo
        )
      }
      reg1Result.reply match {
        case UserTokenActor.ConnectionRegistered(_, _) => // success
        case other => fail(s"Expected ConnectionRegistered, got $other")
      }

      val reg2Result = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterPlatformConnection(
          twitchPlatform,
          "channel-2",
          "twitch-access-2",
          "twitch-refresh-2",
          expiresAt,
          replyTo
        )
      }
      reg2Result.reply match {
        case UserTokenActor.ConnectionRegistered(_, _) => // success
        case other => fail(s"Expected ConnectionRegistered, got $other")
      }

      val result = testKit.runCommand { replyTo =>
        UserTokenActor.GetAllPlatformConnections(twitchPlatform, replyTo)
      }

      result.reply match {
        case UserTokenActor.AllConnectionsFound(connections) =>
          connections.size shouldEqual 2
          connections.map(_.channelId).toSet shouldEqual Set("channel-1", "channel-2")
        case other => fail(s"Expected AllConnectionsFound, got $other")
      }
    }

    "get empty list when no platform connections" in {
      val testKit = createTestKit()

      val result = testKit.runCommand { replyTo =>
        UserTokenActor.GetAllPlatformConnections(twitchPlatform, replyTo)
      }

      result.reply match {
        case UserTokenActor.AllConnectionsFound(connections) =>
          connections should be (Symbol("empty"))
        case other => fail(s"Expected AllConnectionsFound, got $other")
      }
    }

    "check platform connection existence" in {
      val testKit = createTestKit()

      val result = testKit.runCommand { replyTo =>
        UserTokenActor.HasPlatformConnection(twitchPlatform, "channel-1", replyTo)
      }

      result.reply match {
        case UserTokenActor.HasConnection(hasConnection) =>
          hasConnection shouldEqual false
        case other => fail(s"Expected HasConnection, got $other")
      }
    }

    "check platform connection existence after registration" in {
      val testKit = createTestKit()

      val regResult = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterPlatformConnection(
          twitchPlatform,
          "channel-1",
          "access",
          "refresh",
          expiresAt,
          replyTo
        )
      }
      regResult.reply match {
        case UserTokenActor.ConnectionRegistered(_, _) => // success
        case other => fail(s"Expected ConnectionRegistered, got $other")
      }

      val result = testKit.runCommand { replyTo =>
        UserTokenActor.HasPlatformConnection(twitchPlatform, "channel-1", replyTo)
      }

      result.reply match {
        case UserTokenActor.HasConnection(hasConnection) =>
          hasConnection shouldEqual true
        case other => fail(s"Expected HasConnection(true), got $other")
      }
    }

    "update platform connection preserves channelId" in {
      val testKit = createTestKit()

      val regResult = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterPlatformConnection(
          twitchPlatform,
          "channel-1",
          "old-access",
          "old-refresh",
          expiresAt,
          replyTo
        )
      }
      regResult.reply match {
        case UserTokenActor.ConnectionRegistered(_, _) => // success
        case other => fail(s"Expected ConnectionRegistered, got $other")
      }

      val updateResult = testKit.runCommand { replyTo =>
        UserTokenActor.UpdatePlatformConnection(
          twitchPlatform,
          "channel-1",
          "new-access",
          "new-refresh",
          expiresAt,
          replyTo
        )
      }
      updateResult.reply match {
        case UserTokenActor.ConnectionUpdated(_, _) => // success
        case other => fail(s"Expected ConnectionUpdated, got $other")
      }

      val result = testKit.runCommand { replyTo =>
        UserTokenActor.GetPlatformConnection(twitchPlatform, "channel-1", replyTo)
      }

      result.reply match {
        case UserTokenActor.ConnectionFound(conn) =>
          conn.channelId shouldEqual "channel-1"
          conn.accessToken shouldEqual "new-access"
          conn.refreshToken shouldEqual "new-refresh"
        case other => fail(s"Expected ConnectionFound, got $other")
      }
    }

    "revoke removes platform connection from getAll" in {
      val testKit = createTestKit()

      val regResult = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterPlatformConnection(
          twitchPlatform,
          "channel-1",
          "access",
          "refresh",
          expiresAt,
          replyTo
        )
      }
      regResult.reply match {
        case UserTokenActor.ConnectionRegistered(_, _) => // success
        case other => fail(s"Expected ConnectionRegistered, got $other")
      }

      val revokeResult = testKit.runCommand { replyTo =>
        UserTokenActor.RevokePlatformConnection(twitchPlatform, "channel-1", replyTo)
      }
      revokeResult.reply match {
        case UserTokenActor.ConnectionRevoked(_, _) => // success
        case other => fail(s"Expected ConnectionRevoked, got $other")
      }

      val result = testKit.runCommand { replyTo =>
        UserTokenActor.GetAllPlatformConnections(twitchPlatform, replyTo)
      }

      result.reply match {
        case UserTokenActor.AllConnectionsFound(connections) =>
          connections should be (Symbol("empty"))
        case other => fail(s"Expected AllConnectionsFound (empty), got $other")
      }
    }

    // --- Persistence tests ---

    "persist and recover Twitch auth token" in {
      val testKit = createTestKit()

      val regResult = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterTwitchAuthToken(
          "access-1",
          "refresh-1",
          expiresAt,
          "twitch-user-1",
          replyTo
        )
      }
      val tokenId = regResult.reply match {
        case UserTokenActor.AuthRegistered(id, _) => id
        case other => fail(s"Expected AuthRegistered, got $other")
      }

      val stateBefore = testKit.getState()
      stateBefore.twitchTokens.size shouldEqual 1

      testKit.restart()

      val stateAfter = testKit.getState()
      stateAfter.twitchTokens.size shouldEqual 1
      stateAfter.twitchTokens(tokenId).accessToken shouldEqual "access-1"

      // Verify we can still fetch it by ID
      val getResult = testKit.runCommand { replyTo =>
        UserTokenActor.GetTwitchAuthToken(tokenId, replyTo)
      }
      getResult.reply match {
        case UserTokenActor.AuthFound(token) =>
          token.accessToken shouldEqual "access-1"
        case other => fail(s"Expected AuthFound, got $other")
      }
    }

    "persist and recover platform connections" in {
      val testKit = createTestKit()

      val regResult = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterPlatformConnection(
          twitchPlatform,
          "channel-1",
          "access-1",
          "refresh-1",
          expiresAt,
          replyTo
        )
      }
      regResult.reply match {
        case UserTokenActor.ConnectionRegistered(_, _) => // success
        case other => fail(s"Expected ConnectionRegistered, got $other")
      }

      val stateBefore = testKit.getState()
      stateBefore.connections(twitchPlatform).size shouldEqual 1

      testKit.restart()

      val stateAfter = testKit.getState()
      stateAfter.connections(twitchPlatform).size shouldEqual 1
      stateAfter.connections(twitchPlatform)(0).accessToken shouldEqual "access-1"
    }

    "persist and recover both Twitch auth token and platform connections" in {
      val testKit = createTestKit()

      val authResult = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterTwitchAuthToken(
          "auth-access",
          "auth-refresh",
          expiresAt,
          "twitch-user-1",
          replyTo
        )
      }
      val authId = authResult.reply match {
        case UserTokenActor.AuthRegistered(id, _) => id
        case other => fail(s"Expected AuthRegistered, got $other")
      }

      val connResult = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterPlatformConnection(
          twitchPlatform,
          "channel-1",
          "conn-access",
          "conn-refresh",
          expiresAt,
          replyTo
        )
      }
      connResult.reply match {
        case UserTokenActor.ConnectionRegistered(_, _) => // success
        case other => fail(s"Expected ConnectionRegistered, got $other")
      }

      testKit.restart()

      val state = testKit.getState()
      state.twitchTokens.size shouldEqual 1
      state.twitchTokens(authId).accessToken shouldEqual "auth-access"
      state.connections(twitchPlatform).size shouldEqual 1
    }

    "support multiple Twitch auth tokens" in {
      val testKit = createTestKit()

      val reg1Result = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterTwitchAuthToken(
          "device-1-access",
          "device-1-refresh",
          expiresAt,
          "twitch-user-1",
          replyTo
        )
      }
      val token1Id = reg1Result.reply match {
        case UserTokenActor.AuthRegistered(id, _) => id
        case other => fail(s"Expected AuthRegistered, got $other")
      }

      val reg2Result = testKit.runCommand { replyTo =>
        UserTokenActor.RegisterTwitchAuthToken(
          "device-2-access",
          "device-2-refresh",
          expiresAt,
          "twitch-user-1",
          replyTo
        )
      }
      val token2Id = reg2Result.reply match {
        case UserTokenActor.AuthRegistered(id, _) => id
        case other => fail(s"Expected AuthRegistered, got $other")
      }

      // Both tokens should be present
      val state = testKit.getState()
      state.twitchTokens.size shouldEqual 2
      state.twitchTokens(token1Id).accessToken shouldEqual "device-1-access"
      state.twitchTokens(token2Id).accessToken shouldEqual "device-2-access"

      // Can fetch all tokens
      val getAllResult = testKit.runCommand { replyTo =>
        UserTokenActor.GetAllTwitchAuthTokens(replyTo)
      }
      getAllResult.reply match {
        case UserTokenActor.AllAuthTokensFound(tokens) =>
          tokens.size shouldEqual 2
          tokens.map(_.accessToken).toSet shouldEqual Set("device-1-access", "device-2-access")
        case other => fail(s"Expected AllAuthTokensFound, got $other")
      }

      // Can fetch individual tokens by ID
      val get1Result = testKit.runCommand { replyTo =>
        UserTokenActor.GetTwitchAuthToken(token1Id, replyTo)
      }
      get1Result.reply match {
        case UserTokenActor.AuthFound(token) =>
          token.accessToken shouldEqual "device-1-access"
        case other => fail(s"Expected AuthFound, got $other")
      }

      // Revoke one, other remains
      val revokeResult = testKit.runCommand { replyTo =>
        UserTokenActor.RevokeTwitchAuthToken(token1Id, replyTo)
      }
      revokeResult.reply match {
        case UserTokenActor.AuthRevoked(id) =>
          id shouldEqual token1Id
        case other => fail(s"Expected AuthRevoked, got $other")
      }

      val stateAfter = testKit.getState()
      stateAfter.twitchTokens.size shouldEqual 1
      stateAfter.twitchTokens(token2Id).accessToken shouldEqual "device-2-access"
    }
  }

}
