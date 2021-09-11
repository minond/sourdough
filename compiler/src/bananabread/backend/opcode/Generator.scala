package bananabread
package backend.opcode

import error._

import ir.typeless
import ir.typeless.Ir

import parsing.location.Location
import parsing.opcode.Tree => OpcodeTree
import parsing.opcode.Expr => OpcodeExpr
import parsing.opcode.{Instruction => InstructionExpr, Label => LabelExpr}

import runtime.value
import runtime.value.Id
import runtime.register._
import runtime.instruction
import runtime.instruction._
import runtime.instruction.{Value, Label, Instruction}
import runtime.instruction.{Bool, Type, I32, Str, Symbol}

import utils.{safeToInt, squished}

import scala.util.Random
import scala.collection.mutable.{Map, Queue}
import scala.collection.immutable.Map => ImMap


case class Grouped(section: String, data: Instruction | Label)
type Output = List[Grouped | Value | Label]
type Result = Either[GeneratorErr, Output]


def compile(nodes: List[Ir]): Either[GeneratorErr, List[Code]] =
  generate(nodes).map(_.deduped.labeled.framed.sectioned)

def generate(nodes: List[Ir]): Result =
  generate(backend.opcode.Scope.empty, nodes)
def generate(scope: Scope, nodes: List[Ir]): Result =
  nodes.map { node => generate(scope, node) }.squished
       .map { inst => inst.flatten }
def generate(scope: Scope, node: Ir): Result = node match
  case num: typeless.Num    => generatePush(scope, num, I32)
  case str: typeless.Str    => generatePush(scope, str, Str)
  case sym: typeless.Symbol => generatePush(scope, sym, Symbol)
  case bool: typeless.Bool  => generatePush(scope, bool, Bool)
  case id: typeless.Id      => generateLoad(scope, id)
  case lam: typeless.Lambda => generateAnnonLambda(scope, lam)
  case typeless.App(lambda, args, _)      => generateCall(scope, lambda, args)
  case typeless.Cond(cond, pass, fail, _) => generateCond(scope, cond, pass, fail)
  case typeless.Let(bindings, body, _)    => generateLet(scope, bindings, body)
  case typeless.Begin(irs,_ )             => generateBegin(scope, irs)
  case typeless.Def(name, value, _)       => generateDef(scope, name.lexeme, value)

def generatePush(scope: Scope, node: Ir, ty: Type): Result = (ty, node) match
  case (I32, num: typeless.Num) =>
    num.expr.lexeme.safeToInt match
      case Left(_)  => Left(BadPushErr(ty, node))
      case Right(i) => Right(group(scope, Push(I32, value.I32(i))))
  case (Bool, _: typeless.True) =>
    Right(group(scope, Push(Bool, value.True)))
  case (Bool, _: typeless.False) =>
    Right(group(scope, Push(Bool, value.False)))
  case (Str, str: typeless.Str) =>
    Right(Value(Str, str.ptr, value.Str(str.expr.lexeme)) +:
          group(scope, Push(Const, value.Id(str.ptr))))
  case (Symbol, sym: typeless.Symbol) =>
    Right(Value(Symbol, sym.ptr, value.Symbol(sym.expr.lexeme)) +:
          group(scope, Push(Const, value.Id(sym.ptr))))
  case _ =>
    Left(BadPushErr(ty, node))

def generateLoad(scope: Scope, id: typeless.Id): Result = scope.get(id) match
  case Some(_) => Right(group(scope, Load(I32, scope.qualified(id))))
  case None    => Left(UndeclaredIdentifierErr(id))

def generateAnnonLambda(scope: Scope, lambda: typeless.Lambda): Result =
  scope.forked(lambda.ptr) { subscope =>
    val exposure =
      if scope.isToplevel
      then List.empty
      else group(scope, Push(Scope, value.Id(lambda.ptr)))

    generateLambda(subscope, lambda.params, lambda.body)
      .map(Label(lambda.ptr) +: _)
      .map(_ ++ exposure)
      .map(_ :+ Value(Ref, lambda.ptr, Id(lambda.ptr)))
  }

