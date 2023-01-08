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

package org.apache.pekko.persistence

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.reflect.ClassTag
import scala.util.Failure

import com.typesafe.config.Config

import org.apache.pekko
import pekko.actor.{ ExtendedActorSystem, Extension, ExtensionId }
import pekko.annotation.InternalApi
import pekko.event.Logging
import pekko.persistence.PersistencePlugin.PluginHolder

/**
 * INTERNAL API
 */
@InternalApi
private[pekko] object PersistencePlugin {
  final private[persistence] case class PluginHolder[ScalaDsl, JavaDsl](
      scaladslPlugin: ScalaDsl,
      javadslPlugin: JavaDsl)
      extends Extension
}

/**
 * INTERNAL API
 */
@InternalApi
private[pekko] trait PluginProvider[T, ScalaDsl, JavaDsl] {
  def scalaDsl(t: T): ScalaDsl
  def javaDsl(t: T): JavaDsl
}

/**
 * INTERNAL API
 */
@InternalApi
private[pekko] abstract class PersistencePlugin[ScalaDsl, JavaDsl, T: ClassTag](system: ExtendedActorSystem)(
    implicit ev: PluginProvider[T, ScalaDsl, JavaDsl]) {

  private val plugins = new AtomicReference[Map[String, ExtensionId[PluginHolder[ScalaDsl, JavaDsl]]]](Map.empty)
  private val log = Logging(system, classOf[PersistencePlugin[_, _, _]])

  @tailrec
  final protected def pluginFor(pluginId: String, readJournalPluginConfig: Config): PluginHolder[ScalaDsl, JavaDsl] = {
    val configPath = pluginId
    val extensionIdMap = plugins.get
    extensionIdMap.get(configPath) match {
      case Some(extensionId) =>
        extensionId(system)
      case None =>
        val extensionId = new ExtensionId[PluginHolder[ScalaDsl, JavaDsl]] {
          override def createExtension(system: ExtendedActorSystem): PluginHolder[ScalaDsl, JavaDsl] = {
            val provider = createPlugin(configPath, readJournalPluginConfig)
            PluginHolder(ev.scalaDsl(provider), ev.javaDsl(provider))
          }
        }
        plugins.compareAndSet(extensionIdMap, extensionIdMap.updated(configPath, extensionId))
        pluginFor(pluginId, readJournalPluginConfig)
    }
  }

  private def createPlugin(configPath: String, readJournalPluginConfig: Config): T = {
    val mergedConfig = readJournalPluginConfig.withFallback(system.settings.config)
    require(
      !isEmpty(configPath) && mergedConfig.hasPath(configPath),
      s"'reference.conf' is missing persistence plugin config path: '$configPath'")
    val pluginConfig = mergedConfig.getConfig(configPath)
    val pluginClassName = pluginConfig.getString("class")
    log.debug(s"Create plugin: $configPath $pluginClassName")
    val pluginClass = system.dynamicAccess.getClassFor[AnyRef](pluginClassName).get

    def instantiate(args: collection.immutable.Seq[(Class[_], AnyRef)]) =
      system.dynamicAccess.createInstanceFor[T](pluginClass, args)

    instantiate(
      (classOf[ExtendedActorSystem], system) :: (classOf[Config], pluginConfig) ::
      (classOf[String], configPath) :: Nil)
      .recoverWith {
        case _: NoSuchMethodException =>
          instantiate((classOf[ExtendedActorSystem], system) :: (classOf[Config], pluginConfig) :: Nil)
      }
      .recoverWith { case _: NoSuchMethodException => instantiate((classOf[ExtendedActorSystem], system) :: Nil) }
      .recoverWith { case _: NoSuchMethodException => instantiate(Nil) }
      .recoverWith {
        case ex: Exception =>
          Failure.apply(
            new IllegalArgumentException(
              "Unable to create read journal plugin instance for path " +
              s"[$configPath], class [$pluginClassName]!",
              ex))
      }
      .get
  }

  /** Check for default or missing identity. */
  private def isEmpty(text: String): Boolean = text == null || text.length == 0
}
