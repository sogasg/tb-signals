package com.cluda.coinsignals.signals

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.stream.Materializer
import akka.util.Timeout
import com.cluda.coinsignals.signals.getsignal.GetSignalsActor
import com.cluda.coinsignals.signals.model.Meta
import com.cluda.coinsignals.signals.postsignal.PostSignalActor
import com.cluda.coinsignals.signals.protocoll.{GetSignals, GetSignalsParams}
import com.typesafe.config.Config

import scala.concurrent.{ExecutionContextExecutor, Future}

trait Service {
  implicit val system: ActorSystem

  implicit def executor: ExecutionContextExecutor

  implicit val materializer: Materializer
  implicit val timeout: Timeout

  val config: Config
  val logger: LoggingAdapter

  val getExchangeActor: ActorRef
  val databaseWriterActor: ActorRef
  val databaseReaderActor: ActorRef
  val getPriceActor: ActorRef
  val notificationActor: ActorRef
  val httpNotifyActor: ActorRef

  val runID = UUID.randomUUID()


  /**
   * Start a actor and pass it the decodedHttpRequest.
   * Returns a future. If anything fails it returns HttpResponse with "BadRequest",
   * else it returns the HttpResponse returned by the started actor
   *
   * @param props of the actor to start
   * @return Future[HttpResponse]
   */
  def perRequestActor[T](props: Props, message: T): Future[HttpResponse] = {
    (system.actorOf(props) ? message)
      .recover { case _ => HttpResponse(BadRequest, entity = "BadRequest") }
      .asInstanceOf[Future[HttpResponse]]
  }

  val routes = {
    pathPrefix("ping") {
      complete {
        HttpResponse(OK, entity = "runID: " + runID)
      }
    } ~
        pathPrefix("streams" / Segment) { streamID =>
          pathPrefix("signals") {
            post {
              entity(as[String]) { signal =>
                  complete {
                    if (List(-1, 0, 1).contains(signal.toInt)) {
                      perRequestActor[Meta](
                        PostSignalActor.props(getExchangeActor),
                        Meta(None, streamID, signal.toInt, None, None, None, None)
                      )
                    }
                    else {
                      HttpResponse(BadRequest, entity = "BadRequest")
                    }
                  }
              }
            } ~
              get {
                parameters('fromId.as[Long].?, 'toId.as[Long].?, 'afterTime.as[Long].?, 'beforeTime.as[Long].?, 'lastN.as[Int].?).as(GetSignalsParams) { params =>
                  complete {
                    if (params.isValid) {
                      perRequestActor[GetSignals](
                        GetSignalsActor.props(databaseReaderActor),
                        GetSignals(streamID, params)
                      )
                    }
                    else {
                      HttpResponse(BadRequest, entity = "invalid combination of parameters")
                    }
                  }
                }
              }
          } ~
            pathPrefix("status") {
              complete {
                perRequestActor[GetSignals](
                  GetSignalsActor.props(databaseReaderActor),
                  GetSignals(streamID, GetSignalsParams(lastN = Some(1)))
                )
              }
            }

        }

  }


}