def generateCall(scope: Scope, lambda: Ir, args: List[Ir]): Result = lambda match
  case id: typeless.Id if scope.contains(id) =>
    scope.get(id) match
      case Some(id: typeless.Id)      => generateCallId(scope, args, id)
      case Some(app: typeless.App)    => generateCallApp(scope, args, app)
      case Some(lam: typeless.Lambda) => generateCallId(scope, args, id)
      case Some(_)                    => Left(BadCallErr(lambda))
      case None                       => Left(UndeclaredIdentifierErr(id))

  case typeless.Id(parsing.ast.Id("opcode", _)) => args match
    case typeless.Str(node @ parsing.ast.Str(str, loc)) :: Nil =>
      parsing.opcode.parse("<opcode>", str) match
        case Right(tr) => generateOpcode(scope, tr, str, loc)
        case Left(err) => Left(OpcodeSyntaxErr(err, node))
    case _             => Left(BadCallErr(lambda))

  case lam: typeless.Lambda =>
    for
      call <- generateCallLambda(scope, args, lam)
      func <- generate(scope, lam)
    yield
      call ++ func

  case id: typeless.Id   => generateCallId(scope, args, id)
  case app: typeless.App => generateCallApp(scope, args, app)
  case _: typeless.Let   => generateCallResultOf(scope, args, lambda)
  case _: typeless.Cond  => generateCallResultOf(scope, args, lambda)
  case _: typeless.Begin => generateCallResultOf(scope, args, lambda)

  case _ => Left(BadCallErr(lambda))

def generateOpcode(scope: Scope, tree: OpcodeTree, source: String, loc: Location): Result =
  tree.nodes.map(generateOpcode(scope, _, source, loc)).squished.map(_.flatten)
def generateOpcode(scope: Scope, expr: OpcodeExpr, source: String, loc: Location): Result = expr match
  case InstructionExpr("add",     Some("I32"),  Nil,         _) => Right(group(scope, Add(I32)))
  case InstructionExpr("sub",     Some("I32"),  Nil,         _) => Right(group(scope, Sub(I32)))
  case InstructionExpr("push",    Some("I32"),  List(str),   _) => withI32(expr, str) { i => group(scope, Push(I32, value.I32(i))) }
  case InstructionExpr("push",    Some("Str"),  List(label), _) => Right(group(scope, Push(Str, value.Id(scope.qualified(label)))))
  case InstructionExpr("push",    Some("Ref"),  List(label), _) => Right(group(scope, Push(Ref, value.Id(scope.qualified(label)))))
  case InstructionExpr("load",    Some("I32"),  List(label), _) => Right(group(scope, Load(I32, scope.qualified(label))))
  case InstructionExpr("load",    Some("Bool"), List(label), _) => Right(group(scope, Load(Bool, scope.qualified(label))))
  case InstructionExpr("load",    Some("Str"),  List(label), _) => Right(group(scope, Load(Str, scope.qualified(label))))
  case InstructionExpr("load",    Some("Ref"),  List(label), _) => Right(group(scope, Load(Ref, scope.qualified(label))))
  case InstructionExpr("store",   Some("I32"),  List(label), _) => Right(group(scope, Store(I32, scope.qualified(label))))
  case InstructionExpr("store",   Some("Str"),  List(label), _) => Right(group(scope, Store(Str, scope.qualified(label))))
  case InstructionExpr("store",   Some("Ref"),  List(label), _) => Right(group(scope, Store(Ref, scope.qualified(label))))
  case InstructionExpr("jz",      None,         List(label), _) => Right(group(scope, Jz(scope.qualified(label))))
  case InstructionExpr("jmp",     None,         List(label), _) => Right(group(scope, Jmp(label)))
  case InstructionExpr("call",    None,         List(label), _) => Right(group(scope, Call(scope.qualified(label))))
  case InstructionExpr("mov",     Some("Pc"),   Nil,         _) => Right(group(scope, Mov(Pc, None)))
  case InstructionExpr("mov",     Some("Lr"),   Nil,         _) => Right(group(scope, Mov(Lr, None)))
  case InstructionExpr("mov",     Some("Jm"),   Nil,         _) => Right(group(scope, Mov(Jm, None)))
  case InstructionExpr("mov",     Some("Rt"),   Nil,         _) => Right(group(scope, Mov(Rt, None)))
  case InstructionExpr("mov",     Some("Pc"),   List(str),   _) => withI32(expr, str) { i => group(scope, Mov(Pc, Some(value.I32(i)))) }
  case InstructionExpr("mov",     Some("Lr"),   List(str),   _) => withI32(expr, str) { i => group(scope, Mov(Lr, Some(value.I32(i)))) }
  case InstructionExpr("mov",     Some("Jm"),   List(str),   _) => withI32(expr, str) { i => group(scope, Mov(Jm, Some(value.I32(i)))) }
  case InstructionExpr("mov",     Some("Rt"),   List(str),   _) => withI32(expr, str) { i => group(scope, Mov(Rt, Some(value.I32(i)))) } // XXX May not always be an I32
  case InstructionExpr("stw",     None,         List("Rt"),  _) => Right(group(scope, Stw(Rt)))
  case InstructionExpr("ldw",     None,         List("Rt"),  _) => Right(group(scope, Ldw(Rt)))
  case InstructionExpr("concat",  None,         Nil,         _) => Right(group(scope, Concat))
  case InstructionExpr("println", None,         Nil,         _) => Right(group(scope, Println))
  case InstructionExpr("halt",    None,         Nil,         _) => Right(group(scope, Halt))
  case InstructionExpr("call0",   None,         Nil,         _) => Right(group(scope, Call0))
  case InstructionExpr("ret",     None,         Nil,         _) => Right(group(scope, Ret))
  case InstructionExpr("swap",    None,         Nil,         _) => Right(group(scope, Swap))
  case LabelExpr(label,                                      _) => Right(group(scope, Label(label)))
  case _                                                        => Left(UnknownUserOpcodeErr(expr, source, loc))

