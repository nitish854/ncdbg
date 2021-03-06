package com.programmaticallyspeaking.ncd.nashorn

import com.programmaticallyspeaking.ncd.host._
import com.programmaticallyspeaking.ncd.infra.IdGenerator
import com.programmaticallyspeaking.ncd.messaging.{Observable, Observer, Subject, Subscription}
import com.sun.jdi.event._
import com.sun.jdi.{StackFrame => _, _}
import org.slf4s.Logging

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal


object NashornDebuggerHost {
  import JDIExtensions._
  import TypeConstants._

  object JDWP_ERR_INVALID_SLOT {
    def unapply(v: Any): Option[Throwable] = v match {
      case e: InternalException if e.errorCode() == 35 => Some(e)
      case _ => None
    }
  }

  val ECMAException_create = "create"

  val wantedTypes = Map(
    NIR_ScriptRuntime -> true,
    NIR_Context -> true,
    JL_Boolean -> true,
    JL_Integer -> true,
    JL_Long -> true,
    JL_Double -> true,
    NIO_Global -> true,
    NIR_JSType -> false // don't stop init
  )

  type CodeEvaluator = (String, Map[String, AnyRef]) => ValueNode

//  case object PostponeInitialize extends NashornScriptOperation

  private[nashorn] case class ObjectPropertiesKey(objectId: ObjectId, onlyOwn: Boolean, onlyAccessors: Boolean)

  /** This marker is embedded in all scripts evaluated by NashornDebuggerHost on behalf of Chrome DevTools. The problem
    * this solves is that such evaluated scripts are detected on startup (i.e. when reconnecting to a running target)
    * but they are not interesting to show in DevTools. Thus NashornDebuggerHost will not consider scripts that contain
    * this marker.
    */
  val EvaluatedCodeMarker = "__af4caa215e04411083cfde689d88b8e6__"

  // Prefix for synthetic properties added to artificial scope JS objects. The prefix is chosen so that it can never
  // clash with the name of a real local variable.
  val hiddenPrefix = "||"

  val hiddenPrefixEscapedForUseInJavaScriptRegExp = "[|][|]"

  val localScopeObjectIdPrefix = "$$locals-"
  val stackFrameIndexExtraProp = hiddenPrefix + "stackIndex"

  case class ObjectDescriptor(native: Option[Value], marshalled: ComplexNode, extras: Map[String, ValueNode])

  private val infrastructureThreadNames = Set("attach listener", "reference handler", "finalizer", "signal dispatcher")

  def isInfrastructureThread(t: ThreadReference) = infrastructureThreadNames.contains(t.name().toLowerCase)
  def isRunningThread(t: ThreadReference) = t.status() == ThreadReference.THREAD_STATUS_RUNNING

  val IL_POP = 0x57     // pop result after function return

  /**
    * Information used to determine if a breakpoint request event is in the exact same location as the previous step
    * event. When we do expensive step into after stepping using a StepRequest, it seems as if the BreakpointRequest
    * is hit in the current location right away.
    */
  case class StepLocationInfo(location: Location, stackSize: Int)
  object StepLocationInfo {
    def from(ev: LocatableEvent) = StepLocationInfo(ev.location(), ev.thread().frameCount())
  }

  val ScriptClassNamePrefix = "jdk.nashorn.internal.scripts.Script$"

  /**
    * Key for an EventRequest property that stores a handler to execute when an event for the request is seen.
    */
  private[nashorn] object EventHandlerKey

  /**
    * The type of a handler for an event associated with an event request.
    */
  type EventHandler = (Event) => Boolean

  case class StackFrameImpl(id: String, thisObj: ValueNode, scopeChain: Seq[Scope],
                            breakableLocation: BreakableLocation,
                            eval: CodeEvaluator,
                            functionDetails: FunctionDetails) extends StackFrame {
    val scriptId = breakableLocation.script.id
    val scriptURL = breakableLocation.script.url
    val location = breakableLocation.scriptLocation
  }


