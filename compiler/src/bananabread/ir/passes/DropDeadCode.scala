package bananabread
package ir.passes
package dropDeadCode

import ir.typeless._


// TODO This needs to take scoping into account. As is, this will keep a
// top-level function that is not called when a scoped function with the same
// name is being called. Among other things, this means recursive functions are
// always kept.
//
// TODO This is currently keeping lambdas that are only called in other lambdas
// that are dropped. These are also unnecessary lambdas that need to be
// dropped.
def dropUnnecessaryLambdas(nodes: List[Ir]): List[Ir] =
  val called = nodes.foldLeft(Set.empty[String]) { (acc, node) =>
    acc ++ listCalledLambdas(node)
  }

  val defined = nodes.foldLeft(Set.empty[String]) { (acc, node) =>
    acc ++ listDefinedLambdas(node)
  }

  val unnecessary = defined.diff(called)

  nodes.foldLeft(List.empty[Ir]) {
    case (nodes, Def(name, _: Lambda, _)) if unnecessary.contains(name.lexeme) =>
      nodes
    case (nodes, node) =>
      nodes :+ node
  }

def listCalledLambdas(binding: Binding): Set[String] =
  listCalledLambdas(binding.value)
def listCalledLambdas(node: Ir): Set[String] =
  node match
    case _: Num    => Set.empty
    case _: Str    => Set.empty
    case _: Bool   => Set.empty
    case _: Symbol => Set.empty
    case Id(name)  =>
      Set(name.lexeme)
    case App(Id(name), args, _) =>
      Set(name.lexeme) ++ args.map(listCalledLambdas).flatten
    case App(lam, args, _) =>
      listCalledLambdas(lam) ++ args.map(listCalledLambdas).flatten
    case Def(_, body, _) =>
      listCalledLambdas(body)
    case Lambda(_, body, _, _) =>
      listCalledLambdas(body)
    case Begin(ins, _) =>
      ins.map(listCalledLambdas).flatten.toSet
    case Cond(cond, pass, fail, _) =>
      List(cond, pass, fail).map(listCalledLambdas).flatten.toSet
    case Let(bindings, body, _) =>
      listCalledLambdas(body) ++ bindings.map(listCalledLambdas).flatten


def listDefinedLambdas(node: Ir): Set[String] =
  node match
    case Def(name, _, _) => Set(name.lexeme)
    case _               => Set.empty
