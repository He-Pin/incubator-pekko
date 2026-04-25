/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pekko.stream.impl.io

import java.nio.ByteBuffer
import javax.net.ssl._
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.HandshakeStatus._
import javax.net.ssl.SSLEngineResult.Status._

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

import org.apache.pekko
import pekko.annotation.InternalApi
import pekko.stream._
import pekko.stream.TLSProtocol._
import pekko.stream.stage.{ GraphStage, InHandler, OutHandler, TimerGraphStageLogic }
import pekko.util.ByteString

/**
 * INTERNAL API.
 *
 * Direct-push GraphStage implementation for TLS.  Each instance is intended to
 * operate as its own async island in the materialized graph: callers should apply
 * [[org.apache.pekko.stream.Attributes.asyncBoundary]] to the resulting
 * [[org.apache.pekko.stream.scaladsl.BidiFlow]] so that the TLS work-loop runs on a
 * separately scheduled interpreter island and does not block the fusing actor of
 * adjacent stages.
 */
@InternalApi private[stream] final class TlsGraphStage(
    createSSLEngine: () => SSLEngine,
    verifySession: SSLSession => Try[Unit],
    closing: TLSClosing)
    extends GraphStage[BidiShape[SslTlsOutbound, ByteString, ByteString, SslTlsInbound]] {

  private val plainIn = Inlet[SslTlsOutbound]("TlsGraphStage.transportIn")
  private val plainOut = Outlet[SslTlsInbound]("TlsGraphStage.transportOut")
  private val cipherIn = Inlet[ByteString]("TlsGraphStage.cipherIn")
  private val cipherOut = Outlet[ByteString]("TlsGraphStage.cipherOut")

  override val shape: BidiShape[SslTlsOutbound, ByteString, ByteString, SslTlsInbound] =
    BidiShape(plainIn, cipherOut, cipherIn, plainOut)

  override def initialAttributes: Attributes = Attributes.name("TlsGraphStage")

  override def createLogic(inheritedAttributes: Attributes): TimerGraphStageLogic =
    new TimerGraphStageLogic(shape) {
      private val WarmupTimer = "TlsGraphStageWarmup"
      private val MaxTlsIterations = 1000

      private val transportOutBuffer = ByteBuffer.allocate(16665 + 2048)
      private val userOutBuffer = ByteBuffer.allocate(16665 * 2 + 2048)
      private val transportInBuffer = ByteBuffer.allocate(16665 + 2048)
      private val userInBuffer = ByteBuffer.allocate(16665 + 2048)

      private var engine: SSLEngine = _
      private var lastHandshakeStatus: HandshakeStatus = NOT_HANDSHAKING
      private var currentSession: SSLSession = _
      private var corkUser = true
      private var warm = false

      private var pendingPlainCommand: SslTlsOutbound = _
      private var pendingPlainBytes = ByteString.empty
      private var pendingCipherBytes = ByteString.empty

      private var pendingCipherOutput = ByteString.empty
      private var pendingPlainOutput: SslTlsInbound = null

      private var plainInputFinished = false
      private var cipherInputFinished = false
      private var plainOutputClosed = false
      private var cipherOutputClosed = false

      private var inboundClosed = false
      private var outboundCloseRequested = false
      private var unwrapNeedWrapCounter = 0

      prepare(userInBuffer)
      prepare(transportInBuffer)

      setHandler(
        plainIn,
        new InHandler {
          override def onPush(): Unit = {
            pendingPlainCommand = grab(plainIn)
            pumpTls()
          }

          override def onUpstreamFinish(): Unit = {
            plainInputFinished = true
            pumpTls()
          }

          override def onUpstreamFailure(ex: Throwable): Unit = failStage(ex)
        })

      setHandler(
        cipherIn,
        new InHandler {
          override def onPush(): Unit = {
            pendingCipherBytes = grab(cipherIn)
            pumpTls()
          }

          override def onUpstreamFinish(): Unit = {
            cipherInputFinished = true
            pumpTls()
          }

          override def onUpstreamFailure(ex: Throwable): Unit = failStage(ex)
        })

      setHandler(
        cipherOut,
        new OutHandler {
          override def onPull(): Unit = pumpTls()

          override def onDownstreamFinish(cause: Throwable): Unit = {
            cipherOutputClosed = true
            pendingCipherOutput = ByteString.empty
            completeStage()
          }
        })

      setHandler(
        plainOut,
        new OutHandler {
          override def onPull(): Unit = pumpTls()

          override def onDownstreamFinish(cause: Throwable): Unit = {
            plainOutputClosed = true
            pendingPlainOutput = null
            pumpTls()
          }
        })

      override def preStart(): Unit =
        try {
          engine = createSSLEngine()
          engine.beginHandshake()
          lastHandshakeStatus = engine.getHandshakeStatus
          currentSession = engine.getSession
          tryPullInputs()
          scheduleOnce(WarmupTimer, Duration.Zero)
        } catch {
          case NonFatal(ex) => failStage(ex)
        }

      override protected def onTimer(timerKey: Any): Unit =
        if (timerKey == WarmupTimer) {
          warm = true
          pumpTls()
        }

      private def pumpTls(): Unit = {
        if (isClosed(cipherOut) || cipherOutputClosed) return

        var iterations = 0
        var continue = true

        while (continue && iterations < MaxTlsIterations && !isClosed(cipherOut)) {
          iterations += 1
          continue = false

          if (pushPendingOutputs()) continue = true
          if (!warm) {
            tryPullInputs()
            return
          }

          if (materializePendingPlainCommand()) continue = true
          if (requestOutboundCloseIfNeeded()) continue = true
          if (closeInboundIfNeeded()) continue = true
          if (pushPendingOutputs()) continue = true

          if (shouldCompleteStage()) {
            completeStage()
            return
          }

          if (!hasPendingPlainOutput && shouldUnwrap) {
            continue = doUnwrapStep() || continue
          } else if (!hasPendingCipherOutput && shouldWrap) {
            continue = doWrapStep() || continue
          }

          if (pushPendingOutputs()) continue = true
          if (shouldCompleteStage()) {
            completeStage()
            return
          }

          tryPullInputs()
        }

        if (iterations >= MaxTlsIterations && !shouldCompleteStage()) {
          failStage(new IllegalStateException("TlsGraphStage exceeded TLS work loop without settling"))
        }
      }

      private def tryPullInputs(): Unit = {
        if (!plainInputFinished && pendingPlainCommand == null && pendingPlainBytes.isEmpty && !hasBeenPulled(plainIn))
          pull(plainIn)
        if (!cipherInputFinished && pendingCipherBytes.isEmpty && !hasBeenPulled(cipherIn))
          pull(cipherIn)
      }

      private def hasPendingCipherOutput: Boolean = pendingCipherOutput.nonEmpty
      private def hasPendingPlainOutput: Boolean = pendingPlainOutput ne null
      private def hasPendingUserData: Boolean = pendingPlainBytes.nonEmpty || (pendingPlainCommand ne null)

      private def shouldUnwrap: Boolean =
        engine != null &&
        !engine.isInboundDone &&
        (pendingCipherBytes.nonEmpty || (cipherInputFinished && !inboundClosed))

      private def shouldWrap: Boolean =
        engine != null &&
        !cipherOutputClosed &&
        !engine.isOutboundDone &&
        (lastHandshakeStatus == NEED_WRAP ||
        outboundCloseRequested ||
        (!corkUser && pendingPlainBytes.nonEmpty && lastHandshakeStatus != NEED_UNWRAP))

      private def materializePendingPlainCommand(): Boolean =
        if (pendingPlainCommand == null) false
        else
          pendingPlainCommand match {
            case bytes: SendBytes if !corkUser && pendingPlainBytes.isEmpty =>
              pendingPlainBytes = bytes.bytes
              pendingPlainCommand = null
              true

            case negotiate: NegotiateNewSession if pendingPlainBytes.isEmpty =>
              currentSession.invalidate()
              TlsUtils.applySessionParameters(engine, negotiate)
              engine.beginHandshake()
              lastHandshakeStatus = engine.getHandshakeStatus
              corkUser = true
              pendingPlainCommand = null
              true

            case _ => false
          }

      private def requestOutboundCloseIfNeeded(): Boolean = {
        val noUserWork = pendingPlainBytes.isEmpty && pendingPlainCommand == null
        val closeForCompletion =
          plainInputFinished &&
          noUserWork &&
          (!closing.ignoreComplete || plainOutputClosed)
        val closeForCancellation =
          plainOutputClosed &&
          (!closing.ignoreCancel || plainInputFinished)

        if (engine == null || outboundCloseRequested || engine.isOutboundDone || !(closeForCompletion || closeForCancellation))
          false
        else if (mayCloseOutbound) {
          engine.closeOutbound()
          lastHandshakeStatus = engine.getHandshakeStatus
          outboundCloseRequested = true
          true
        } else false
      }

      private def closeInboundIfNeeded(): Boolean =
        if (engine == null || inboundClosed || !cipherInputFinished || pendingCipherBytes.nonEmpty) false
        else {
          inboundClosed = true
          try engine.closeInbound()
          catch {
            case _: SSLException if !plainOutputClosed && !hasPendingPlainOutput =>
              pendingPlainOutput = SessionTruncated
          }
          lastHandshakeStatus = engine.getHandshakeStatus
          true
        }

      private def shouldCompleteStage(): Boolean =
        !hasPendingCipherOutput &&
        !hasPendingPlainOutput &&
        (cipherOutputClosed ||
        (engine != null &&
        engine.isOutboundDone &&
        (engine.isInboundDone || inboundClosed || plainOutputClosed) &&
        !hasPendingUserData))

      private def pushPendingOutputs(): Boolean = {
        var pushed = false

        if (pendingCipherOutput.nonEmpty && isAvailable(cipherOut)) {
          val out = pendingCipherOutput
          pendingCipherOutput = ByteString.empty
          push(cipherOut, out)
          pushed = true
        }

        if (!plainOutputClosed && pendingPlainOutput != null && isAvailable(plainOut)) {
          val out = pendingPlainOutput
          pendingPlainOutput = null
          push(plainOut, out)
          pushed = true
        }

        pushed
      }

      private def doWrapStep(): Boolean = {
        pendingPlainBytes = chopInto(pendingPlainBytes, userInBuffer)
        transportOutBuffer.clear()

        val result = engine.wrap(userInBuffer, transportOutBuffer)
        lastHandshakeStatus = result.getHandshakeStatus

        if (result.getHandshakeStatus == FINISHED) handshakeFinished()
        runDelegatedTasks()

        pendingPlainBytes = putBack(userInBuffer, pendingPlainBytes)

        result.getStatus match {
          case OK =>
            if (transportOutBuffer.position() == 0 && lastHandshakeStatus == NEED_WRAP)
              failStage(new IllegalStateException("SSLEngine trying to loop NEED_WRAP without producing output"))
            captureCipherOutput()
            true

          case CLOSED =>
            captureCipherOutput()
            true

          case status =>
            failStage(new IllegalStateException(s"unexpected status $status in TLS wrap"))
            false
        }
      }

      private def doUnwrapStep(): Boolean = {
        val ignoreOutput = plainOutputClosed
        pendingCipherBytes = chopInto(pendingCipherBytes, transportInBuffer)
        val oldInputPosition = transportInBuffer.position()

        val result = engine.unwrap(transportInBuffer, userOutBuffer)
        if (ignoreOutput) userOutBuffer.clear()
        lastHandshakeStatus = result.getHandshakeStatus
        runDelegatedTasks()

        result.getStatus match {
          case OK =>
            result.getHandshakeStatus match {
              case NEED_WRAP =>
                unwrapNeedWrapCounter += 1
                if (unwrapNeedWrapCounter > MaxTlsIterations) {
                  failStage(
                    new IllegalStateException(
                      s"Stuck in TLS unwrap loop while switching to NEED_WRAP (handshake=$lastHandshakeStatus, remaining=${transportInBuffer.remaining})"))
                  false
                } else {
                  pendingCipherBytes = putBack(transportInBuffer, pendingCipherBytes)
                  true
                }

              case FINISHED =>
                capturePlainOutput(currentSession)
                handshakeFinished()
                pendingCipherBytes = putBack(transportInBuffer, pendingCipherBytes)
                true

              case NEED_UNWRAP
                  if transportInBuffer.hasRemaining &&
                  userOutBuffer.position() == 0 &&
                  transportInBuffer.position() == oldInputPosition =>
                failStage(new IllegalStateException("SSLEngine trying to loop NEED_UNWRAP without consuming input"))
                false

              case _ =>
                capturePlainOutput(currentSession)
                pendingCipherBytes = putBack(transportInBuffer, pendingCipherBytes)
                true
            }

          case CLOSED =>
            capturePlainOutput(currentSession)
            pendingCipherBytes = putBack(transportInBuffer, pendingCipherBytes)
            true

          case BUFFER_UNDERFLOW =>
            capturePlainOutput(currentSession)
            pendingCipherBytes = putBack(transportInBuffer, pendingCipherBytes)
            true

          case BUFFER_OVERFLOW =>
            capturePlainOutput(currentSession)
            pendingCipherBytes = putBack(transportInBuffer, pendingCipherBytes)
            true

          case null =>
            failStage(new IllegalStateException("unexpected status null in TLS unwrap"))
            false
        }
      }

      @tailrec
      private def runDelegatedTasks(): Unit = {
        val task = engine.getDelegatedTask
        if (task ne null) {
          task.run()
          runDelegatedTasks()
        } else {
          lastHandshakeStatus = engine.getHandshakeStatus
        }
      }

      private def handshakeFinished(): Unit = {
        val session = engine.getSession
        verifySession(session) match {
          case Success(()) =>
            currentSession = session
            corkUser = false
            capturePlainOutput(currentSession)

          case Failure(ex) =>
            failStage(ex)
        }
      }

      private def mayCloseOutbound: Boolean =
        lastHandshakeStatus match {
          case HandshakeStatus.NOT_HANDSHAKING | HandshakeStatus.FINISHED => true
          case _                                                          => false
        }

      private def captureCipherOutput(): Unit = {
        transportOutBuffer.flip()
        if (transportOutBuffer.hasRemaining) pendingCipherOutput = ByteString(transportOutBuffer)
        transportOutBuffer.clear()
      }

      private def capturePlainOutput(session: SSLSession): Unit = {
        userOutBuffer.flip()
        if (!plainOutputClosed && userOutBuffer.hasRemaining) {
          pendingPlainOutput = SessionBytes(session, ByteString(userOutBuffer))
          unwrapNeedWrapCounter = 0
        }
        userOutBuffer.clear()
      }

      private def chopInto(source: ByteString, buffer: ByteBuffer): ByteString = {
        buffer.compact()
        val copied = source.copyToBuffer(buffer)
        buffer.flip()
        source.drop(copied)
      }

      private def putBack(buffer: ByteBuffer, source: ByteString): ByteString = {
        val result =
          if (buffer.hasRemaining) ByteString(buffer) ++ source
          else source
        prepare(buffer)
        result
      }

      private def prepare(buffer: ByteBuffer): Unit = {
        buffer.clear()
        buffer.limit(0)
      }
    }
}
