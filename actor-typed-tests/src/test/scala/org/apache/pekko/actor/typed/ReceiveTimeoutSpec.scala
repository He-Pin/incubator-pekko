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

package org.apache.pekko.actor.typed

import scala.concurrent.duration._

import org.apache.pekko
import pekko.actor.NotInfluenceReceiveTimeout
import pekko.actor.testkit.typed.scaladsl.LogCapturing
import pekko.actor.testkit.typed.scaladsl.ManualTime
import pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import pekko.actor.testkit.typed.scaladsl.TestProbe
import pekko.actor.typed.scaladsl.Behaviors
import pekko.testkit.CallingThreadDispatcher

import org.scalatest.wordspec.AnyWordSpecLike

object ReceiveTimeoutSpec {
  sealed trait Response
  case object Heartbeat extends Response with NotInfluenceReceiveTimeout
  case object Activity extends Response

  sealed trait Command
  case object Start extends Command
  case object Timeout extends Command
  case object Adapted extends Command
  case object MarkedAdapted extends Command with NotInfluenceReceiveTimeout
}

class ReceiveTimeoutSpec extends ScalaTestWithActorTestKit(ManualTime.config) with AnyWordSpecLike with LogCapturing {
  import ReceiveTimeoutSpec._

  private val manualTime = ManualTime()

  "A typed actor with receive timeout" must {
    for {
      mode <- Seq("messageAdapter", "Scala ask", "Java ask")
      response <- Seq(Heartbeat, Activity)
    } {
      s"${if (response == Heartbeat) "preserve" else "reset"} the timeout for $response through $mode" in {
        val replies = TestProbe[ActorRef[Response]]()
        val received = TestProbe[Command]()
        // Invert the marker on the adapted message to prove that the original response controls the timeout.
        val adapted = if (response == Heartbeat) Adapted else MarkedAdapted
        val ref = spawn(
          Behaviors.setup[Command] { context =>
            mode match {
              case "messageAdapter" => replies.ref ! context.messageAdapter[Response](_ => adapted)
              case "Scala ask"      => context.ask(replies.ref, identity[ActorRef[Response]])(_ => adapted)
              case "Java ask"       =>
                context.asJava.ask[ActorRef[Response], Response](
                  classOf[Response], replies.ref, java.time.Duration.ofSeconds(3), ref => ref, (_, _) => adapted)
              case other => throw new IllegalArgumentException(other)
            }
            Behaviors.receiveMessage {
              case Start =>
                context.setReceiveTimeout(1.second, Timeout)
                Behaviors.same
              case Timeout =>
                received.ref ! Timeout
                Behaviors.stopped
              case message =>
                received.ref ! message
                Behaviors.same
            }
          },
          DispatcherSelector.fromConfig(CallingThreadDispatcher.Id))

        try {
          // Synchronous dispatch finishes timeout scheduling before the clock advances.
          ref ! Start
          manualTime.timePasses(600.millis)
          replies.receiveMessage() ! response
          received.expectMessage(adapted)
          manualTime.timePasses(400.millis)
          if (response == Activity) {
            received.expectNoMessage()
            manualTime.timePasses(600.millis)
          }
          received.expectMessage(Timeout)
        } finally testKit.stop(ref)
      }
    }
  }
}
