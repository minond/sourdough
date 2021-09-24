package bananabread
package error

import backend.opcode.error => genop
import parsing.error => parse
import runtime.error => runtime
import typechecker.error => typechecker
import ir.typeless.error => typelessIr
import ir.typed.error => typedIr

import ir.typed
import ir.typeless
import parsing.location.Location
import parsing.ast
import parsing.ast.{Def, Expr}
import bananabread.runtime.instruction.{Instruction, Code, Label, Value, pp => inspp}

import scala.io.AnsiColor.{BOLD, RESET}

val GRAY = String("\u001B[37m")

val SourcePadding = 5
val OpcodePadding = 10


def pp(err: Err, source: String): String = err match
  case parse.BadNumErr(_, location) =>
    lines(
      generateSyntaxErrorLine(s"bad number", location, source),
      isolateBadLine(location, source),
    )

  case parse.UnclosedStringErr(location) =>
    lines(
      generateSyntaxErrorLine(s"bad string", location, source),
      isolateBadLine(location, source),
    )

  case parse.EmptyBeginNotAllowedErr(token) =>
    lines(
      generateSyntaxErrorLine(s"empty begin", token.location, source),
      isolateBadLine(token.location, source),
    )

  case parse.BadOperatorDefinitionErr(location) =>
    lines(
      generateSyntaxErrorLine(s"bad operator definition", location, source),
      isolateBadLine(location, source),
    )

  case parse.UnexpectedEofErr(prev) =>
    lines(
      generateSyntaxErrorLine(s"unexpected <eof> after `$prev`", prev.location, source),
      isolateBadLine(prev.location, source),
    )

  case parse.UnexpectedTokenErr(token) =>
    lines(
      generateSyntaxErrorLine(s"unexpected token `$token` found", token.location, source),
      isolateBadLine(token.location, source),
    )

  case parse.MissingExpectedTokenErr(token, expected) =>
    lines(
      generateSyntaxErrorLine(s"expected `$expected` but found `$token`", token.location, source),
      isolateBadLine(token.location, source),
    )

  case parse.MissingExpectedTokenAfterErr(after, token, expected) =>
    lines(
      generateSyntaxErrorLine(s"expected `$expected` after `$after` but found `$token`", token.location, source),
      isolateBadLine(after.location, source),
    )

  case genop.BadPushErr(_, node) =>
    node.expr match
      case expr : Expr =>
        lines(
          generateRuntimeErrorLine(s"bad push, `${expr}` is not a valid runtime value", expr.location, source),
          isolateBadLine(expr.location, source),
        )
      case stmt : Def =>
        lines(
          generateRuntimeErrorLine(s"bad push, `${stmt.value}` is not a valid runtime value", stmt.value.location, source),
          isolateBadLine(stmt.value.location, source),
        )
      case _ : (ast.Module | ast.Import) =>
        ???

  case genop.UndeclaredIdentifierErr(id) =>
    lines(
      generateRuntimeErrorLine(s"${id.expr.lexeme} was referenced but not found", id.expr.location, source),
      isolateBadLine(id.expr.location, source),
    )

  case genop.LookupErr(id) =>
    lines(
      generateRuntimeErrorLine(s"${id.lexeme} was referenced but not found", id.location, source),
      isolateBadLine(id.location, source),
    )

  case genop.UnknownUserOpcodeErr(code, loc) =>
    lines(
      generateSyntaxErrorLine(s"invalid opcode", loc, source),
      isolateBadLine(code.location, source),
    )

  case genop.BadCallErr(typed.Id(id, _)) =>
    lines(
      generateRuntimeErrorLine(s"bad call to `${id.lexeme}`", id.location, source),
      isolateBadLine(id.location, source),
    )

  case genop.BadCallErr(node) =>
    lines(
      generateRuntimeErrorLine(s"bad call to value of kind ${node.getClass.getSimpleName}", node.expr.location, source),
      isolateBadLine(node.expr.location, source),
    )

  case runtime.RuntimeErr(msg, ins, codes, registers) =>
    lines(
      generateOpcodeErrorLine(msg),
      isolateBadOpcode(registers.pc.value, codes),
    )

  case typechecker.UnknowTypeErr(tag) =>
    lines(
      generateTypeErrorLine(s"unknown type `${tag.id.lexeme}`", tag.id.location, source),
      isolateBadLine(tag.id.location, source),
    )

  case typechecker.LookupErr(label, ir) =>
    lines(
      generateTypeErrorLine(s"`${label}` was referenced but not found", ir.expr.location, source),
      isolateBadLine(ir.expr.location, source),
    )

  case typechecker.UnunifiedTypeVarErr(_, ir) =>
    lines(
      generateTypeErrorNearLine(s"unable to unify expression, perhaps a type annotation is missing", ir.expr.location, source),
      isolateBadLine(ir.expr.location, source),
    )

  case typechecker.UnificationErr(expected, got, ir) =>
    lines(
      generateTypeErrorLine(s"expected value of type `$expected` but got value of type `$got`", ir.expr.location, source),
      isolateBadLine(ir.expr.location, source),
    )

  case typedIr.UndeclaredIdentifierErr(typeless.Id(id)) =>
    lines(
      generateTypeErrorLine(s"undeclared identifier `$id`", id.location, source),
      isolateBadLine(id.location, source),
    )

  case _ =>
    s"${err.getClass.getCanonicalName}: ${err}"

