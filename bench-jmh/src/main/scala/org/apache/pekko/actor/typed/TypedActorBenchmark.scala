/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2014-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.actor.typed

import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

import org.apache.pekko
import pekko.actor.typed.scaladsl.AskPattern._

object TypedActorBenchmark {
  // Constants because they are used in annotations
  final val threads = 8 // update according to cpu
  final val numMessagesPerActorPair = 1000000 // messages per actor pair

  final val numActors = 512
  final val totalMessages = numMessagesPerActorPair * numActors / 2
  final val timeout = 30.seconds
}

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(1)
@Threads(1)
@Warmup(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS, batchSize = 1)
@Measurement(iterations = 10, time = 15, timeUnit = TimeUnit.SECONDS, batchSize = 1)
class TypedActorBenchmark {
  import TypedActorBenchmark._
  import TypedBenchmarkActors._

  @Param(Array("50"))
  var tpt = 0

  @Param(Array("50"))
  var batchSize = 0

  @Param(Array("pekko.dispatch.SingleConsumerOnlyUnboundedMailbox", "pekko.dispatch.UnboundedMailbox"))
  var mailbox = ""

  @Param(Array("fjp-dispatcher")) //  @Param(Array("fjp-dispatcher", "affinity-dispatcher"))
  var dispatcher = ""

  implicit var system: ActorSystem[Start] = _

  implicit val askTimeout: pekko.util.Timeout = pekko.util.Timeout(timeout)

  @Setup(Level.Trial)
  def setup(): Unit = {
    pekko.actor.BenchmarkActors.requireRightNumberOfCores(threads)
    system = ActorSystem(
      TypedBenchmarkActors.echoActorsSupervisor(numMessagesPerActorPair, numActors, dispatcher, batchSize),
      "TypedActorBenchmark",
      ConfigFactory.parseString(s"""
       pekko.actor {

         default-mailbox.mailbox-capacity = 512

         fjp-dispatcher {
           executor = "fork-join-executor"
           fork-join-executor {
             parallelism-min = $threads
             parallelism-factor = 1.0
             parallelism-max = $threads
           }
           throughput = $tpt
           mailbox-type = "$mailbox"
         }
         affinity-dispatcher {
            executor = "affinity-pool-executor"
            affinity-pool-executor {
              parallelism-min = $threads
              parallelism-factor = 1.0
              parallelism-max = $threads
              task-queue-size = 512
              idle-cpu-level = 5
              fair-work-distribution.threshold = 2048
            }
            throughput = $tpt
            mailbox-type = "$mailbox"
         }
       }
      """))
  }

  @TearDown(Level.Trial)
  def shutdown(): Unit = {
    system.terminate()
    Await.ready(system.whenTerminated, 15.seconds)
  }

  @Benchmark
  @OperationsPerInvocation(totalMessages)
  def echo(): Unit = {
    Await.result(system.ask(Start(_)), timeout)
  }

}
