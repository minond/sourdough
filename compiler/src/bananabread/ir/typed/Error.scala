package bananabread
package ir
package typed
package error


sealed trait LiftErr
// TODO This is being moved down to the linked ir, remove this.
case class UndeclaredIdentifierErr(id: typeless.Id) extends LiftErr
