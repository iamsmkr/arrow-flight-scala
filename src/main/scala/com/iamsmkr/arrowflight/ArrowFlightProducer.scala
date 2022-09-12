package com.iamsmkr.arrowflight

import org.apache.arrow.flight.FlightProducer._
import org.apache.arrow.flight._
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.util.AutoCloseables
import org.apache.arrow.vector._
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent._

private[arrowflight] class ArrowFlightProducer(
                                                allocator: BufferAllocator,
                                                location: Location
                                              ) extends NoOpFlightProducer with AutoCloseable {

  private val datasets = new ConcurrentHashMap[FlightDescriptor, BlockingQueue[ArrowFlightDataset]]()

  override def acceptPut(context: CallContext, flightStream: FlightStream, ackStream: StreamListener[PutResult]): Runnable = {
    () => {
      while (flightStream.next()) {
        val vectorSchemaRootSent = flightStream.getRoot
        val unloader = new VectorUnloader(vectorSchemaRootSent)
        val arrowRecordBatch = unloader.getRecordBatch
        val rows = flightStream.getRoot.getRowCount
        val dataset = ArrowFlightDataset(arrowRecordBatch, flightStream.getSchema, rows)
        if (!datasets.containsKey(flightStream.getDescriptor)) {
          println(s"Create new blocking queue")
          datasets.put(flightStream.getDescriptor, new ArrayBlockingQueue[ArrowFlightDataset](10000))
        }
        datasets.get(flightStream.getDescriptor).put(dataset)
      }
      ackStream.onCompleted()
    }
  }

  override def getStream(context: CallContext, ticket: Ticket, listener: ServerStreamListener): Unit = {
    val flightDescriptor = FlightDescriptor.path(new String(ticket.getBytes, StandardCharsets.UTF_8))
    val queue = datasets.get(flightDescriptor)
    val dataset = queue.take()
    if (dataset == null)
      throw CallStatus.NOT_FOUND.withDescription("Unknown descriptor").toRuntimeException

    val vectorSchemaRoot = VectorSchemaRoot.create(dataset.schema, allocator)
    try {
      val loader = new VectorLoader(vectorSchemaRoot)
      listener.start(vectorSchemaRoot)

      val arrowRecordBatch = dataset.arrowRecordBatch
      loader.load(arrowRecordBatch.cloneWithTransfer(allocator))
      listener.putNext()
      listener.completed()
    } finally {
      if (vectorSchemaRoot != null)
        vectorSchemaRoot.close()
    }
  }

  override def getFlightInfo(context: CallContext, descriptor: FlightDescriptor): FlightInfo = {
    val flightEndpoint = new FlightEndpoint(new Ticket(descriptor.getPath.get(0).getBytes(StandardCharsets.UTF_8)), location)
    val dataset = datasets.get(descriptor).peek()
    new FlightInfo(
      dataset.schema,
      descriptor,
      Collections.singletonList(flightEndpoint),
      /*bytes=*/ -1,
      dataset.rows
    )
  }

  override def listFlights(context: CallContext, criteria: Criteria, listener: StreamListener[FlightInfo]): Unit = {
    datasets.entrySet().stream().filter(k => k.getValue.size() > 0).forEach(k => listener.onNext(getFlightInfo(null, k.getKey)))
    listener.onCompleted()
  }

  override def close(): Unit = {
    val queues = datasets.values()
    queues.forEach(q => AutoCloseables.close(q))
  }
}
