package bananabread
package parsing.language

import parsing.{Parsed, takeWhile, eat, skip, lookahead}
import parsing.{and, is, not, oneof, or}
import parsing.{isLetter, isNewline, isNumeric, isWhitespace}

import parsing.location.Location
import parsing.ast._
import parsing.error._

import utils.{isAn, squished, safeToFloat, without}

import scala.reflect.ClassTag


type Tokens = BufferedIterator[Token]

def parse(sourceName: String, sourceString: String): Parsed[Tree] =
  parse(sourceName, sourceString, Syntax())
def parse(sourceName: String, sourceString: String, syntax: Syntax): Parsed[Tree] =
  tokenize(sourceName, sourceString, syntax).flatMap { tokens =>
    parse(sourceName, tokens.without[Comment].iterator.buffered, syntax)
  }
def parse(sourceName: String, tokens: Tokens, syntax: Syntax): Parsed[Tree] =
  for
    nodes <- tokens
      .foldLeft[(List[Parsed[Stmt | Expr]], Syntax)](List.empty, syntax) {
        case ((acc, syntax), token) =>
          val expr = parseTop(token, tokens, syntax)
          if ! isOperatorDefinition(expr)
          then (acc :+ expr, syntax)
          else operatorDefinition(expr) match
            case Some(pos, prec, name) =>
              (acc, syntax.withOp(pos, prec, name))
            case None =>
              (acc :+ Left(BadOperatorDefinitionErr(token.location)), syntax)
      }
      ._1
      .squished
  yield
    Tree(nodes)

def parseTop(head: Token, tail: Tokens, syntax: Syntax): Parsed[Stmt | Expr] =
  head match
    case _ if is(head, "def") => parseDef(head, tail, syntax)
    case _ => parseExpr(head, tail, syntax)

def parseExpr(head: Token, tail: Tokens, syntax: Syntax): Parsed[Expr] =
  head match
    case op: Id if syntax.isPrefix(op) => parseExprCont(parseUniop(op, tail, syntax), tail, syntax)
    case _ => parseExprCont(parsePrimary(head, tail, syntax), tail, syntax)

def parseUniop(op: Id, tail: Tokens, syntax: Syntax): Parsed[Uniop] =
  for rhs <- parsePrimary(tail.next, tail, syntax)
  yield Uniop(op, rhs)

def parsePrimary(head: Token, tail: Tokens, syntax: Syntax): Parsed[Expr] =
  head match
    case word: Id if is(word, "func")  => parseLambda(word, tail, syntax)
    case word: Id if is(word, "if")    => parseCond(word, tail, syntax)
    case word: Id if is(word, "let")   => parseLet(word, tail, syntax)
    case word: Id if is(word, "begin") => parseBegin(word, tail, syntax)
    case paren: OpenParen              => parseGroup(paren, tail, syntax)
    case lit: Num    => Right(lit)
    case lit: Str    => Right(lit)
    case lit: Id     => Right(lit)
    case lit: Symbol => Right(lit)
    case unexpected  => Left(UnexpectedTokenErr(unexpected))

def parseLambda(start: Token, tail: Tokens, syntax: Syntax): Parsed[Lambda] =
  for
    params <- parseParams(start, skip(tail), syntax)
    eq     <- eat("=", start, tail)
    body   <- parseNextExpr(eq, tail, syntax)
  yield
    Lambda(params, body)

def parseParams(
  head: Token,
  tail: Tokens,
  syntax: Syntax,
  acc: List[Param] = List.empty
): Parsed[List[Param]] =
  tail.headOption match
    case Some(_: CloseParen) =>
      tail.next
      Right(acc)

    case Some(by: Comma) =>
      eat[Id](tail.next, tail).flatMap { name =>
        parseParamTy(name, tail, syntax).flatMap { ty =>
          parseParams(head, tail, syntax, acc :+ Param(name, ty))
        }
      }

    case Some(_) =>
      eat[Id](head, tail).flatMap { name =>
        parseParamTy(name, tail, syntax).flatMap { ty =>
          parseParams(head, tail, syntax, acc :+ Param(name, ty))
        }
      }

    case None =>
      Left(UnexpectedEofErr(head))

def parseParamTy(head: Token, tail: Tokens, syntax: Syntax): Parsed[Option[Id]] =
  tail.headOption match
    case Some(_: Colon) =>
      eat[Id](tail.next, tail).map(Some(_))

    case _ =>
      Right(None)

