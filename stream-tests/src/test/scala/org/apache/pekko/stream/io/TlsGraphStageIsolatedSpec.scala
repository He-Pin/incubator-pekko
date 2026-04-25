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

package org.apache.pekko.stream.io

import javax.net.ssl._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Success

import org.apache.pekko
import pekko.NotUsed
import pekko.stream._
import pekko.stream.TLSProtocol._
import pekko.stream.impl.io.TlsGraphStage
import pekko.stream.scaladsl._
import pekko.stream.scaladsl.GraphDSL.Implicits._
import pekko.stream.testkit.{ StreamSpec, TestPublisher }
import pekko.testkit.WithLogCapturing
import pekko.util.ByteString

/**
 * Isolates the most timing-sensitive GraphStage tests in their own suite.
 *
 * These scenarios exercise startup failure ordering and TLS 1.2 renegotiation.
 * Keeping them separate avoids cross-test interference from long-lived TLS
 * session state while still validating the GraphStage path directly.
 */
class TlsGraphStageIsolatedSpec extends StreamSpec(TlsSpec.configOverrides) with WithLogCapturing {

  import TlsSpec._

  /**
   * Constructs a [[BidiFlow]] backed by a single [[TlsGraphStage]] instance.
   *
   * When `asyncBoundary` is `true` (the default) the stage is placed in its own
   * async island via [[Attributes.asyncBoundary]], matching the intended production
   * topology where each TLS endpoint runs in its own interpreter island. Set it to
   * `false` only when exercising raw stage mechanics in complete isolation (e.g.
   * failure-propagation micro-tests that deliberately avoid a boundary).
   */
  private def stageFlow(
      ctx: SSLContext,
      ciphers: Set[String],
      clientMode: Boolean,
      closing: TLSClosing,
      asyncBoundary: Boolean = true): BidiFlow[SslTlsOutbound, ByteString, ByteString, SslTlsInbound, NotUsed] = {
    val stage = new TlsGraphStage(
      () => {
        val engine = ctx.createSSLEngine()
        engine.setUseClientMode(clientMode)
        if (ciphers.nonEmpty) engine.setEnabledCipherSuites(ciphers.toArray)
        engine
      },
      _ => Success(()),
      closing)
    val flow = BidiFlow.fromGraph(stage)
    // Each TLS instance is its own async island so the SSLEngine work-loop does
    // not block the fusing actor of neighbouring stages.
    if (asyncBoundary) flow.addAttributes(Attributes.asyncBoundary) else flow
  }

  /**
   * Connects a client [[TlsGraphStage]] to a server [[TlsGraphStage]] in a loopback
   * topology.  Each side runs as its own async island (one island per TLS engine),
   * mirroring the intended production wiring.
   */
  private def loopbackFlow(
      ctx: SSLContext,
      ciphers: Set[String],
      clientClosing: TLSClosing,
      serverClosing: TLSClosing,
      flow: Flow[SslTlsInbound, SslTlsOutbound, NotUsed]): Flow[SslTlsOutbound, SslTlsInbound, NotUsed] = {
    // asyncBoundary = true (default): one async island per TLS instance.
    val client = stageFlow(ctx, ciphers, clientMode = true, clientClosing)
    val server = stageFlow(ctx, ciphers, clientMode = false, serverClosing)
    client.atop(server.reversed).join(flow)
  }

  private val echoApplicationFlow = Flow[SslTlsInbound].collect {
    case SessionBytes(_, b) => SendBytes(b)
  }

  private def roundTrip(
      ctx: SSLContext,
      inputs: Seq[ByteString],
      clientClosing: TLSClosing = IgnoreComplete,
      serverClosing: TLSClosing = IgnoreComplete,
      timeout: FiniteDuration = 20.seconds): ByteString = {
    val expectedBytes = inputs.foldLeft(0)(_ + _.size)
    val received =
      Source(inputs.map(SendBytes.apply).toList)
        .via(loopbackFlow(ctx, TLS12Ciphers, clientClosing, serverClosing, echoApplicationFlow))
        .collect { case SessionBytes(_, b) => b }

    if (expectedBytes == 0) {
      val outputs = Await.result(received.runWith(Sink.seq), timeout)
      outputs.foldLeft(ByteString.empty)(_ ++ _)
    } else {
      Await.result(
        received
          .scan(ByteString.empty)(_ ++ _)
          .drop(1)
          .filter(_.size >= expectedBytes)
          .runWith(Sink.head),
        timeout)
    }
  }

