package org.apache.pekko.stream

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{ Flow, Sink, Source }
import org.apache.pekko.util.ByteString

import scala.concurrent.Await

object PekkoQuickstart extends App {
  private implicit val system: ActorSystem = ActorSystem()

  val s = Source
    .repeat(())
    .map(_ => ByteString('a' * 400000))
    .take(1000000)
    .flatMapPrefix(50000) { _ => Flow[ByteString] }

  println("start")
  val result = Source.empty
    .concatAllLazy(List.tabulate(30000)(_ => s): _*)
    .runWith(Sink.ignore)
  Await.result(result, scala.concurrent.duration.Duration.Inf)
  println("Done")
}
