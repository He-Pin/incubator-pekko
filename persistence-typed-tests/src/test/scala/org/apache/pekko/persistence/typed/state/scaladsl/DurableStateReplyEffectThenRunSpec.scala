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

package org.apache.pekko.persistence.typed.state.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko
import pekko.actor.testkit.typed.scaladsl.{ LogCapturing, ScalaTestWithActorTestKit, TestProbe }
import pekko.actor.typed.{ ActorRef, Behavior }
import pekko.persistence.testkit.PersistenceTestKitDurableStateStorePlugin
import pekko.persistence.typed.PersistenceId

import org.scalatest.wordspec.AnyWordSpecLike

class DurableStateReplyEffectThenRunSpec
    extends ScalaTestWithActorTestKit(PersistenceTestKitDurableStateStorePlugin.config)
    with AnyWordSpecLike
    with LogCapturing {

  private val ids = new AtomicInteger()

  private def behavior(handler: (Int, ActorRef[String]) => ReplyEffect[Int]): Behavior[ActorRef[String]] =
    DurableStateBehavior.withEnforcedReplies[ActorRef[String], Int](
      PersistenceId.ofUniqueId(s"then-run-${ids.incrementAndGet()}"),
      emptyState = 0,
      commandHandler = handler)

  "A reply effect" must {
    "run callbacks in order after replying with the persisted state" in {
      val probe = TestProbe[String]()
      val actor = spawn(behavior { (_, replyTo) =>
        Effect.persist(1)
          .thenReply(replyTo)(state => s"reply:$state")
          .thenRun(state => replyTo ! s"callback:$state")
          .thenRun(state => replyTo ! s"another:$state")
      })
      actor ! probe.ref
      probe.expectMessage("reply:1")
      probe.expectMessage("callback:1")
      probe.expectMessage("another:1")
    }

    "decorate a handler that accepts or rejects commands" in {
      val probe = TestProbe[String]()
      val handler: (Int, ActorRef[String]) => ReplyEffect[Int] = (state, replyTo) =>
        if (state == 0)
          Effect.persist(1).thenReply(replyTo)(updatedState => s"accepted:$updatedState")
        else
          Effect.reply(replyTo)("rejected")
      val actor = spawn(behavior { (state, replyTo) =>
        handler(state, replyTo).thenRun(updatedState => replyTo ! s"observed:$updatedState")
      })

      actor ! probe.ref
      probe.expectMessage("accepted:1")
      probe.expectMessage("observed:1")
      actor ! probe.ref
      probe.expectMessage("rejected")
      probe.expectMessage("observed:1")
    }

    "retain enforced replies when appending callbacks after noReply and unstashAll" in {
      val probe = TestProbe[String]()
      val actor = spawn(behavior { (_, replyTo) =>
        Effect.noReply[Int].thenUnstashAll().thenRun(state => replyTo ! s"callback:$state")
      })
      actor ! probe.ref
      probe.expectMessage("callback:0")
    }

    "run a callback after stashing a command" in {
      val probe = TestProbe[String]()
      val actor = spawn(behavior { (_, replyTo) =>
        Effect.stash[Int]().thenRun(state => replyTo ! s"callback:$state")
      })
      actor ! probe.ref
      probe.expectMessage("callback:0")
    }
  }
}