def parseCond(start: Token, tail: Tokens, syntax: Syntax): Parsed[Cond] =
  for
    cond <- parseExpr(tail.next, tail, syntax)
    _    <- eat("then", start, tail)
    pass <- parseExpr(tail.next, tail, syntax)
    _    <- eat("else", start, tail)
    fail <- parseExpr(tail.next, tail, syntax)
  yield
    Cond(start, cond, pass, fail)

def parseLet(start: Token, tail: Tokens, syntax: Syntax): Parsed[Let] =
  for
    bindings <- parseBindings(start, tail, syntax)
    _        <- eat("in", start, tail)
    body     <- parseExpr(tail.next, tail, syntax)
  yield
    Let(start, bindings, body)

def parseDef(start: Token, tail: Tokens, syntax: Syntax): Parsed[Def] =
  for
    name  <- eat[Id](start, tail)
    next  = lookahead(start, tail)
    value <- if next.isAn[OpenParen]
             then parseLambda(start, tail, syntax)
             else parseDefValue(start, tail, syntax)
  yield
    Def(name, value)

def parseDefValue(start: Token, tail: Tokens, syntax: Syntax): Parsed[Expr] =
  for
    _     <- eat("=", start, tail)
    value <- parseExpr(tail.next, tail, syntax)
  yield
    value

def parseBindings(start: Token, tail: Tokens, syntax: Syntax): Parsed[List[Binding]] =
  for
    binding  <- parseBinding(start, tail, syntax)
    next     = lookahead(start, tail)
    bindings <- if is(next, "in")
                then Right(List.empty)
                else parseBindings(start, tail, syntax)
  yield
    binding +: bindings

def parseBinding(start: Token, tail: Tokens, syntax: Syntax): Parsed[Binding] =
  for
    label <- eat[Id](start, tail)
    eq    <- eat("=", label, tail)
    value <- parseExpr(tail.next, tail, syntax)
  yield
    Binding(label, value)

def parseBegin(start: Token, tail: Tokens, syntax: Syntax): Parsed[Begin] =
  for
    heade <- if is(lookahead(start, tail), "end")
             then Left(EmptyBeginNotAllowedErr(start))
             else parseExpr(tail.next, tail, syntax)
    taile <- parseBeginTail(start, tail, syntax)
    _     <- eat("end", start, tail)
  yield
    Begin(heade, taile)

def parseBeginTail(start: Token, tail: Tokens, syntax: Syntax): Parsed[List[Expr]] =
  for
    heade <- if is(lookahead(start, tail), "end")
            then return Right(List.empty)
            else parseExpr(tail.next, tail, syntax)
    next  = lookahead(start, tail)
    taile <- if is(next, "end")
            then Right(List.empty)
            else parseBeginTail(start, tail, syntax)
  yield
    heade +: taile

def parseGroup(paren: Token, tail: Tokens, syntax: Syntax): Parsed[Expr] =
  for
    inner <- parseExpr(tail.next, tail, syntax)
    _     <- eat[CloseParen](paren, tail)
  yield
    inner

def parseExprCont(currRes: Parsed[Expr], tail: Tokens, syntax: Syntax): Parsed[Expr] =
  currRes.flatMap { curr => parseExprCont(curr, tail, syntax) }

def parseExprCont(curr: Expr, tail: Tokens, syntax: Syntax): Parsed[Expr] =
  tail.headOption match
    case Some(op: Id) if syntax.isPostfix(op) =>
      tail.next
      Right(Uniop(op, curr))

    case Some(op: Id) if syntax.isInfix(op) =>
      for rhs <- parseNextExpr(op, skip(tail), syntax)
      yield
        rhs match
          case Binop(nextOp, nextLhs, nextRhs) if syntax.isInfix(nextOp) &&
            syntax.infixPrecedence(op) > syntax.infixPrecedence(nextOp) =>
              Binop(nextOp, Binop(op, curr, nextLhs), nextRhs)

          case _ => Binop(op, curr, rhs)

    case Some(paren: OpenParen) =>
      parseExprCont(parseNextExprsByUntil[Comma, CloseParen](paren, skip(tail), syntax).map { args =>
        App(curr, args)
      }, tail, syntax)

    case Some(_) => Right(curr)
    case None => Right(curr)

def parseNextExprsByUntil[By: ClassTag, Until: ClassTag](
  head: Token,
  tail: Tokens,
  syntax: Syntax,
  acc: List[Expr] = List.empty
): Parsed[List[Expr]] =
  tail.headOption match
    case Some(_: Until) =>
      tail.next
      Right(acc)

    case Some(by: By) =>
      parseNextExpr(tail.next, tail, syntax).flatMap { expr =>
        parseNextExprsByUntil[By, Until](head, tail, syntax, acc :+ expr)
      }

    case Some(_) =>
      parseExpr(tail.next, tail, syntax).flatMap { expr =>
        parseNextExprsByUntil[By, Until](head, tail, syntax, acc :+ expr)
      }

    case None =>
      Left(UnexpectedEofErr(head))

