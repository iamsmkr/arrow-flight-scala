package com.iamsmkr.arrowflight

import org.apache.arrow.util.AutoCloseables
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch
import org.apache.arrow.vector.types.pojo.Schema

import java.util

private[arrowflight] case class ArrowFlightDataset(
                                                    arrowRecordBatch: ArrowRecordBatch,
                                                    schema: Schema,
                                                    rows: Long
                                                  ) extends AutoCloseable {

  override def close(): Unit = AutoCloseables.close(arrowRecordBatch)
}
