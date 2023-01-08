/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.cluster

import org.apache.pekko.actor.{ Actor, ActorLogging }
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.cluster.ClusterEvent._

class SimpleClusterListener2 extends Actor with ActorLogging {

  // #join
  val cluster = Cluster(context.system)
  // #join

  // subscribe to cluster changes, re-subscribe when restart
  override def preStart(): Unit = {
    // #join
    cluster.join(cluster.selfAddress)
    // #join

    // #subscribe
    cluster.subscribe(self, classOf[MemberEvent], classOf[UnreachableMember])
    // #subscribe

    // #register-on-memberup
    cluster.registerOnMemberUp {
      cluster.subscribe(self, classOf[MemberEvent], classOf[UnreachableMember])
    }
    // #register-on-memberup
  }
  override def postStop(): Unit = cluster.unsubscribe(self)

  def receive = {
    case state: CurrentClusterState =>
      log.info("Current members: {}", state.members.mkString(", "))
    case MemberUp(member) =>
      log.info("Member is Up: {}", member.address)
    case UnreachableMember(member) =>
      log.info("Member detected as unreachable: {}", member)
    case MemberRemoved(member, previousStatus) =>
      log.info("Member is Removed: {} after {}", member.address, previousStatus)
    case _: MemberEvent => // ignore
  }
}
