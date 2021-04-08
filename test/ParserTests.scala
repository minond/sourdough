package sourdough
package test

import org.scalatest._
import flatspec._
import matchers._

import parser.{parse, Syntax}

class ParserTests extends AnyFlatSpec with should.Matchers:
  val stdOps = Syntax().withPrefix(0, "-")
                       .withPrefix(0, "∀")
                       .withInfix(4, "^")
                       .withInfix(3, "*")
                       .withInfix(3, "/")
                       .withInfix(2, "+")
                       .withInfix(2, "-")
                       .withInfix(1, ":")
                       .withInfix(2, "∈")
                       .withInfix(1, ">")
                       .withPostfix(10, "!")

  def exprsOf(code: String, syntax: Syntax = stdOps) =
    parse("<test>", code, syntax).getOrElse(???).map(_.toString)

  def astOf(code: String, syntax: Syntax = stdOps) =
    exprsOf(code, syntax).head

  it should "parse prefix operators" in {
    astOf("-b") shouldEqual "(- b)"
  }

  it should "parse infix operators" in {
    astOf("a - b") shouldEqual "(- a b)"
  }

  it should "parse postfix operators" in {
    astOf("x!") shouldEqual "(! x)"
  }

  it should "parse complex expressions with binary operators of different precedence" in {
    astOf("∀ n ∈ N : n^2 > n") shouldEqual "(∀ (: (∈ n N) (> (^ n 2) n)))"
  }
