package com.programmaticallyspeaking.ncd.nashorn

import com.programmaticallyspeaking.ncd.host.types.ObjectPropertyDescriptor
import com.programmaticallyspeaking.ncd.host.{ObjectId, StackFrame}
import com.programmaticallyspeaking.ncd.nashorn.NashornDebuggerHost.ObjectPropertiesKey
import com.sun.jdi.{ReferenceType, ThreadReference}
import com.sun.jdi.event.{ExceptionEvent, LocatableEvent}

import scala.collection.mutable

private[nashorn] class PausedData(val thread: ThreadReference, val marshaller: Marshaller, stackBuilder: StackBuilder, event: LocatableEvent) {
  import scala.collection.JavaConverters._

  // Capture known classes when pausing, to be able to diff afterwards. This is currently the only way I know to
  // detect classes added during code evaluation.
  private val classesBefore = thread.virtualMachine().allClasses().asScala.toSet

  def addedClasses(): Seq[ReferenceType] = {
    val classesNow = thread.virtualMachine().allClasses().asScala.toSet
    classesNow.diff(classesBefore).toSeq
  }

  /** We assume that we can cache object properties as long as we're in a paused state. Since we're connected to a
    * Java process, an arbitrary Java object may change while in this state, so we only cache JS objects.
    */
  val objectPropertiesCache = mutable.Map[ObjectPropertiesKey, Seq[(String, ObjectPropertyDescriptor)]]()

  val propertyHolderCache = mutable.Map[ObjectId, Option[PropertyHolder]]()

  lazy val stackFrameHolders = stackBuilder.captureStackFrames(thread)(marshaller)

  def pausedInAScript: Boolean = stackFrameHolders.headOption.exists(h => h.stackFrame.isDefined || h.mayBeAtSpecialStatement)

  def isAtDebuggerStatement: Boolean = stackFrameHolders.headOption.exists(_.isAtDebuggerStatement)

  lazy val stackFrames: Seq[StackFrame] = stackFrameHolders.flatMap(_.stackFrame)

  lazy val exceptionEventInfo: Option[ExceptionEventInfo] = event match {
    case ex: ExceptionEvent => Some(new ExceptionEventInfo(ex, stackFrameHolders, marshaller))
    case _ => None
  }
}