  object NoScriptReason {
    sealed trait EnumVal
    case object NoSource extends EnumVal
    case object EvaluatedCode extends EnumVal
  }

  case class StackFrameHolder(stackFrame: Option[StackFrame], location: Location) {
    val mayBeAtSpecialStatement = stackFrame.isEmpty
    val belongsToScript = stackFrame.isDefined
    val isAtDebuggerStatement = location.isDebuggerStatement
  }

  sealed trait InternalState
  case object Pause extends InternalState
  case object Unpause extends InternalState
}

class NashornDebuggerHost(val virtualMachine: XVirtualMachine, protected val asyncInvokeOnThis: ((NashornScriptHost) => Any) => Future[Any])
    extends NashornScriptHost with Logging with ProfilingSupport with ObjectPropertiesSupport with StepSupport with BreakpointSupport with PauseSupport with PrintSupport {
  import Breakpoints._
  import JDIExtensions._
  import NashornDebuggerHost._
  import TypeConstants._

  import ExecutionContext.Implicits._
  import scala.collection.JavaConverters._

  protected val stackframeIdGenerator = new IdGenerator("ndsf")

  private val eventSubject = Subject.serialized[ScriptEvent]

  protected val internalStateSubject = Subject.serialized[InternalState] //TODO: Serialized isn't needed

  private var hostInitializationComplete = false

  private var infoAboutLastStep: Option[StepLocationInfo] = None

  protected val mappingRegistry: MappingRegistry = new MappingRegistryImpl

  // Data that are defined when the VM has paused on a breakpoint or encountered a step event
  protected var pausedData: Option[PausedData] = None

  private val _scripts = new Scripts
  protected val _breakableLocations = new BreakableLocations(virtualMachine, _scripts)
  private val _scriptFactory = new ScriptFactory(virtualMachine)
  protected val _breakpoints = new ActiveBreakpoints

  protected val _pauser = new Pauser(_breakpoints, _scripts, emitEvent)

  /**
    * Configure what we do when we encounter one of the wanted types.
    */
  private val actionPerWantedType: Map[String, (ClassType) => Unit] = Map(
    NIR_ScriptRuntime -> enableBreakingAtDebuggerStatement _,
    NIO_Global -> enablePrintCapture _
  )

  private val _scriptPublisher = new ScriptPublisher(emitEvent)
  private val _scanner = new ClassScanner(virtualMachine, _scripts, _scriptFactory, _scriptPublisher, _breakableLocations, actionPerWantedType)

  protected val typeLookup = new TypeLookup {
    override def apply(name: String): Option[ClassType] = _scanner.typeByName(name)
  }

  private val boxer = new Boxer(typeLookup)
  protected val codeEval = new CodeEval(typeLookup, preventGC)
  private val stackBuilder = new StackBuilder(stackframeIdGenerator, typeLookup, mappingRegistry, codeEval, boxer,
    (location: Location) => findBreakableLocation(location))

  private val _stackFramEval = new StackFrameEvaluator(mappingRegistry, boxer)

  def initialize(): Unit = {
    enableExceptionPausing()

    _scanner.setup(new Observer[ScanAction] {
      // Not used...
      override def onError(error: Throwable): Unit = {}

      override def onComplete(): Unit = considerInitializationToBeComplete()

      override def onNext(item: ScanAction): Unit = item match {
        case ScanMoreLater(action) => asyncInvokeOnThis(_ => action())
        case _ =>
      }
    })
  }

  //TODO: This method is never called, because there's no graceful way to stop NCDbg ATM.
  def prepareForExit(): Unit = {
    try {
      virtualMachine.enableGarbageCollectionForAllReferences()
    } catch {
      case NonFatal(t) =>
        log.error("Failed to enable collection for one or more object references.", t)
    }
  }

  private def considerInitializationToBeComplete(): Unit = {
    log.info("Host initialization is complete.")
    hostInitializationComplete = true
    emitEvent(InitialInitializationComplete)
  }

  protected def emitEvent(event: ScriptEvent): Unit = {
    // Emit asynchronously so that code that observes the event can interact with the host without deadlocking it.
    log.debug(s"Emitting event of type ${event.getClass.getSimpleName}")
    Future(eventSubject.onNext(event))
  }

  private def signalComplete(): Unit = {
    // Emit asynchronously so that code that observes the event can interact with the host without deadlocking it.
    log.info("Signalling completion.")
    Future(eventSubject.onComplete())
  }

  // toList => iterate over a copy since we mutate inside the foreach
  private def hasDeathOrDisconnectEvent(eventSet: EventSet) = eventSet.asScala.collect {
    case e: VMDeathEvent => e
    case e: VMDisconnectEvent => e
  }.nonEmpty

  private def removeAnyStepRequest(): Unit = {
    val erm = virtualMachine.eventRequestManager()
    erm.deleteEventRequests(erm.stepRequests())
  }

  private def shouldIgnoreLocationOnStep(location: Location) = {
    // We should *maybe* ignore IL_POP. Most of the time, it's just popping the return value of a function.
    // However, if that happens on the last line of the method, we'd like to pause inside the callee.
    location.byteCode match {
      case IL_POP => !location.isLastLineInFunction
      case _ => false
    }

  }

  private def handleStepOrMethodEvent(ev: LocatableEvent): Boolean = {
    var doResume = true
    virtualMachine.eventRequestManager().deleteEventRequest(ev.request())
    if (shouldIgnoreLocationOnStep(ev.location())) {
      // We most likely hit an "intermediate" location after returning from a function.
      log.debug(s"Skipping step/method event at ${ev.location()} with byte code: 0x${ev.location().byteCode.toHexString}")
      createEnabledStepOverRequest(ev.thread(), isAtDebuggerStatement = false)
    } else {
      log.debug(s"Considering step/method event at ${ev.location()} with byte code: 0x${ev.location().byteCode.toHexString}")
      doResume = _pauser.handleBreakpoint(ev, prepareForPausing(ev))
      if (!doResume) infoAboutLastStep = Some(StepLocationInfo.from(ev))
    }
    doResume
  }

  // Creates the PausedData structure
  private def prepareForPausing(ev: LocatableEvent): PausedData = {
    assert(pausedData.isEmpty, "prepareForPausing in paused state")

    // Start with a fresh object registry
    mappingRegistry.clear()

    internalStateSubject.onNext(Pause)

    implicit val thread = ev.thread()
    val pd = new PausedData(thread, createMarshaller(), stackBuilder, ev)
    pausedData = Some(pd)
    pd
  }

  private def cleanupPausing(): Unit = {
    // We evaluate code with all ClassPrepare requests disabled to avoid deadlock. However, code evaluation mau
    // result in an added script (e.g. when the 'load' extension is used). Go through added classes and let the
    // scanner decide if scripts were added.
    val addedClasses = pausedData.toSeq.flatMap(_.addedClasses())
    pausedData = None
    mappingRegistry.clear() // only valid when paused

    internalStateSubject.onNext(Unpause)

    if (addedClasses.nonEmpty) {
      log.debug(s"Scanning ${addedClasses.size} classes to detect scripts added during code evaluation.")
      addedClasses.foreach(_scanner.consider)
    }
  }

  private def maybeEmitErrorEvent(pd: PausedData): Unit = pd.exceptionEventInfo match {
    case Some(info) =>
      if (info.exceptionType == ExceptionType.UncaughtByScript) {
        info.marshalledException match {
          case Right(err) => emitEvent(UncaughtError(err))
          case Left(reason) => log.warn(reason)
        }
      }
    case None =>
  }

  def handleOperation(eventQueueItem: NashornScriptOperation): Unit = eventQueueItem match {
    case NashornEventSet(es) if hasDeathOrDisconnectEvent(es) =>
      signalComplete()
    case NashornEventSet(eventSet) =>
      var doResume = true
      val wasPausedAtEntry = pausedData.isDefined
      eventSet.asScala.foreach { ev =>
        try {
          // Invoke any event handler associated with the request for the event.
          val eventIsConsumed = ev.handle().contains(true)

          if (!eventIsConsumed) {
            val pf = if (pausedData.isDefined) handleEventPaused else handleEventNotPaused
            doResume = pf.orElse(handleEventCommon).apply(ev)

            // Clear info about the last step as soon as we see a non-step event.
            if (!ev.isInstanceOf[StepEvent]) {
              infoAboutLastStep = None
            }
          }
        } catch {
          case NonFatal(ex) =>
            log.error(s"Failed to handle event ${ev.getClass.getName}", ex)
        }
      }
      if (doResume) {
        if (!wasPausedAtEntry) cleanupPausing()
        resume(eventSet)
      }
    case operation =>
      throw new IllegalArgumentException("Unknown operation: " + operation)
  }

  private val handleEventCommon: PartialFunction[Event, Boolean] = {
    case ev: ClassPrepareEvent =>
      _scanner.handleEvent(ev)
      true

    case _: VMStartEvent =>
      // ignore it, but don't log a warning
      true

    case other if pausedData.isDefined =>
      // Don't react on events if we're paused. Only one thread can be debugged at a time. Only log this on
      // trace level to avoid excessive logging in a multi-threaded system.
      val eventName = other.getClass.getSimpleName
      log.trace(s"Ignoring Nashorn event $eventName since we're already paused.")
      true

    case other =>
      log.warn("Unknown event: " + other)
      true
  }

  private val handleEventPaused: PartialFunction[Event, Boolean] = PartialFunction.empty

  private val handleEventNotPaused: PartialFunction[Event, Boolean] = {
    case ev: MethodEntryEvent => handleStepOrMethodEvent(ev)
    case ev: MethodExitEvent => handleStepOrMethodEvent(ev)
    case ev: StepEvent => handleStepOrMethodEvent(ev)

    case ev: BreakpointEvent =>
      infoAboutLastStep match {
        case Some(info) if info == StepLocationInfo.from(ev) =>
          // We stopped in the same location. Continue!
          log.debug(s"Breakpoint event in the same location (${ev.location()}) as the previous step event. Ignoring!")
          true
        case _ =>
          removeAnyStepRequest()

          _pauser.handleBreakpoint(ev, prepareForPausing(ev))
      }

    case ev: ExceptionEvent =>
      val exceptionTypeName = ev.exception().referenceType().name()
      val isECMAException = exceptionTypeName == NIR_ECMAException
      if (isECMAException) {
        val pd = prepareForPausing(ev)
        maybeEmitErrorEvent(pd)
        _pauser.handleBreakpoint(ev, pd)
      } else {
        log.trace(s"Ignoring non-ECMA exception of type $exceptionTypeName")
        true
      }
  }

  private def resume(eventSet: EventSet): Unit = {
    enableGarbageCollectionWhereDisabled()
    eventSet.resume()
  }

  protected def functionDetails(functionMethod: Method): FunctionDetails = {
    FunctionDetails(functionMethod.name())
  }

  private def enableGarbageCollectionWhereDisabled(): Unit = {
    virtualMachine.enableGarbageCollectionWhereDisabled()
  }

  protected def disableGarbageCollectionFor(value: Value, entireSession: Boolean = false): Unit = {
    virtualMachine.disableGarbageCollectionFor(value, entireSession)
  }

  private def preventGC(value: Value, lifecycle: Lifecycle.EnumVal): Unit = {
    disableGarbageCollectionFor(value, lifecycle == Lifecycle.Session)
  }

  private def createMarshaller()(implicit threadReference: ThreadReference): Marshaller = {
    new Marshaller(mappingRegistry) {
      override def marshal(value: Value): ValueNode = {
        disableGarbageCollectionFor(value)
        super.marshal(value)
      }
    }
  }

  override def scripts: Seq[Script] = _scripts.scripts

  override def findScript(id: ScriptIdentity): Option[Script] = _scripts.byId(id)

  override def events: Observable[ScriptEvent] = new Observable[ScriptEvent] {
    override def subscribe(observer: Observer[ScriptEvent]): Subscription = {
      // Make sure the observer sees that we're initialized
      if (hostInitializationComplete) {
        observer.onNext(InitialInitializationComplete)
      }
      eventSubject.subscribe(observer)
    }
  }

  protected def findBreakableLocation(location: Location): Option[BreakableLocation] =
    _breakableLocations.byLocation(location)

  protected def findBreakableLocationsAtLine(id: ScriptIdentity, lineNumber: Int): Option[Seq[BreakableLocation]] =
    _breakableLocations.atLine(id, lineNumber)

  protected def resumeWhenPaused(): Unit = pausedData match {
    case Some(data) =>
      log.info("Resuming virtual machine")
      enableGarbageCollectionWhereDisabled()
      virtualMachine.resume()
      cleanupPausing()
      emitEvent(Resumed)
    case None =>
      log.debug("Ignoring resume request when not paused (no pause data).")
  }

  private def removeAllBreakpoints(): Unit = _breakpoints.disableAll()

  override def reset(): Unit = {
    log.info("Resetting VM...")
    _pauser.pauseOnBreakpoints(false)
    removeAllBreakpoints()
    resume()
  }

  override def removeBreakpointById(id: String): Unit = _breakpoints.disableById(id)


  override def evaluateOnStackFrame(stackFrameId: String, expression: String, namedObjects: Map[String, ObjectId]): Try[ValueNode] = Try {
    pausedData match {
      case Some(pd) => _stackFramEval.evaluateOnStackFrame(pd, stackFrameId, expression, namedObjects)
      case None =>
        log.warn(s"Evaluation of '$expression' for stack frame $stackFrameId cannot be done in a non-paused state.")
        throw new IllegalStateException("Code evaluation can only be done in a paused state.")
    }
  }

  override def restartStackFrame(stackFrameId: String): Seq[StackFrame] = {
    pausedData match {
      case Some(pd) =>
        log.warn(s"Request to restart stack frame $stackFrameId. Note that depending on the Java version of the target, this may cause the target to crash.")
        // Get the Location of the stack frame to pop
        pd.stackFrames.find(_.id == stackFrameId) match {
          case Some(sf: StackFrameImpl) =>
            // Now get the current stack frame list and identify the correct target. This is needed since the old
            // stack frame list isn't valid anymore (due to thread resume due to marshalling).
            pd.thread.frames().asScala.find(f => sf.breakableLocation.hasLocation(f.location())) match {
              case Some(jdiStackFrame) =>
                log.debug(s"Popping stack frame at location ${jdiStackFrame.location()}")

                // ThreadReference.popFrames(StackFrame) API doc:
                // "All frames up to and including the frame are popped off the stack. The frame previous to the
                // parameter frame will become the current frame. After this operation, this thread will be suspended
                // at the invoke instruction of the target method that created frame. The frame's method can be
                // reentered with a step into the instruction.
                pd.thread.popFrames(jdiStackFrame)

                // Don't grab new frames - simply assume that we can reuse a slice of the current list.
                pd.stackFrames.span(_ ne sf)._2.tail

              case None =>
                throw new IllegalArgumentException("Unknown stack frame location: " + sf.breakableLocation)
            }
          case _ =>
            val availableIdsAsString = pd.stackFrames.map(_.id).mkString(", ")
            throw new IllegalArgumentException(s"Stack frame ID '$stackFrameId' is unknown. Available: $availableIdsAsString")
        }

      case None =>
        throw new IllegalStateException("Frame restart can only be done in a paused state.")
    }
  }
}


