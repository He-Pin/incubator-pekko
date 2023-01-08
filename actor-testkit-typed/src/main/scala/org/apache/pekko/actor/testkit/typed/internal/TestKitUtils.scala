/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.actor.testkit.typed.internal

import scala.concurrent.{ Await, TimeoutException }
import scala.concurrent.duration.Duration
import scala.util.control.Exception.Catcher
import scala.util.control.NonFatal

import org.apache.pekko
import pekko.actor.typed.{ ActorRef, ActorSystem, Behavior, Props }
import pekko.actor.typed.scaladsl.ActorContext
import pekko.actor.typed.scaladsl.Behaviors
import pekko.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi
private[pekko] object ActorTestKitGuardian {
  sealed trait TestKitCommand
  final case class SpawnActor[T](name: String, behavior: Behavior[T], replyTo: ActorRef[ActorRef[T]], props: Props)
      extends TestKitCommand
  final case class SpawnActorAnonymous[T](behavior: Behavior[T], replyTo: ActorRef[ActorRef[T]], props: Props)
      extends TestKitCommand
  final case class StopActor[T](ref: ActorRef[T], replyTo: ActorRef[Ack.type]) extends TestKitCommand
  final case class ActorStopped[T](replyTo: ActorRef[Ack.type]) extends TestKitCommand

  case object Ack

  val testKitGuardian: Behavior[TestKitCommand] = Behaviors.receive[TestKitCommand] {
    case (context, SpawnActor(name, behavior, reply, props)) =>
      try {
        reply ! context.spawn(behavior, name, props)
        Behaviors.same
      } catch handleSpawnException(context, reply, props)
    case (context, SpawnActorAnonymous(behavior, reply, props)) =>
      try {
        reply ! context.spawnAnonymous(behavior, props)
        Behaviors.same
      } catch handleSpawnException(context, reply, props)
    case (context, StopActor(ref, reply)) =>
      context.watchWith(ref, ActorStopped(reply))
      context.stop(ref)
      Behaviors.same
    case (_, ActorStopped(reply)) =>
      reply ! Ack
      Behaviors.same
  }

  private def handleSpawnException[T](
      context: ActorContext[ActorTestKitGuardian.TestKitCommand],
      reply: ActorRef[ActorRef[T]],
      props: Props): Catcher[Behavior[TestKitCommand]] = {
    case NonFatal(e) =>
      context.log.error(s"Spawn failed, props [$props]", e)
      reply ! context.spawnAnonymous(Behaviors.stopped)
      Behaviors.same
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[pekko] object TestKitUtils {

  // common internal utility impls for Java and Scala
  private val TestKitRegex = """org\.apache\.pekko\.testkit\.typed\.(?:javadsl|scaladsl)\.ActorTestKit(?:\$.*)?""".r

  def testNameFromCallStack(classToStartFrom: Class[_]): String =
    pekko.testkit.TestKitUtils.testNameFromCallStack(classToStartFrom, TestKitRegex)

  /**
   * Sanitize the `name` to be used as valid actor system name by
   * replacing invalid characters. `name` may for example be a fully qualified
   * class name and then the short class name will be used.
   */
  def scrubActorSystemName(name: String): String =
    pekko.testkit.TestKitUtils.scrubActorSystemName(name)

  def shutdown(system: ActorSystem[_], timeout: Duration, throwIfShutdownTimesOut: Boolean): Unit = {
    system.terminate()
    try Await.ready(system.whenTerminated, timeout)
    catch {
      case _: TimeoutException =>
        val message = "Failed to stop [%s] within [%s] \n%s".format(system.name, timeout, system.printTree)
        if (throwIfShutdownTimesOut) throw new RuntimeException(message)
        else println(message)
    }
  }
}