  "TlsGraphStage isolated cases" must {

    "reliably cancel subscriptions when cipherIn (TransportIn) fails early" in {
      val ex = new Exception("transport-in-failure")
      // asyncBoundary = false: this test exercises stage-level error propagation in
      // isolation and does not need a separate async island.
      val client = stageFlow(initSslContext("TLSv1.2"), TLS12Ciphers, clientMode = true, EagerClose,
        asyncBoundary = false)

      val (sub, out1, out2) =
        RunnableGraph
          .fromGraph(
            GraphDSL.createGraph(
              Source.asSubscriber[SslTlsOutbound],
              Sink.head[ByteString],
              Sink.head[SslTlsInbound])((_, _, _)) { implicit b => (s, o1, o2) =>
              val tls = b.add(client)
              s        ~> tls.in1
              tls.out1 ~> o1
              o2 <~ tls.out2
              tls.in2 <~ Source.failed(ex)
              ClosedShape
            })
          .run()

      the[Exception] thrownBy Await.result(out1, 3.seconds) should be(ex)
      the[Exception] thrownBy Await.result(out2, 3.seconds) should be(ex)
      val pub = TestPublisher.probe()
      pub.subscribe(sub)
      pub.expectSubscription().expectCancellation()
    }

    "reliably cancel subscriptions when plainIn (UserIn) fails early" in {
      val ex = new Exception("user-in-failure")
      // asyncBoundary = false: this test exercises stage-level error propagation in
      // isolation and does not need a separate async island.
      val client = stageFlow(initSslContext("TLSv1.2"), TLS12Ciphers, clientMode = true, EagerClose,
        asyncBoundary = false)

      val (sub, out1, out2) =
        RunnableGraph
          .fromGraph(
            GraphDSL.createGraph(
              Source.asSubscriber[ByteString],
              Sink.head[ByteString],
              Sink.head[SslTlsInbound])((_, _, _)) { implicit b => (s, o1, o2) =>
              val tls = b.add(client)
              Source.failed[SslTlsOutbound](ex) ~> tls.in1
              tls.out1                          ~> o1
              o2 <~ tls.out2
              tls.in2 <~ s
              ClosedShape
            })
          .run()

      the[Exception] thrownBy Await.result(out1, 3.seconds) should be(ex)
      the[Exception] thrownBy Await.result(out2, 3.seconds) should be(ex)
      val pub = TestPublisher.probe()
      pub.subscribe(sub)
      pub.expectSubscription().expectCancellation()
    }

    "round-trip alternating empty and non-empty ByteString inputs exactly" in {
      val input = List(
        ByteString.empty,
        ByteString("A"),
        ByteString.empty,
        ByteString("BC"),
        ByteString.empty,
        ByteString("DEF"),
        ByteString.empty)
      val expected = input.foldLeft(ByteString.empty)(_ ++ _)

      roundTrip(initSslContext("TLSv1.2"), input) shouldEqual expected
    }

    "round-trip a fragmented large payload exactly" in {
      val payloadSize = (64 * 1024) + 1
      val payload = ByteString(Array.tabulate[Byte](payloadSize)(i => (i % 251).toByte))

      roundTrip(initSslContext("TLSv1.2"), List(payload), timeout = 30.seconds) shouldEqual payload
    }

    "complete without payload when both sides are EagerClose" in {
      roundTrip(
        initSslContext("TLSv1.2"),
        Nil,
        clientClosing = EagerClose,
        serverClosing = EagerClose,
        timeout = 10.seconds) shouldEqual ByteString.empty
    }

    "complete when plainIn finishes immediately under EagerClose/IgnoreComplete" in {
      roundTrip(
        initSslContext("TLSv1.2"),
        Nil,
        clientClosing = EagerClose,
        serverClosing = IgnoreComplete,
        timeout = 10.seconds) shouldEqual ByteString.empty
    }

    "pass data before and after NegotiateNewSession (TLS 1.2 renegotiation)" in {
      val renegotiationContext = initSslContext("TLSv1.2")
      val markNewSession = Flow[SslTlsInbound].map {
        var session: SSLSession = null

        {
          case SessionTruncated                      => SendBytes(ByteString("TRUNCATED"))
          case SessionBytes(s, b) if session eq null =>
            session = s
            SendBytes(b)
          case SessionBytes(s, b) if s != session =>
            session = s
            SendBytes(ByteString("NEWSESSION") ++ b)
          case SessionBytes(_, b) => SendBytes(b)
        }
      }

      val expected = ByteString("helloNEWSESSIONworld")
      val outputs = Await.result(
        Source(List[SslTlsOutbound](SendBytes(ByteString("hello")), NegotiateNewSession,
          SendBytes(ByteString("world"))))
          .via(loopbackFlow(renegotiationContext, TLS12Ciphers, IgnoreComplete, IgnoreComplete, markNewSession))
          .collect { case SessionBytes(_, b) => b }
          .takeWithin(10.seconds)
          .runWith(Sink.seq),
        20.seconds)

      outputs.foldLeft(ByteString.empty)(_ ++ _) shouldEqual expected
    }
  }
}
