package bisquit

import parser.{tokenize, parse, Syntax}


def main(args: Array[String]) =
  val syntax = Syntax().withPrefix(1, "-")
                       .withInfix(4, "^")
                       .withInfix(3, "*")
                       .withInfix(3, "/")
                       .withInfix(3, "%")
                       .withInfix(2, "+")
                       .withInfix(2, "-")
                       .withPostfix(1, "!")

  // println(tokenize("<stdin>", "  4.3 + 3 + a(1,2,345)", syntax))
  // println(parse("<stdin>", "  4.3 + 54.764", syntax))
  println(parse("<stdin>", "b + c * 3", syntax))
  println(parse("<stdin>", "a-a", syntax))
  println(parse("<stdin>", "a - -a", syntax))
  println(parse("<stdin>", "-3", syntax))
  println(parse("<stdin>", "_1+_2", syntax))
  println(parse("<stdin>", "_1+_2@3", syntax))
  println(parse("<stdin>", "a::b", syntax))
  println(parse("<stdin>", "a : b", syntax))
  println(parse("<stdin>", "a!", syntax))
  // println(parse("<stdin>", "  4.3 + 3 + a(1,2,345)", syntax))