def generateCallId(scope: Scope, args: List[Ir], id: typeless.Id): Result =
  scope.qualified2(id) match
    case Some(name) => generateCallWithArgs(scope, args, Call(name))
    case None => Left(LookupErr(id))

def generateCallLambda(scope: Scope, args: List[Ir], lambda: typeless.Lambda): Result =
  generateCallWithArgs(scope, args, Call(lambda.ptr))

def generateCallApp(scope: Scope, args: List[Ir], app: typeless.App): Result =
  for
    call1 <- generateCall(scope, app.lambda, app.args)
    mov    = group(scope, Mov(Jm, None))
    call2 <- generateCallWithArgs(scope, args, Call0)
    codes  = call1 ++ mov ++ call2
  yield
    codes

def generateCallResultOf(scope: Scope, args: List[Ir], node: typeless.Ir): Result =
  for
    body <- generate(scope, node)
    mov   = group(scope, Mov(Jm, None))
    call <- generateCallWithArgs(scope, args, Call0)
    codes = body ++ mov ++ call
  yield
    codes

def generateCallWithArgs(scope: Scope, args: List[Ir], call: Instruction): Result =
  generateCallArgsLoad(scope, args).map { header =>
    header ++ group(scope, call)
  }

def generateCallArgsLoad(scope: Scope, args: List[Ir]): Result =
  args.map {
    case lam: typeless.Lambda =>
      // TODO Fully qualify all anonymous functions
      generate(scope, lam).map { lamop =>
        scope.define(lam.ptr, lam)
        group(scope, Load(I32, lam.ptr)) ++ lamop
      }
    case arg =>
      generate(scope, arg)
  }.squished.map(_.flatten)

def generateCond(scope: Scope, cond: Ir, pass: Ir, fail: Ir): Result =
  val condString = uniqueString(scope, "cond")
  val thenString = uniqueString(scope, "then")
  val elseString = uniqueString(scope, "else")
  val doneString = uniqueString(scope, "done")

  val thenLabel = group(scope, Label(thenString))
  val elseLabel = group(scope, Label(elseString))
  val doneLabel = group(scope, Label(doneString))

  val elseJump = group(scope, Jz(elseString))
  val doneJump = group(scope, Jmp(doneString))

  for
    condCode <- generate(scope, cond)
    passCode <- generate(scope, pass)
    passRefs  = generatePushReturnedRef(scope, pass)
    failCode <- generate(scope, fail)
    failRefs  = generatePushReturnedRef(scope, fail)
  yield
    condCode  ++ elseJump ++                          // if
    thenLabel ++ passCode ++ passRefs ++ doneJump ++  // then
    elseLabel ++ failCode ++ failRefs ++              // else
    doneLabel                                         // rest

