package sourdough
package runtime

import ir.Typeless => tl
import ir.Typeless.Ir
import opcode.Opcode
import value.Value

import scala.util.Random


val rand = Random.alphanumeric


case class Instruction(op: Opcode, args: Value*):
  def toList = List(this)

  override def toString = op match
    case opcode.Label => s"${args(0)}:"
    case _ => s"  $op ${args.mkString(", ")}"

def inst(op: Opcode, args: Value*) =
  Instruction(op, args:_*).toList

def lift(nodes: List[Ir]): List[Instruction] =
  nodes.flatMap(lift)

def lift(node: Ir): List[Instruction] = node match
  case _: tl.Num => push(node, ty.I32)
  case _: tl.Str => ???
  case tl.Lambda(params, body, _) => lambda(params, body)
  case tl.Id(ast.Id(label, _)) => load(label)
  case tl.App(lambda, args, _) => call(lambda, args)
  case tl.Cond(cnd, pas, fal, _) => cond(cnd, pas, fal)
  case tl.Let(bindings, body, _) => let(bindings, body)


def push(node: Ir, typ: ty.Type) = typ match
  case ty.I32 => inst(opcode.PushI32, value.lift(node))
  case ty.Str => ???
  case _: ty.Var => ???
  case _: ty.Lambda => ???

def name(name: String): value.Id =
  value.Id(name)

def unique(name: String): value.Id =
  value.Id(s"$name.${rand.take(16).mkString}")

def call(lambda: Ir, args: List[Ir]) = lambda match
  case _: tl.Id =>
    args.flatMap(lift) ++
    inst(opcode.Call, value.lift(lambda))
  case lambda: tl.Lambda =>
    inst(opcode.Label, name(lambda.ptr)) ++
    lift(lambda) ++
    store(lambda.ptr) ++
    args.flatMap(lift) ++
    inst(opcode.Call, name(lambda.ptr))
  case _ =>
    /* bad call */
    ???

def load(label: String) =
  inst(opcode.LoadI32, name(label))

def store(label: String) =
  inst(opcode.StoreI32, name(label))

def cond(cnd: Ir, pas: Ir, fal: Ir) =
  val lelse = unique("else")
  val ldone = unique("done")

  lift(cnd) ++
  inst(opcode.Jz, lelse) ++
  lift(pas) ++
  inst(opcode.Jmp, ldone) ++
  inst(opcode.Label, lelse) ++
  lift(fal) ++
  inst(opcode.Label, ldone)

def let(bindings: List[tl.Binding], body: Ir) =
  bindings.flatMap { case tl.Binding(ast.Id(label, _), value, _) =>
    lift(value) ++
    store(label)
  } ++ lift(body)

def lambda(params: List[tl.Id], body: Ir) =
  params.flatMap { case tl.Id(ast.Id(label, _)) =>
    store(label)
  } ++ lift(body)