def parseNextExpr(head: Token, tail: Tokens, syntax: Syntax): Parsed[Expr] =
  tail.headOption match
    case Some(_) => parseExpr(tail.next, tail, syntax)
    case None    => Left(UnexpectedEofErr(head))


def tokenize(sourceName: String, sourceString: String, syntax: Syntax): Parsed[List[Token]] =
  tokenize(sourceName, sourceString.iterator.zipWithIndex.buffered, syntax)
def tokenize(sourceName: String, sourceStream: BufferedIterator[(Char, Int)], syntax: Syntax): Parsed[List[Token]] =
  sourceStream
    .filter { (c, _) => !c.isWhitespace }
    .map { (c, i) => nextToken(c, sourceStream, Location(sourceName, i), syntax) }
    .squished

def nextToken(
  head: Char,
  tail: BufferedIterator[(Char, Int)],
  loc: Location,
  syntax: Syntax,
  ignoreComment: Boolean = false,
  ignorePString: Boolean = false,
): Parsed[Token] = head match
  case ',' => Right(Comma(loc))
  case '.' => Right(Dot(loc))
  case ':' => Right(Colon(loc))
  case '(' => Right(OpenParen(loc))
  case ')' => Right(CloseParen(loc))
  case '{' => Right(OpenCurlyParen(loc))
  case '}' => Right(CloseCurlyParen(loc))
  case '[' => Right(OpenSquareBraket(loc))
  case ']' => Right(CloseSquareBraket(loc))

  case '/' if !ignoreComment =>
    tail.headOption match
      case Some('/', _) =>
        val comment = takeWhile(skip(tail), not(isNewline))
        Right(Comment(comment.mkString.strip, loc))
      case _ => nextToken(head, tail, loc, syntax, ignoreComment=true)

  case '%' if !ignorePString =>
    tail.headOption match
      case Some('{', _) =>
        val str = takeWhile(skip(tail), not(is('}')))
        // TODO Unsafe head lookup
        if tail.next._1 != '}'
        then Left(UnclosedStringErr(loc))
        else Right(Str(str.mkString, loc))
      case _ => nextToken(head, tail, loc, syntax, ignorePString=true)

  case '\'' =>
    val symbol = takeWhile(tail, isSymbolTail).mkString

    Right(Symbol(symbol, loc))

  case head if isNumHead(head) =>
    val rest = takeWhile(tail, isNumTail).mkString
    val lexeme = head +: rest

    lexeme.safeToFloat match
      case Left(_)  => Left(BadNumErr(lexeme, loc))
      case Right(_) => Right(Num(lexeme, loc))

  case head if isIdHead(head) =>
    val rest = takeWhile(tail, isIdTail).mkString
    val lexeme = head +: rest

    Right(Id(lexeme, loc))

  case head =>
    val rest = takeWhile(tail, isUnknownTail).mkString
    val lexeme = head +: rest

    Right(Id(lexeme, loc))


val isNumHead = isNumeric
val isNumTail = or(isNumHead,
                   is('.'))
val isIdTail = and(not(isWhitespace),
                   or(isNumeric,
                      isLetter,
                      is('_')))
val isIdHead = and(isIdTail,
                   not(isNumeric))
val isUnknownTail = and(not(isIdTail),
                        not(isWhitespace),
                        not(oneof(',', '.', '(', ')', '{', '}', '[', ']')))
val isSymbolTail = and(not(isWhitespace),
                       not(oneof(',', '(', ')', '{', '}', '[', ']')))

def isOperatorDefinition(expr: Parsed[Expr | Stmt]) = expr match
  case Right(App(Id("operator", _), _)) => true
  case _ => false

def operatorDefinition(expr: Parsed[Expr | Stmt]): Option[(OpPosition, Int, String)] = expr match
  case Right(App(Id("operator", _), Symbol("prefix",  _) :: Num(prec, _) :: Symbol(name, _) :: Nil)) => Some((Prefix, prec.toInt, name))
  case Right(App(Id("operator", _), Symbol("infix",   _) :: Num(prec, _) :: Symbol(name, _) :: Nil)) => Some((Infix, prec.toInt, name))
  case Right(App(Id("operator", _), Symbol("postfix", _) :: Num(prec, _) :: Symbol(name, _) :: Nil)) => Some((Postfix, prec.toInt, name))
  case _ => None