/** TODO Let expressions are only returning a reference to an annonymous lambda
  * in their bodies because `generateAnnonLambda` will ensure a reference to
  * all non-top-level lambdas are pushed on the stack. But this may not always
  * work. What we should do instead is use `generatePushReturnedRef` like other
  * expression kinds do.
  */
def generateLet(scope: Scope, bindings: List[typeless.Binding], body: Ir): Result =
  scope.unique { subscope =>
    val header = bindings.map { case typeless.Binding(label, value, _) =>
      subscope.define(label, value)
      for
        valueCode <- generate(subscope, value)
        storeCode <- generateStore(subscope, label.lexeme, value)
      yield
        value match
          case lam: typeless.Lambda =>
            valueCode ++ group(subscope, Push(Ref, runtime.value.Id(lam.ptr))) ++ storeCode
          case _ =>
            valueCode ++ storeCode
    }

    for
      lets <- header.squished
      code <- generate(subscope, body)
    yield
      regroup(subscope, scope, lets.flatten ++ code)
  }

def generateDef(scope: Scope, name: String, value: Ir): Result = value match
  case lam: typeless.Lambda =>
    scope.define(name, lam)
    scope.scoped(name) { subscope =>
      generateLambda(subscope, lam.params, lam.body)
        .map(Label(lam.ptr) +: _ :+ Value(Ref, scope.qualified(name), Id(scope.qualified(name))))
    }

  case _ =>
    scope.define(name, value)
    generate(scope, value)
      .map(_ ++ group(scope, Store(I32, scope.qualified(name))))

def generateLambda(scope: Scope, params: List[typeless.Id], body: Ir): Result =
  val init = group(scope,
    FrameInit(params.size),
  )

  val storeArgs = params.reverse.flatMap { case param @ typeless.Id(label) =>
    scope.define(param, param)
    group(scope, Swap, Store(I32, scope.qualified(label)))
  }

  val callerInfo = group(scope,
    Stw(Ebp), // Track old ebp.
    Stw(Esp), // Load esp.
    Ldw(Ebp), // And store it in ebp.
              // TODO do this std+ldw in a single mov inst.
  )

  val ret = group(scope,
    Ldw(Rt),  // Still using stack conventions, store return value in
              // rt register while we do some cleanup.
    Stw(Ebp), // Load ebp.
    Ldw(Esp), // And store it in esp.
              // TODO do this std+ldw in a single mov inst.
    Ldw(Ebp), // Restore the previous ebp value back into that register.
    Stw(Rt),  // Reload the return value on the stack.
    Swap,     // Swap return value and return address which are now at
    Ret)      // top of the stack and return.

  generate(scope, body).map(init ++ storeArgs ++ callerInfo ++ _ ++ ret)

def generateStore(scope: Scope, label: String, value: Ir): Result = value match
  case _: typeless.Lambda => Right(group(scope, Store(Ref, scope.qualified(label))))
  case _: typeless.Num    => Right(group(scope, Store(I32, scope.qualified(label))))
  case _: typeless.Str    => Right(group(scope, Store(Str, scope.qualified(label))))
  case _: typeless.Bool   => Right(group(scope, Store(Bool, scope.qualified(label))))
  case _: typeless.Begin  => Right(group(scope, Store(I32, scope.qualified(label)))) /* XXX May not be an I32 */
  case _: typeless.Let    => Right(group(scope, Store(I32, scope.qualified(label)))) /* XXX May not be an I32 */
  case _: typeless.Cond   => Right(group(scope, Store(I32, scope.qualified(label)))) /* XXX May not be an I32 */
  case _: typeless.App    => Right(group(scope, Store(I32, scope.qualified(label)))) /* XXX May not be an I32 */
  case _: typeless.Symbol => Right(group(scope, Store(Symbol, scope.qualified(label))))
  case _: typeless.Def    => Left(CannotStoreDefErr(value))
  case id: typeless.Id => scope.get(id) match
    case Some(v: typeless.Id) if v.expr.lexeme == id.expr.lexeme =>
      Right(group(scope, Store(I32, scope.qualified(label)))) /* XXX May not be an I32 */
    case Some(v) => generateStore(scope, label, v)
    case None    => Left(UndeclaredIdentifierErr(id))

