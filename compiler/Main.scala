package bananabread

import parser.{tokenize, parse, Syntax}
import ir.Typeless => tl
import opcode.{Opcode, Instruction}
import vm.Machine

def main(args: Array[String]) =
  val syntax = Syntax.withPrefix(1, "-")
                     .withPrefix(1, "*")
                     .withPrefix(1, "∀")
                     .withPrefix(1, "!")
                     .withInfix(4, "^")
                     .withInfix(3, "*")
                     .withInfix(3, "/")
                     .withInfix(3, "%")
                     .withInfix(2, "+")
                     .withInfix(2, "-")
                     .withInfix(0, "|>")
                     .withInfix(0, "_o_")
                     .withInfix(10, "∈")
                     .withInfix(99, "^")
                     .withInfix(2, ":")
                     .withInfix(3, ">")
                     .withInfix(3, "==")
                     .withInfix(0, ":=")
                     .withInfix(0, "++")
                     .withPostfix(1, "!")

  val code =
    """
    let
      ++ = func (a, b) = a + b
      cond = func (a, b) = a ++ b
      count_down = func (x) =
        if x
        then count_down(x - 1)
        else x
      something = (func (a) = println(a + a))(232)
      something_else =
        begin
          123
          321
          begin
            111
            111
          end
          begin
            111
            27
          end
        end
      a = 1+3
      b = func (x) = x
      c = func (x) = x+x
      d = if 0
          then 123
          else a
      e = func (x) =
            if x
            then x
            else x + 1
      f = func () =
            func () =
              func () =
                func () =
                  func (x) = x + x
    in begin
      begin
        if cond(0, 1)
        then 123
        else 1
      end
      begin
        println(f()()()()(4))
      end
      count_down(1000)
      count_down(1000)
      count_down(1000)
      // println(123)
      // println(321)
      1 + 3
      // println(22 + 20)
      println(something_else)
    end
    """

  val res =
    for
      ast <- parse("<stdin>", code, syntax)
      _=println(s"AST: ${ast}\n\n")
      ir = ast.map(tl.lift)
      _=println(s"IR: ${ir}\n\n")
      ins = opcode.compile(ir)
    yield
      val rt = Machine(ins.dump, info = false, prompt = false)

      rt.printInstructions
      rt.run
      rt.printInfo

  println(res)
