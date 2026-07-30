/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.stream.scaladsl

import scala.concurrent.Future
import scala.util.control.NoStackTrace

import org.apache.pekko
import pekko.Done
import pekko.stream._
import pekko.stream.testkit.StreamSpec
import pekko.stream.testkit.scaladsl.TestSource

class SinkWatchTerminationSpec extends StreamSpec {

  "Sink.watchTermination" must {

    "complete future with Done when stream completes normally" in {
      val (_, future) =
        Source(1 to 4).toMat(Sink.watchTermination(Sink.ignore)(Keep.both))(Keep.right).run()
      future.futureValue should ===(Done)
    }

    "pass through the original sink's materialized value" in {
      val (mat, future) =
        Source(1 to 4).toMat(Sink.watchTermination(Sink.head[Int])(Keep.both))(Keep.right).run()
      mat.futureValue should ===(1)
      future.futureValue should ===(Done)
    }

    "complete future with Done when stream is cancelled from downstream" in {
      val sink = Sink.watchTermination(Sink.head[Int])(Keep.right)
      val future = Source(1 to 100).runWith(sink)
      // Sink.head cancels after receiving the first element
      future.futureValue should ===(Done)
    }

    "fail future when upstream fails" in {
      val ex = new RuntimeException("Stream failed.") with NoStackTrace
      val (sourceProbe, (_, future)) =
        TestSource[Int]().toMat(Sink.watchTermination(Sink.ignore)(Keep.both))(Keep.both).run()
      sourceProbe.sendNext(1)
      sourceProbe.sendError(ex)
      whenReady(future.failed) { _ shouldBe ex }
    }

    "complete future with Done for an empty stream" in {
      val future = Source.empty[Int].runWith(Sink.watchTermination(Sink.ignore)(Keep.right))
      future.futureValue should ===(Done)
    }

    "fail future when stream is abruptly terminated" in {
      val mat = Materializer(system)
      val (_, (_, future)) =
        TestSource[Int]().toMat(Sink.watchTermination(Sink.ignore)(Keep.both))(Keep.both).run()(mat)
      mat.shutdown()
      future.failed.futureValue shouldBe an[AbruptTerminationException]
    }

    "combine materialized values using matF" in {
      implicit val ec: scala.concurrent.ExecutionContext = system.dispatcher
      val sink: Sink[Int, Future[String]] =
        Sink.watchTermination(Sink.head[Int]) { (headFuture, terminationFuture) =>
          for {
            head <- headFuture
            _ <- terminationFuture
          } yield s"head=$head"
        }
      val result = Source(1 to 3).runWith(sink)
      result.futureValue should ===("head=1")
    }

    "work with Sink.seq" in {
      val (seqFuture, terminationFuture) =
        Source(1 to 5).toMat(Sink.watchTermination(Sink.seq[Int])(Keep.both))(Keep.right).run()
      seqFuture.futureValue should ===(Seq(1, 2, 3, 4, 5))
      terminationFuture.futureValue should ===(Done)
    }

    "work with Sink.fold" in {
      val (foldFuture, terminationFuture) =
        Source(1 to 5).toMat(Sink.watchTermination(Sink.fold[Int, Int](0)(_ + _))(Keep.both))(Keep.right).run()
      foldFuture.futureValue should ===(15)
      terminationFuture.futureValue should ===(Done)
    }

    "work with Sink.foreach" in {
      @volatile var sum = 0
      val (doneFuture, terminationFuture) =
        Source(1 to 5)
          .toMat(Sink.watchTermination(Sink.foreach[Int](n => sum += n))(Keep.both))(Keep.right)
          .run()
      doneFuture.futureValue should ===(Done)
      terminationFuture.futureValue should ===(Done)
      sum should ===(15)
    }

    "fail future when the wrapped sink fails" in {
      val ex = new RuntimeException("Sink failed.") with NoStackTrace
      val failingSink: Sink[Int, Future[Done]] = Sink.foreach[Int] { n =>
        if (n == 3) throw ex
      }
      val (doneFuture, terminationFuture) =
        Source(1 to 5).toMat(Sink.watchTermination(failingSink)(Keep.both))(Keep.right).run()
      doneFuture.failed.futureValue shouldBe ex
      terminationFuture.failed.futureValue shouldBe ex
    }

    "work with single element" in {
      val future = Source.single(42).runWith(Sink.watchTermination(Sink.ignore)(Keep.right))
      future.futureValue should ===(Done)
    }

    "produce the same result as Flow.watchTermination when chained" in {
      // Sink.watchTermination(sink)(matF) should behave the same as
      // Flow[T].watchTermination(Keep.right).toMat(sink)((f, m) => matF(m, f))
      val (seqResult1, termResult1) =
        Source(1 to 3).toMat(Sink.watchTermination(Sink.seq[Int])(Keep.both))(Keep.right).run()

      val (termResult2, seqResult2) =
        Source(1 to 3).watchTermination(Keep.right).toMat(Sink.seq[Int])(Keep.both).run()

      seqResult1.futureValue should ===(seqResult2.futureValue)
      termResult1.futureValue should ===(termResult2.futureValue)
    }
  }
}
