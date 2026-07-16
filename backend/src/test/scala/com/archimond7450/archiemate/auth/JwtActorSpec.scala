package com.archimond7450.archiemate.auth

import com.archimond7450.archiemate.settings.JwtConfig
import io.circe.Json
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import pdi.jwt.{Jwt, JwtAlgorithm}
import pdi.jwt.JwtCirce

import java.time.Instant
import scala.concurrent.duration._

class JwtActorSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers {

  private val testConfig = JwtConfig(
    secret = "test-secret-key-for-jwt-signing-must-be-long-enough",
    tokenLifetimeMinutes = 15
  )

  private def createActor(): ActorRef[JwtActor.Command] =
    testKit.spawn(JwtActor(testConfig), s"jwt-actor-${java.util.UUID.randomUUID().toString.take(8)}")

  "JwtActor" should {
    "encode a JWT token with userId as subject" in {
      val actor = createActor()
      val probe = testKit.createTestProbe[JwtActor.EncodeResponse]("encode-probe")

      val userId = "user-123"
      actor ! JwtActor.Encode(userId, probe.ref)

      probe.receiveMessage() match {
        case JwtActor.EncodeSuccess(token) =>
          token should not be empty
          token.split("\\.").length shouldEqual 3 // header.payload.signature
        case other => fail(s"Expected EncodeSuccess, got $other")
      }
    }

    "decode a JWT token and extract userId" in {
      val actor = createActor()
      val encodeProbe = testKit.createTestProbe[JwtActor.EncodeResponse]("encode-probe")
      val decodeProbe = testKit.createTestProbe[JwtActor.DecodeResponse]("decode-probe")

      val userId = "user-456"
      actor ! JwtActor.Encode(userId, encodeProbe.ref)
      val token = encodeProbe.receiveMessage() match {
        case JwtActor.EncodeSuccess(t) => t
        case other => fail(s"Expected EncodeSuccess, got $other")
      }

      actor ! JwtActor.Decode(token, decodeProbe.ref)
      decodeProbe.receiveMessage() match {
        case JwtActor.DecodeSuccess(decodedUserId, expiresAt) =>
          decodedUserId shouldEqual userId
          expiresAt should be > Instant.now()
        case other => fail(s"Expected DecodeSuccess, got $other")
      }
    }

    "validate a valid JWT token" in {
      val actor = createActor()
      val encodeProbe = testKit.createTestProbe[JwtActor.EncodeResponse]("encode-probe")
      val validateProbe = testKit.createTestProbe[JwtActor.ValidateResponse]("validate-probe")

      val userId = "user-789"
      actor ! JwtActor.Encode(userId, encodeProbe.ref)
      val token = encodeProbe.receiveMessage() match {
        case JwtActor.EncodeSuccess(t) => t
        case other => fail(s"Expected EncodeSuccess, got $other")
      }

      actor ! JwtActor.Validate(token, validateProbe.ref)
      validateProbe.receiveMessage() match {
        case JwtActor.ValidateSuccess(true, None) => // success
        case other => fail(s"Expected ValidateSuccess(true, None), got $other")
      }
    }

    "reject an invalid JWT token" in {
      val actor = createActor()
      val validateProbe = testKit.createTestProbe[JwtActor.ValidateResponse]("validate-probe")

      actor ! JwtActor.Validate("invalid-token", validateProbe.ref)
      validateProbe.receiveMessage() match {
        case JwtActor.ValidateSuccess(false, Some(reason)) =>
          reason should not be empty
        case other => fail(s"Expected ValidateSuccess(false, Some), got $other")
      }
    }

    "reject an expired JWT token" in {
      // Create a token that expired 1 hour ago
      val expiredAt = Instant.now().minusSeconds(3600)
      val payload = Json.obj(
        "sub" -> Json.fromString("user-expired"),
        "iat" -> Json.fromLong(Instant.now().minusSeconds(7200).getEpochSecond),
        "exp" -> Json.fromLong(expiredAt.getEpochSecond)
      )
      val token = JwtCirce.encode(payload, testConfig.secret, JwtAlgorithm.HS256)

      val actor = createActor()
      val validateProbe = testKit.createTestProbe[JwtActor.ValidateResponse]("validate-probe")

      actor ! JwtActor.Validate(token, validateProbe.ref)
      validateProbe.receiveMessage() match {
        case JwtActor.ValidateSuccess(false, Some(reason)) =>
          reason.toLowerCase should include ("expir")
        case other => fail(s"Expected ValidateSuccess(false, Some(expired)), got $other")
      }
    }

    "decode and validate a valid JWT token" in {
      val actor = createActor()
      val encodeProbe = testKit.createTestProbe[JwtActor.EncodeResponse]("encode-probe")
      val decodeValidateProbe = testKit.createTestProbe[JwtActor.DecodeAndValidateResponse]("decode-validate-probe")

      val userId = "user-dv"
      actor ! JwtActor.Encode(userId, encodeProbe.ref)
      val token = encodeProbe.receiveMessage() match {
        case JwtActor.EncodeSuccess(t) => t
        case other => fail(s"Expected EncodeSuccess, got $other")
      }

      actor ! JwtActor.DecodeAndValidate(token, decodeValidateProbe.ref)
      decodeValidateProbe.receiveMessage() match {
        case JwtActor.DecodeAndValidateSuccess(decodedUserId, expiresAt) =>
          decodedUserId shouldEqual userId
          expiresAt should be > Instant.now()
        case other => fail(s"Expected DecodeAndValidateSuccess, got $other")
      }
    }

    "reject decode and validate with an expired token" in {
      val expiredAt = Instant.now().minusSeconds(3600)
      val payload = Json.obj(
        "sub" -> Json.fromString("user-expired"),
        "iat" -> Json.fromLong(Instant.now().minusSeconds(7200).getEpochSecond),
        "exp" -> Json.fromLong(expiredAt.getEpochSecond)
      )
      val token = JwtCirce.encode(payload, testConfig.secret, JwtAlgorithm.HS256)

      val actor = createActor()
      val probe = testKit.createTestProbe[JwtActor.DecodeAndValidateResponse]("decode-validate-probe")

      actor ! JwtActor.DecodeAndValidate(token, probe.ref)
      probe.receiveMessage() match {
        case JwtActor.Error(message) =>
          message.toLowerCase should include ("expir")
        case other => fail(s"Expected Error, got $other")
      }
    }

    "refresh a valid JWT token" in {
      val actor = createActor()
      val encodeProbe = testKit.createTestProbe[JwtActor.EncodeResponse]("encode-probe")
      val refreshProbe = testKit.createTestProbe[JwtActor.RefreshResponse]("refresh-probe")

      val userId = "user-refresh"
      actor ! JwtActor.Encode(userId, encodeProbe.ref)
      val oldToken = encodeProbe.receiveMessage() match {
        case JwtActor.EncodeSuccess(t) => t
        case other => fail(s"Expected EncodeSuccess, got $other")
      }

      actor ! JwtActor.Refresh(oldToken, refreshProbe.ref)
      refreshProbe.receiveMessage() match {
        case JwtActor.RefreshSuccess(newToken) =>
          newToken should not be empty
        case other => fail(s"Expected RefreshSuccess, got $other")
      }
    }

    "reject refresh of an invalid JWT token" in {
      val actor = createActor()
      val refreshProbe = testKit.createTestProbe[JwtActor.RefreshResponse]("refresh-probe")

      actor ! JwtActor.Refresh("invalid-token", refreshProbe.ref)
      refreshProbe.receiveMessage() match {
        case JwtActor.Error(message) =>
          message.toLowerCase should include ("invalid")
        case other => fail(s"Expected Error, got $other")
      }
    }

    "reject a tampered JWT token" in {
      val actor = createActor()
      val encodeProbe = testKit.createTestProbe[JwtActor.EncodeResponse]("encode-probe")
      val validateProbe = testKit.createTestProbe[JwtActor.ValidateResponse]("validate-probe")

      val userId = "user-tamper"
      actor ! JwtActor.Encode(userId, encodeProbe.ref)
      val token = encodeProbe.receiveMessage() match {
        case JwtActor.EncodeSuccess(t) => t
        case other => fail(s"Expected EncodeSuccess, got $other")
      }

      // Tamper with the token (modify the payload)
      val tamperedToken = token.split("\\.").match {
        case Array(header, payload, signature) =>
          // Flip a character in the payload
          val newPayload = payload.toList.updated(payload.length / 2, payload(payload.length / 2).toChar ^ 1).mkString
          s"$header.$newPayload.$signature"
      }

      actor ! JwtActor.Validate(tamperedToken, validateProbe.ref)
      validateProbe.receiveMessage() match {
        case JwtActor.ValidateSuccess(false, Some(reason)) =>
          reason should not be empty
        case other => fail(s"Expected ValidateSuccess(false, Some), got $other")
      }
    }
  }
}