def generateSyntaxErrorLine(message: String, loc: Location, source: String) =
  s"${BOLD}syntax error: ${message} in ${generateCoordinates(loc, source)}${RESET}"

def generateRuntimeErrorLine(message: String, loc: Location, source: String) =
  s"${BOLD}runtime error: ${message} in ${generateCoordinates(loc, source)}${RESET}"

def generateOpcodeErrorLine(message: String) =
  s"${BOLD}opcode runtime error: ${message}${RESET}"

def generateTypeErrorLine(message: String, loc: Location, source: String) =
  s"${BOLD}type error: ${message} in ${generateCoordinates(loc, source)}${RESET}"

def generateTypeErrorNearLine(message: String, loc: Location, source: String) =
  s"${BOLD}type error: ${message} near ${generateCoordinates(loc, source)}${RESET}"

def generateCoordinates(loc: Location, source: String) =
  val (row, col) = offsetToRowAndCol(loc.offset, source)
  s"${loc.source}:${row + 1}:${col + 1}"

def offsetToRowAndCol(offset: Int, source: String): (Int, Int) =
  source.split("\n").foldLeft[(Int, Int)]((0, offset)) {
    case ((row, rem), line) =>
      if rem - line.size <= 0
      then return (row, rem)
      else (row + 1, rem - line.size - 1)
  }

def isolateBadLine(loc: Location, source: String) =
  val (row, col) = offsetToRowAndCol(loc.offset, source)
  val lines = source.split("\n")
  val max = lines.size
  val pointer = generatePointer(col)
  val bads = lines.zipWithIndex.slice(row - SourcePadding, row + SourcePadding)
  bads.foldLeft[List[String]](List.empty) {
    case (acc, (line, index)) =>
      if index == row
      then acc :+ withLineNumber(max, index, line) :+ withLineNumber(max, -1, pointer)
      else acc :+ withLineNumber(max, index, line)
  }.mkString("\n")

def isolateBadOpcode(pc: Int, codes: List[Code]) =
  val max = codes.size
  val bads = codes.zipWithIndex.slice(pc - OpcodePadding, pc + OpcodePadding)
  val pointer = generatePointer(0)
  bads.foldLeft[List[String]](List.empty) {
    case (acc, (line, index)) =>
      val code = line match
        case op: Value       => inspp(op)
        case op: Label       => s"    ${inspp(op)}"
        case op: Instruction => s"        ${inspp(op)}"
      val formatted =
        if index == pc
        then s"${BOLD}${code}${RESET}"
        else code

      acc :+ withLineNumber(max, index, formatted)
  }.mkString("\n")

def generatePointer(col: Int) =
  " " * col + s"${BOLD}^${RESET}"

def withLineNumber(max: Int, row: Int, line: String) =
  if row == -1
  then s"${GRAY}${pad(max, " ", " ")}|${RESET} $line"
  else s"${GRAY}${pad(max, (row + 1).toString, "0")}|${RESET} $line"

def pad(max: Int, str: String, using: String) =
  val currSize = str.size
  val maxSize = max.toString.size
  if currSize < maxSize
  then (using * (maxSize - currSize)) + str
  else str

def lines(args: String*): String =
  args.mkString("\n")
