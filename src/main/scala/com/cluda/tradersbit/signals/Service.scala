package com.cluda.tradersbit.signals

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem, InvalidActorNameException, Props}
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.{HttpHeader, HttpResponse}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RequestContext
import akka.pattern.ask
import akka.stream.Materializer
import akka.util.Timeout
import com.cluda.tradersbit.signals.getsignal.GetSignalsActor
import com.cluda.tradersbit.signals.model.Meta
import com.cluda.tradersbit.signals.postsignal.PostSignalActor
import com.cluda.tradersbit.signals.protocoll.{GetSignals, GetSignalsParams}
import com.typesafe.config.Config
import spray.json._
import DefaultJsonProtocol._

import scala.concurrent.{ExecutionContextExecutor, Future}

trait Service {
  implicit val system: ActorSystem

  implicit def executor: ExecutionContextExecutor

  implicit val materializer: Materializer
  implicit val timeout: Timeout

  val config: Config
  val logger: LoggingAdapter
  val runID = UUID.randomUUID()
  var actorIDs = Map[String, Long]()

  val getExchangeActor: ActorRef
  val databaseWriterActor: ActorRef
  val databaseReaderActor: ActorRef
  val getPriceActor: ActorRef
  val notificationActor: ActorRef
  val httpNotifyActor: ActorRef

  private lazy val apiKey = config.getString("microservices.signalsApiKey")


  def actorName(props: Props): String = {
    val classPath = props.actorClass().toString
    val className = classPath.substring(classPath.lastIndexOf('.') + 1)
    val id: Long = actorIDs.getOrElse(className, 0)
    if (id == 0) {
      actorIDs = actorIDs + (className -> 1)
    }
    else {
      actorIDs = actorIDs + (className -> (id + 1))
    }
    className + id
  }

  /**
    * Start a actor and pass it the decodedHttpRequest.
    * Returns a future. If anything fails it returns HttpResponse with "BadRequest",
    * else it returns the HttpResponse returned by the started actor
    *
    * @param props of the actor to start
    * @return Future[HttpResponse]
    */
  def perRequestActor[T](props: Props, message: T, name: Option[String] = None): Future[HttpResponse] = {
    try {
      (system.actorOf(props, name.getOrElse(actorName(props))) ? message).asInstanceOf[Future[HttpResponse]]
    }
    catch {
      case e: InvalidActorNameException => {
        logger.warning("Responded with: TooManyRequests")
        Future(HttpResponse(TooManyRequests, entity = "Only one signal can be processed for a stream at the time. " +
          "wait for responds to your last signal before send a new signal."))
      }
      case _ => {
        logger.error("Responded with: InternalServerError")
        Future(HttpResponse(InternalServerError, entity = "InternalServerError"))
      }
    }
  }

  def auth: RequestContext => Boolean = (ctx: RequestContext) => {
    ctx.request.headers.find(_.is("authorization")) match {
      case Some(header: HttpHeader) =>
        val thisApiKey = header.value().split(" ")(1)
        thisApiKey.equals(apiKey)
      case None => false
    }
  }

  val routes = {
    authorize(auth) {
      headerValueByName("Global-Request-ID") { globalRequestID =>

        pathPrefix("ping") {
          complete {
            logger.info(s"[$globalRequestID]: Answering ping request")
            HttpResponse(OK, entity = Map("runID" -> runID.toString, "globalRequestID" -> globalRequestID).toJson.prettyPrint)
          }
        } ~
          pathPrefix("streams" / Segment) { streamID =>
            pathPrefix("signals") {
              post {
                entity(as[String]) { signalString =>
                  try {
                    val signal = signalString.toInt
                    complete {
                      if (List(-1, 0, 1).contains(signal)) {
                        logger.info(s"[$globalRequestID]: Got new signal: $signal. For stream: $streamID.")
                        perRequestActor[Meta](
                          PostSignalActor.props(globalRequestID, getExchangeActor),
                          Meta(None, streamID, signal, None, None, None, None, None),
                          Some(streamID + "-post_signal"))
                      }
                      else {
                        logger.error(s"[$globalRequestID]: Got unknown signal: $signal. Returning 'BadRequest'. For stream: $streamID.")
                        HttpResponse(BadRequest, entity = "BadRequest")
                      }
                    }
                  } catch {
                    case e: Throwable =>
                      logger.error(s"[$globalRequestID]: Got unknown signal: $signalString. Returning 'BadRequest'. For stream: $streamID. Error: " + e.toString)
                      complete(HttpResponse(BadRequest, entity = "BadRequest"))
                  }
                }
              } ~
                get {
                  parameters('onlyClosed.as[Boolean].?, 'fromId.as[Long].?, 'toId.as[Long].?, 'afterTime.as[Long].?, 'beforeTime.as[Long].?, 'lastN.as[Int].?).as(GetSignalsParams) { params =>
                    complete {
                      if (params.isValid) {
                        logger.info(s"[$globalRequestID]: Got valid request for signals. For stream: $streamID.")
                        perRequestActor[GetSignals](
                          GetSignalsActor.props(globalRequestID, databaseReaderActor),
                          GetSignals(streamID, params))
                      }
                      else {
                        logger.warning(s"[$globalRequestID]: Got invalid request for signals. For stream: $streamID.")
                        HttpResponse(BadRequest, entity = "invalid combination of parameters")
                      }
                    }
                  }
                }
            } ~
              pathPrefix("status") {
                complete {
                  logger.info(s"[$globalRequestID]: Got request for status. For stream: $streamID.")
                  perRequestActor[GetSignals](
                    GetSignalsActor.props(globalRequestID, databaseReaderActor),
                    GetSignals(streamID, GetSignalsParams(lastN = Some(1)))
                  )
                }
              }

          }

      }
    }
  }


}
