/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2021-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko

import dotty.tools.dotc.ast.Trees._
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.plugins.{ PluginPhase, StandardPlugin }
import dotty.tools.backend.jvm.GenBCode
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.typer.FrontEnd

class SerialVersionRemoverPlugin extends StandardPlugin {

  val name = "serialversion-remover-plugin"
  val description = "Remove SerialVersionUid annotation from traits"

  override def init(options: List[String]): List[PluginPhase] = {
    (new SerialVersionRemoverPhase()) :: Nil
  }
}

class SerialVersionRemoverPhase extends PluginPhase {
  import tpd._

  val phaseName = "serialversion-remover"

  override val runsBefore = Set(GenBCode.name)

  override def transformTypeDef(tree: TypeDef)(implicit ctx: Context): Tree = {
    val symbol = tree.symbol
    if (tree.symbol.getAnnotation(defn.SerialVersionUIDAnnot).isDefined && tree.symbol.is(Trait)) {
      tree.symbol.removeAnnotation(defn.SerialVersionUIDAnnot)
    }

    tree
  }

}
