package bananabread
package typechecker
package error

import ty._
import ir.typeless.Ir
import parsing.ast


sealed trait InferenceErr
case class LookupErr(label: String, ir: Ir) extends InferenceErr
case class TypeMismatchErr(expected: Type, got: Type, node: Ir) extends InferenceErr
case class UnknowTypeErr(tag: ast.Ty) extends InferenceErr