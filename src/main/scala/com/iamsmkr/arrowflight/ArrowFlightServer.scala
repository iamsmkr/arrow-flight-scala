package com.iamsmkr.arrowflight

import org.apache.arrow.flight.{FlightServer, Location}
import org.apache.arrow.memory.BufferAllocator
import org.apache.logging.log4j.LogManager

import java.net._
import java.io.IOException
import java.util.concurrent.Executors

case class ArrowFlightServer(allocator: BufferAllocator) extends AutoCloseable {
  private val logger = LogManager.getLogger(classOf[ArrowFlightServer])

  private var started = false

  private val location = Location.forGrpcInsecure(InetAddress.getLocalHost.getHostAddress, 0)
  private val flightProducer = new ArrowFlightProducer(allocator, location)
  private val flightServer =
    FlightServer.builder(
      allocator,
      location,
      flightProducer
    ).build()

  private val pool = Executors.newCachedThreadPool()
  pool.submit(new Runnable {
    override def run(): Unit = {
      try {
        flightServer.synchronized {
          flightServer.start()
          started = true
          flightServer.notify()
        }
        logger.info("ArrowFlightServer({},{}) is online", flightServer.getLocation.getUri.getHost, flightServer.getPort)
        flightServer.awaitTermination()
      } catch {
        case e: IOException =>
          logger.error("Failed to start ArrowFlight server! " + e.getMessage)
          e.printStackTrace()
        case e: InterruptedException => e.printStackTrace()
      } finally {
        close()
      }
    }
  })

  def waitForServerToStart(): Unit = {
    flightServer.synchronized {
      while (!started) {
        try {
          flightServer.wait()
        } catch {
          case e: InterruptedException =>
            e.printStackTrace()
        }
      }
    }
  }

  def getInterface: String = flightServer.getLocation.getUri.getHost

  def getPort: Int = flightServer.getPort

  override def close(): Unit = {
    try {
      flightServer.shutdown()
      flightProducer.close()
    } catch {
      case e: Exception =>
        e.printStackTrace()
    }
  }

  override def toString: String = s"ArrowFlightServer($getInterface,$getPort)"
}
