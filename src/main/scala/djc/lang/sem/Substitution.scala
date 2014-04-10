package djc.lang.sem

import djc.lang.Syntax.Mapper._
import util.Bag
import djc.lang.Syntax.Folder._
import djc.lang.Syntax._

object Substitution {
  def substService(x: Symbol, s: Service): Mapper = {
    ((None, // Def
      None, // Par
      None) // Send
      ,(Some(x2 => if (x == x2) s else ServiceVar(x2)), // ServiceVar
      None) // ServiceRef
      ,(None, // ServerVar
      None) // ServerImpl
      ,Some((ps: Bag[Pattern], p: Prog) => substServiceRule(x, s, ps, p)) // Rule
      ,None) // Pattern
  }
  def substServiceRule(x: Symbol, s: Service, ps: Bag[Pattern], p: Prog): Rule = {
    val patVars = (ps map (fold(freeServiceVars, _))).flatten
    if (patVars contains x)
      Rule(ps, p)
    else
      Rule(ps, map(substService(x, s), p))
  }

  def substServer(x: Symbol, s: Server): Mapper = {
    ((Some((x2: Symbol, s2: Server, p2: Prog) => substServerDef(x, s, x2, s2, p2)), // Def
      None, // Par
      None) // Send
      ,(None, // ServiceVar
      None) // ServiceRef
      ,(Some(x2 => if (x == x2) s else ServerVar(x2)), // ServerVar
      None) // ServerImpl
      ,None // Rule
      ,None) // Pattern
  }
  def substServerDef(x: Symbol, s: Server, x2: Symbol, s2: Server, p2: Prog) = {
    val isThis = x == 'this
    val svars = fold(freeServerVars, s)
    val captureAvoiding = !svars.contains(x2)
    lazy val x2fresh = gensym(x2, svars)
    lazy val p2fresh = map(substServer(x2, ServerVar(x2fresh)), p2)
    val (x2res, p2res) = if (captureAvoiding) (x2,p2) else (x2fresh,p2fresh)

    if (isThis)
      Def(x2res, s2, map(substServer(x, s), p2res))
    else if (x == x2)
      Def(x2, map(substServer(x, s), s2), p2)
    else
      Def(x2res, map(substServer(x, s), s2), map(substServer(x, s), p2res))
  }


  val freeServerVars: Folder[Set[Symbol]] = {
    type R = Set[Symbol]
    (((x: Symbol, ds: R, ps: R) => (ds-'this) ++ (ps-x), // Def
      (xs: Bag[R]) => xs.flatten, // Par
      (srv: R, args: List[R]) => srv ++ args.flatten) // Send
      ,(x => Set(), // ServiceVar
      (srv: R, x: Symbol) => srv) // ServiceRef
      ,(x => Set(x), // ServerVar
      (xs: Bag[R]) => xs.flatten) // ServerImpl
      ,(ps: Bag[R], p: R) => p // Rule
      ,(name: Symbol, params: List[Symbol]) => Set()) // Pattern
  }

  val freeServiceVars: Folder[Set[Symbol]] = {
    type R = Set[Symbol]
    (((x: Symbol, ds: R, ps: R) => ds ++ ps, // Def
      (xs: Bag[R]) => xs.flatten, // Par
      (srv: R, args: List[R]) => srv ++ args.flatten) // Send
      ,(x => Set(x), // ServiceVar
      (srv: R, x: Symbol) => srv) // ServiceRef
      ,(x => Set(), // ServerVar
      (xs: Bag[R]) => xs.flatten) // ServerImpl
      ,(ps: Bag[R], p: R) => p -- (ps.flatten) // Rule
      ,(name: Symbol, params: List[Symbol]) => params.toSet) // Pattern
  }

  def gensym(x: Symbol, used: Set[Symbol]): Symbol = gensym(x, 0, used)
  def gensym(x: Symbol, i: Int, used: Set[Symbol]): Symbol = {
    val s = Symbol(s"${x.name}_$i")
    if (used contains s)
      gensym(x, i+1, used)
    else
      s
  }
}