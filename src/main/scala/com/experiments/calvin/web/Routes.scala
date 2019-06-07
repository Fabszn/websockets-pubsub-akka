package com.experiments.calvin.web

import akka.NotUsed
import akka.actor.{ActorPath, ActorRef, ActorSelection, ActorSystem}
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.OverflowStrategy
import akka.stream.scaladsl._
import com.experiments.calvin.actors.WebSocketUser
import com.experiments.calvin.actors.WebSocketUser._
import io.circe._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._

import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.duration._

trait Routes {
  val system: ActorSystem

  private def wsUser(username: String): (ActorRef, Flow[Message, Message, NotUsed]) = {
    // Create an actor for every WebSocket connection, this will represent the contact point to reach the user
    val wsUser: ActorRef = system.actorOf(WebSocketUser.props(username))

    // Integration point between Akka Streams and the above actor
    val sink: Sink[Message, NotUsed] =
      Flow[Message]
        .collect { case TextMessage.Strict(json) => decode[Calculate](json) }
        .filter(_.isRight)
        .map(_.right.get)
        .to(Sink.actorRef(wsUser, WsHandleDropped)) // connect to the wsUser Actor

    // Integration point between Akka Streams and above actor
    val source: Source[Message, NotUsed] =
      Source
        .actorRef(bufferSize = 10, overflowStrategy = OverflowStrategy.dropBuffer)
        .map((c: Msg) => TextMessage.Strict(c.asJson.noSpaces))
        .mapMaterializedValue { wsHandle =>
          // the wsHandle is the way to talk back to the user, our wsUser actor needs to know about this to send
          // messages to the WebSocket user
          wsUser ! ConnectWsHandle(wsHandle)
          // don't expose the wsHandle anymore
          NotUsed
        }
        .keepAlive(maxIdle = 10.seconds, () => TextMessage.Strict("Keep-alive message sent to WebSocket recipient"))

    (wsUser, Flow.fromSinkAndSource(sink, source))
  }

  val routes: Route = path("ws" / Remaining) { username: String =>
    val (a, f) = wsUser(username)
    handleWebSocketMessages(f)
  } ~ path("send") {
    get {
      //actorRefs.foreach(p => system.actorSelection(p) ! Tick("ezrer"))
      system.actorSelection("/user/*") ! Tick("ezrer")

      complete("done")
    }
  }
}