def generateBegin(scope: Scope, irs: List[Ir]): Result =
  for
    codes <- irs.map { ir => generate(scope, ir) }.squished
    lref   = generatePushReturnedRef(scope, irs.last)
  yield
    codes.flatten ++ lref

/** Generates code for any ir node that ought to be something we return back or
  * keep around. Mostly for references of lambdas.
  */
def generatePushReturnedRef(scope: Scope, ir: Ir): Output =
  ir match
    case lam: typeless.Lambda => group(scope, Push(Scope, value.Id(lam.ptr)))
    case _ => List.empty

def group(scope: Scope, insts: (Instruction | Label)*): Output =
  group(scope.module, insts:_*)
def group(section: String, insts: (Instruction | Label)*): Output =
  insts.toList.map { inst =>
    Grouped(section, inst)
  }

/** TODO regroup is a total hack needed because a "scope" is used for both
  * function/variable scoping _and_ instruction grouping. Fix this by tracking
  * blocks separate to scopes and updating Grouped to use this instead.
  *
  * The issue becomes apparent we need to create a new scope and have it stay
  * grouped with other instructions in the same block. We can't do this because
  * creating a new scope puts the instructions in another section in the code.
  * An example of this are `let` expressions which use a subscope but need to be
  * grouped in with the other instructions in the block it was defined in.
  */
def regroup(prevScope: Scope, newScope: Scope, output: Output): Output =
  output.map {
    case Grouped(prevScope.module, data) => Grouped(newScope.module, data)
    case out                             => out
  }

def uniqueString(scope: Scope, label: String): String =
  s"$label-${Random.alphanumeric.take(4).mkString}"

def withI32(expr: OpcodeExpr, str: String)(f: Int => Output): Result =
  str.safeToInt match
    case Left(_)  => Left(InvalidI32Err(expr))
    case Right(i) => Right(f(i))


extension (output: Output)
  def framed: Output =
    val sections = Map[String, Queue[Grouped | Label]]()

    output.foreach {
      case item @ Grouped(section, _) =>
        sections.get(section) match
          case Some(q) => q.addOne(item)
          case None    => sections.update(section, Queue(item))
      case value: Value =>
      case label: Label =>
    }

    output.map {
      case inst @ Grouped(section, FrameInit(argc)) =>
        Grouped(section, Frame(argc))
      case inst =>
        inst
    }

  def deduped: Output =
    output.foldLeft[(Output, ImMap[String, Value])]((List.empty, ImMap.empty)) {
      case ((output, values), Value(_, label, _)) if values.contains(label) =>
        (output, values)
      case ((output, values), value @ Value(_, label, _value)) =>
        (output :+ value, values + (label -> value))
      case ((output, values), inst) =>
        (output :+ inst, values)
    }._1

  def labeled: Output =
    val sections = Map[String, Queue[Grouped | Label]]()
    val values = Queue[Value]()

    output.foreach {
      case item @ Grouped(section, _) =>
        sections.get(section) match
          case Some(q) => q.addOne(item)
          case None    => sections.update(section, Queue(item))
      case value: Value => values.addOne(value)
      case label: Label =>
    }

    group("main", Label("main")) ++
    sections.get("main").getOrElse(Queue.empty).toList ++
    (for (section, instructions) <- sections if section != "main"
     yield group(section, Label(section)) ++ instructions).flatten ++
    values

  def sectioned: List[Code] =
    val sections = Map[String, Queue[Code]]()
    val values = Queue[Value]()

    output.foreach {
      case Grouped(section, inst: Instruction) =>
        sections.get(section) match
          case Some(q) => q.addOne(inst)
          case None    => sections.update(section, Queue(inst))
      case Grouped(section, label: Label) =>
        sections.get(section) match
          case Some(q) => q.addOne(label)
          case None    => sections.update(section, Queue(label))
      case value: Value => values.addOne(value)
      case label: Label =>
    }

    sections("main").toList ++
    List(Halt) ++
    sections.filter { (name, i) => name != "main" }.values.flatten.toList ++
    values
