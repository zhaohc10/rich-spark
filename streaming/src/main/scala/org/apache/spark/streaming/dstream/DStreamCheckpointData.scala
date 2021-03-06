/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.streaming.dstream

import java.io.{IOException, ObjectInputStream, ObjectOutputStream}

import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.internal.Logging
import org.apache.spark.streaming.event.Event
import org.apache.spark.util.Utils

import scala.collection.mutable
import scala.reflect.ClassTag

private[streaming]
class DStreamCheckpointData[T: ClassTag](dstream: DStream[T])
  extends Serializable with Logging {
  protected val data = new mutable.HashMap[Event, AnyRef]()

  // Mapping of the batch time to the checkpointed RDD file of that time
  @transient private var eventToCheckpointFile = new mutable.HashMap[Event, String]
  // Mapping of the batch time to the time of the oldest checkpointed RDD
  // in that batch's checkpoint data
  @transient private var eventToOldestCheckpointFileEvent = new mutable.HashMap[Event, Event]

  @transient private var fileSystem: FileSystem = null
  protected[streaming] def currentCheckpointFiles = data.asInstanceOf[mutable.HashMap[Event, String]]

  /**
   * Updates the checkpoint data of the DStream. This gets called every time
   * the graph checkpoint is initiated. Default implementation records the
   * checkpoint files at which the generated RDDs of the DStream have been saved.
   */
  def update(event: Event) {

    // Get the checkpointed RDDs from the generated RDDs
    val checkpointFiles = dstream.generatedRDDs.filter(_._2.getCheckpointFile.isDefined)
                                       .map(x => (x._1, x._2.getCheckpointFile.get))
    logDebug("Current checkpoint files:\n" + checkpointFiles.toSeq.mkString("\n"))

    // Add the checkpoint files to the data to be serialized
    if (checkpointFiles.nonEmpty) {
      currentCheckpointFiles.clear()
      currentCheckpointFiles ++= checkpointFiles
      // Add the current checkpoint files to the map of all checkpoint files
      // This will be used to delete old checkpoint files
      eventToCheckpointFile ++= currentCheckpointFiles
      // Remember the time of the oldest checkpoint RDD in current state
      eventToOldestCheckpointFileEvent(event) = currentCheckpointFiles.keys.min(Event.ordering)
    }
  }

  /**
   * Cleanup old checkpoint data. This gets called after a checkpoint of `time` has been
   * written to the checkpoint directory.
   */
  def cleanup(event: Event) {
    // Get the time of the oldest checkpointed RDD that was written as part of the
    // checkpoint of `event`
    eventToOldestCheckpointFileEvent.remove(event) match {
      case Some(lastCheckpointFileEvent) =>
        // Find all the checkpointed RDDs (i.e. files) that are older than `lastCheckpointFileEvent`
        // This is because checkpointed RDDs older than this are not going to be needed
        // even after master fails, as the checkpoint data of `event` does not refer to those files
        val filesToDelete = eventToCheckpointFile.filter(_._1.time < lastCheckpointFileEvent.time)
        logDebug(s"Files to delete on event $event:\n ${filesToDelete.mkString(",")}")
        filesToDelete.foreach {
          case (oldEvent, file) =>
            try {
              val path = new Path(file)
              if (fileSystem == null) {
                fileSystem = path.getFileSystem(dstream.ssc.sparkContext.hadoopConfiguration)
              }
              fileSystem.delete(path, true)
              eventToCheckpointFile -= oldEvent
              logInfo(s"Deleted checkpoint file '$file' for event $oldEvent")
            } catch {
              case e: Exception =>
                logWarning(s"Error deleting old checkpoint file '$file' for event $oldEvent", e)
                fileSystem = null
            }
        }
      case None =>
        logDebug("Nothing to delete")
    }
  }

  /**
   * Restore the checkpoint data. This gets called once when the DStream graph
   * (along with its output DStreams) is being restored from a graph checkpoint file.
   * Default implementation restores the RDDs from their checkpoint files.
   */
  def restore() {
    // Create RDDs from the checkpoint data
    currentCheckpointFiles.foreach {
      case(event, file) =>
        logInfo("Restoring checkpointed RDD for event " + event + " from file '" + file + "'")
        dstream.generatedRDDs += ((event, dstream.context.sparkContext.checkpointFile[T](file)))
    }
  }

  override def toString: String = {
    "[\n" + currentCheckpointFiles.size + " checkpoint files \n" +
      currentCheckpointFiles.mkString("\n") + "\n]"
  }

  @throws(classOf[IOException])
  private def writeObject(oos: ObjectOutputStream): Unit = Utils.tryOrIOException {
    logDebug(this.getClass().getSimpleName + ".writeObject used")
    if (dstream.context.graph != null) {
      dstream.context.graph.synchronized {
        if (dstream.context.graph.checkpointInProgress) {
          oos.defaultWriteObject()
        } else {
          val msg = "Object of " + this.getClass.getName + " is being serialized " +
            " possibly as a part of closure of an RDD operation. This is because " +
            " the DStream object is being referred to from within the closure. " +
            " Please rewrite the RDD operation inside this DStream to avoid this. " +
            " This has been enforced to avoid bloating of Spark tasks " +
            " with unnecessary objects."
          throw new java.io.NotSerializableException(msg)
        }
      }
    } else {
      throw new java.io.NotSerializableException(
        "Graph is unexpectedly null when DStream is being serialized.")
    }
  }

  @throws(classOf[IOException])
  private def readObject(ois: ObjectInputStream): Unit = Utils.tryOrIOException {
    logDebug(this.getClass().getSimpleName + ".readObject used")
    ois.defaultReadObject()
    eventToOldestCheckpointFileEvent = new mutable.HashMap[Event, Event]
    eventToCheckpointFile = new mutable.HashMap[Event, String]
  }
}
