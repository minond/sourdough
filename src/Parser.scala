package bisquit
package parser

import scala.util.{Try, Success, Failure}

case class Syntax(uniops: Seq[String], binops: Seq[String]) {
  def isUniop(id: ast.Id) = uniops.contains(id.lexeme)
  def isUniop(lexeme: String) = uniops.contains(lexeme)
  def isBinop(id: ast.Id) = binops.contains(id.lexeme)
  def isBinop(lexeme: String) = binops.contains(lexeme)
  def withUniop(lexeme: String) = Syntax(lexeme +: uniops, binops)
  def withUniop(id: ast.Id) = Syntax(id.lexeme +: uniops, binops)
  def withBinop(lexeme: String) = Syntax(uniops, lexeme +: binops)
  def withBinop(id: ast.Id) = Syntax(uniops, id.lexeme +: binops)
}

object Syntax {
  def withUniop(lexeme: String) = Syntax(Seq.empty, Seq.empty).withUniop(lexeme)
  def withBinop(lexeme: String) = Syntax(Seq.empty, Seq.empty).withBinop(lexeme)
}

def parse(sourceName: String, sourceString: String, syntax: Syntax): Either[ast.SyntaxErr, List[ast.Expr]] =
  tokenize(sourceName, sourceString, syntax).flatMap { tokens => parse(sourceName, tokens.iterator.buffered, syntax) }

def parse(sourceName: String, tokens: BufferedIterator[ast.Token], syntax: Syntax): Either[ast.SyntaxErr, List[ast.Expr]] =
  tokens
    .map { (token) => parseExpr(token, tokens, sourceName, syntax) }
    .squished

def parseExpr(head: ast.Token, tail: BufferedIterator[ast.Token], sourceName: String, syntax: Syntax): Either[ast.SyntaxErr, ast.Expr] =
  head match {
    case op: ast.Id if syntax.isUniop(op) =>
      for rhs <- expectExpr(op, tail, sourceName, syntax)
      yield ast.Uniop(op, rhs)

    case lit: ast.Literal =>
      tail.headOption match {
        case Some(op: ast.Id) if syntax.isBinop(op) =>
          for rhs <- expectExpr(op, skip(tail), sourceName, syntax)
          yield ast.Binop(lit, op, rhs)

        case Some(_) => Right(lit)
        case None => Right(lit)
      }
  }

def expectExpr(head: ast.Token, tail: BufferedIterator[ast.Token], sourceName: String, syntax: Syntax): Either[ast.SyntaxErr, ast.Expr] =
  tail.headOption match {
    case Some(_) =>
      parseExpr(tail.next, tail, sourceName, syntax)

    case None =>
      Left(ast.UnexpectedEofErr(head))
  }

def tokenize(sourceName: String, sourceString: String, syntax: Syntax): Either[ast.SyntaxErr, List[ast.Token]] =
  tokenize(sourceName, sourceString.iterator.zipWithIndex.buffered, syntax)

def tokenize(sourceName: String, sourceStream: BufferedIterator[(Char, Int)], syntax: Syntax): Either[ast.SyntaxErr, List[ast.Token]] =
  sourceStream
    .filter { (c, _) => !c.isWhitespace }
    .map { (c, i) => nextToken(c, sourceStream, ast.Location(sourceName, i)) }
    .squished

def nextToken(head: Char, tail: BufferedIterator[(Char, Int)], loc: ast.Location): Either[ast.SyntaxErr, ast.Token] =
  head match {
    case ',' => Right(ast.Comma(loc))
    case '.' => Right(ast.Dot(loc))
    case ':' => Right(ast.Colon(loc))
    case '=' => Right(ast.Equal(loc))
    case '(' => Right(ast.OpenParen(loc))
    case ')' => Right(ast.CloseParen(loc))
    case '{' => Right(ast.OpenCurlyParen(loc))
    case '}' => Right(ast.CloseCurlyParen(loc))
    case '[' => Right(ast.OpenSquareBraket(loc))
    case ']' => Right(ast.CloseSquareBraket(loc))

    case head if isNumHead(head) =>
      val rest = takeWhile(tail, isNumTail).mkString
      val lexeme = head +: rest
      Try { lexeme.toFloat } match {
        case Failure(_) => Left(ast.BadNumErr(lexeme, loc))
        case Success(_) => Right(ast.Num(lexeme, loc))
      }

    case head if isIdHead(head) =>
      val rest = takeWhile(tail, isIdTail).mkString
      val lexeme = head +: rest
      Right(ast.Id(lexeme, loc))

    case _ => Left(ast.UnknownCharErr(head, loc))
  }


type Pred[T] = T => Boolean
type Predcond = (Boolean, Boolean) => Boolean

def flpreds[T](preds: Seq[Pred[T]], id: Boolean = true)(cond: Predcond) =
  (c: T) => preds.foldLeft(id)((acc, pred) => cond(acc, pred(c)))

def ge[T <: Char](x: T) = (c: T) => c >= x
def le[T <: Char](x: T) = (c: T) => c <= x
def is[T <: Char](x: T) = (c: T) => c == x
def oneof[T <: Char](xs: T*) = (c: T) => xs.contains(c)
def not[T <: Char](f: Pred[T]) = flpreds(Seq(f))(_ && !_)
def and[T <: Char](fs: Pred[T]*) = flpreds(fs)(_ && _)
def or[T <: Char](fs: Pred[T]*) = flpreds(fs, false)(_ || _)

val isWhitespace = oneof(' ', '\t', '\r', '\n', '\f')
val isNumHead = and(ge('0'),
                    le('9'))
val isNumTail = or(isNumHead,
                   is('.'))
val isIdTail = and(not(isWhitespace),
                   not(oneof('(', ')', '{', '}', '[', ']', ',', '.', '=', ':')))
val isIdHead = and(isIdTail,
                   not(isNumTail))

def takeWhile[T](source: BufferedIterator[(T, _)], pred: Pred[T]): List[T] =
  def aux(buff: List[T]): List[T] =
    if source.isEmpty
    then buff
    else
      val curr = source.head
      if pred(curr._1)
      then aux(buff :+ source.next._1)
      else buff
  aux(List.empty)

def skip[T](it: BufferedIterator[T]): BufferedIterator[T] =
  it.next
  it

implicit class Eithers[L, R](val eithers: Iterator[Either[L, R]]) {
  /** Converts an [[Iterator[Either[L, R]]]] into an [[Either[L, List[R]]]].
   */
  def squished: Either[L, List[R]] =
    eithers.foldLeft[Either[L, List[R]]](Right(List())) {
      (acc, x) =>
        acc.flatMap(xs => x.map(xs :+ _))
    }
}
