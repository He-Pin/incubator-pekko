/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package docs.stream.operators.sink

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.stream.scaladsl.{ Keep, Sink, Source }

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success }

object WatchTermination {

  def watchTerminationExample(): Unit = {
    implicit val system: ActorSystem = ???
    implicit val ec: ExecutionContext = ???

    // #watchTermination
    // Wrap Sink.ignore with termination watching
    val terminationWatchedSink =
      Sink.watchTermination(Sink.ignore) { (mat, terminationFuture) =>
        terminationFuture.onComplete {
          case Success(_)         => println("Stream completed successfully")
          case Failure(exception) => println(s"Stream failed: ${exception.getMessage}")
        }
        mat
      }

    Source(1 to 5).runWith(terminationWatchedSink)
    /*
    Prints:
    Stream completed successfully
     */

    // Combine the original materialized value with the termination signal
    val sinkWithBoth =
      Sink.watchTermination(Sink.seq[Int])(Keep.both)

    val (seqFuture, terminationFuture) =
      Source(1 to 3).runWith(sinkWithBoth)

    seqFuture.onComplete {
      case Success(seq) => println(s"Collected: $seq")
      case Failure(ex)  => println(s"Failed to collect: ${ex.getMessage}")
    }

    terminationFuture.onComplete {
      case Success(_)  => println("Stream terminated normally")
      case Failure(ex) => println(s"Stream terminated with error: ${ex.getMessage}")
    }
    /*
    Prints:
    Collected: Vector(1, 2, 3)
    Stream terminated normally
     */
    // #watchTermination
  }
}
