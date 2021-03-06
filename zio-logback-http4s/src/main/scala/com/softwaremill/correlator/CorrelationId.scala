package com.softwaremill.correlator

import java.{util => ju}

import cats.data.{Kleisli, OptionT}
import ch.qos.logback.classic.util.LogbackMDCAdapter
import org.http4s.util.CaseInsensitiveString
import org.http4s.{HttpRoutes, Request, Response}
import org.slf4j.{Logger, LoggerFactory, MDC}
import zio.{FiberRef, Runtime, Task, UIO, ZIO}

import scala.util.Random

/**
  * Correlation id support. The `init()` method should be called when the application starts.
  * See [[https://blog.softwaremill.com/correlation-ids-in-scala-using-monix-3aa11783db81]] for details.
  */
final class CorrelationId(
    val headerName: String = "X-Correlation-ID",
    logStartRequest: (String, Request[Task]) => Task[Unit] = (cid, req) =>
      Task(CorrelationId.logger.debug(s"Starting request with id: $cid, to: ${req.uri.path}"))
)(implicit runtime: Runtime[Any]) {
  private val MdcKey = "cid"
  private val random = new Random()

  def init: UIO[Unit] = ZioMDCAdapter.init(runtime)

  def get: UIO[Option[String]] = Task(Option(MDC.get(MdcKey))).orElse(UIO.apply(None))

  def setCorrelationIdMiddleware(service: HttpRoutes[Task]): HttpRoutes[Task] =
    Kleisli { req: Request[Task] =>
      val cid = req.headers.get(CaseInsensitiveString(headerName)).fold(newCorrelationId())(_.value)

      val setupAndService: Task[Option[Response[Task]]] =
        for {
          _ <- Task(MDC.put(MdcKey, cid))
          _ <- logStartRequest(cid, req)
          r <- service(req).value
        } yield r

      OptionT(setupAndService.ensuring(Task(MDC.remove(MdcKey)).orElse(ZIO.unit)))
    }

  private def newCorrelationId(): String = {
    def randomUpperCaseChar() = (random.nextInt(91 - 65) + 65).toChar
    def segment: String = (1 to 3).map(_ => randomUpperCaseChar()).mkString
    s"$segment-$segment-$segment"
  }
}

object CorrelationId {
  val logger: Logger = LoggerFactory.getLogger(getClass.getName)
}

/**
  * Based on [[https://olegpy.com/better-logging-monix-1/]]. Makes the current correlation id available for logback
  * loggers.
  */
class ZioMDCAdapter[+R](fiber: FiberRef[ju.Map[String, String]])(implicit runtime: Runtime[R]) extends LogbackMDCAdapter {
  override def put(key: String, `val`: String): Unit =
    runtime.unsafeRun {
      fiber.modify { map =>
        val m: ju.Map[String, String] = if (map eq ju.Collections.EMPTY_MAP) new ju.HashMap() else map
        m.put(key, `val`)
        ((), m)
      }
    }

  override def get(key: String): String = runtime.unsafeRun { fiber.get.map(_.get(key)) }

  override def remove(key: String): Unit =
    runtime.unsafeRun {
      fiber.modify { map =>
        map.remove(key)
        ((), map)
      }
    }

  override def clear(): Unit = runtime.unsafeRun { fiber.set(ju.Collections.emptyMap()) }

  override def getCopyOfContextMap: ju.HashMap[String, String] = runtime.unsafeRun { fiber.get.map(new ju.HashMap(_)) }

  override def setContextMap(contextMap: ju.Map[String, String]): Unit =
    runtime.unsafeRun { fiber.set(new ju.HashMap(contextMap)) }

  override def getPropertyMap: ju.Map[String, String] = runtime.unsafeRun { fiber.get }
  override def getKeys: ju.Set[String] = runtime.unsafeRun { fiber.get.map(_.keySet()) }
}

object ZioMDCAdapter {
  def init(implicit runtime: Runtime[_]): UIO[Unit] =
    for {
      fiber <- FiberRef.make[ju.Map[String, String]](ju.Collections.emptyMap())
    } yield {
      val field = classOf[MDC].getDeclaredField("mdcAdapter")
      field.setAccessible(true)
      field.set(null, new ZioMDCAdapter(fiber))
    }
}
