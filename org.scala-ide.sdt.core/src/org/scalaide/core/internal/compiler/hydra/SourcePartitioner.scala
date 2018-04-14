package org.scalaide.core.internal.compiler.hydra

object SourcePartitioner {
  sealed abstract class SourcePartitioner(val value: String)

  case object Auto extends SourcePartitioner("auto")
  case object Explicit extends SourcePartitioner("explicit")
  case object Plain extends SourcePartitioner("plain")
  case object Package extends SourcePartitioner("package")

  val values: Seq[SourcePartitioner] = Seq(Auto, Explicit, Plain, Package)
}