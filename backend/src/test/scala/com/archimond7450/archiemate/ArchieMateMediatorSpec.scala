package com.archimond7450.archiemate

import com.archimond7450.archiemate.actors.http.HttpRequestActor
import com.archimond7450.archiemate.http.HttpClientActor
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.model.{HttpMethods, Uri}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ArchieMateMediatorSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers {

  private def spawnMediator(
      httpClient: ActorRef[HttpClientActor.Command],
      httpRequestActor: ActorRef[HttpRequestActor.Command],
      name: String = "archie-mate-mediator"
  ): ActorRef[ArchieMateMediator.Command] =
    testKit.spawn(
      ArchieMateMediator(httpClient, httpRequestActor),
      s"$name-${java.util.UUID.randomUUID().toString.take(8)}"
    )

  "ArchieMateMediator" should {

    "route a SendRequest command to the registered http-client actor" in {
      val httpProbe = testKit.createTestProbe[HttpClientActor.Command]("http-client")
      val httpRequestProbe = testKit.createTestProbe[HttpRequestActor.Command]("http-request")
      val mediator = spawnMediator(httpProbe.ref, httpRequestProbe.ref)

      mediator ! ArchieMateMediator.SendHttpClientRequest(
        HttpClientActor.SendRequest(
          method = HttpMethods.GET,
          uri = Uri("http://localhost:1"),
          replyTo = testKit.createTestProbe[org.apache.pekko.pattern.StatusReply[HttpClientActor.Response]]("response").ref
        )
      )

      val msg = httpProbe.receiveMessage()
      msg shouldBe a[HttpClientActor.SendRequest]
      val sendReq = msg.asInstanceOf[HttpClientActor.SendRequest]
      sendReq.method shouldEqual HttpMethods.GET
      sendReq.uri shouldEqual Uri("http://localhost:1")
    }

    "route multiple commands to the same actor in order" in {
      val httpProbe = testKit.createTestProbe[HttpClientActor.Command]("http-client")
      val httpRequestProbe = testKit.createTestProbe[HttpRequestActor.Command]("http-request")
      val mediator = spawnMediator(httpProbe.ref, httpRequestProbe.ref)

      mediator ! ArchieMateMediator.SendHttpClientRequest(
        HttpClientActor.SendRequest(
          method = HttpMethods.GET,
          uri = Uri("http://localhost:1/1"),
          replyTo = testKit.createTestProbe[org.apache.pekko.pattern.StatusReply[HttpClientActor.Response]]("response-1").ref
        )
      )
      mediator ! ArchieMateMediator.SendHttpClientRequest(
        HttpClientActor.SendRequest(
          method = HttpMethods.POST,
          uri = Uri("http://localhost:1/2"),
          replyTo = testKit.createTestProbe[org.apache.pekko.pattern.StatusReply[HttpClientActor.Response]]("response-2").ref
        )
      )

      val msg1 = httpProbe.receiveMessage()
      msg1 shouldBe a[HttpClientActor.SendRequest]
      val req1 = msg1.asInstanceOf[HttpClientActor.SendRequest]
      req1.method shouldEqual HttpMethods.GET
      req1.uri shouldEqual Uri("http://localhost:1/1")

      val msg2 = httpProbe.receiveMessage()
      msg2 shouldBe a[HttpClientActor.SendRequest]
      val req2 = msg2.asInstanceOf[HttpClientActor.SendRequest]
      req2.method shouldEqual HttpMethods.POST
      req2.uri shouldEqual Uri("http://localhost:1/2")
    }

    "preserve command order across rapid sends" in {
      val httpProbe = testKit.createTestProbe[HttpClientActor.Command]("http-client")
      val httpRequestProbe = testKit.createTestProbe[HttpRequestActor.Command]("http-request")
      val mediator = spawnMediator(httpProbe.ref, httpRequestProbe.ref)

      for (i <- 1 to 3) {
        mediator ! ArchieMateMediator.SendHttpClientRequest(
          HttpClientActor.SendRequest(
            method = HttpMethods.GET,
            uri = Uri(s"http://localhost:1/$i"),
            replyTo = testKit.createTestProbe[org.apache.pekko.pattern.StatusReply[HttpClientActor.Response]]("response").ref
          )
        )
      }

      for (i <- 1 to 3) {
        val msg = httpProbe.receiveMessage()
        msg shouldBe a[HttpClientActor.SendRequest]
        val req = msg.asInstanceOf[HttpClientActor.SendRequest]
        req.uri shouldEqual Uri(s"http://localhost:1/$i")
      }
    }

  }

}
