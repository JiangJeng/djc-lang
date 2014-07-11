package djc.lang.typ

import djc.lang.Gensym._
import djc.lang.TypedSyntax._
import djc.lang.typ.Types._

object Checker {
  type Context = Map[Symbol, Type]

  case class TypeCheckException(msg: String) extends RuntimeException(msg)

  def typeCheck(gamma: Context, boundTv: Set[Symbol], p: Exp): Type = p match {
//    case _ if {println(s"gammma: ${gamma.keys}");false} => ?()

    case BaseCall(b, ts, es) => {
      val argTs = es map (typeCheck(gamma, boundTv, _))
      val (tArgs, bounds) = b.targs.unzip
      if (!ts.corresponds(bounds)(_ <:< _))
         throw TypeCheckException(s"Type arguments do not match type parameters. Applied $ts to $b.targs\n in $p")

      val substs = (tArgs zip ts) map { case (alpha, t) =>
        val f: Type => Type = SubstType(alpha, t).apply
        f
      }
      val sigma = Function.chain(substs)  //TODO extend substitutions to be simultaneous
      val bSig = b.ts map sigma
      if (argTs.corresponds(bSig)(_ <:< _)) // actual arguments have subtypes of declared parameters
        sigma(b.res)
      else
        throw TypeCheckException(s"Arguments of base call mismatch. Was: $argTs, Expected: ${bSig}\n  in ${BaseCall(b, ts, es)}")
    }

    case Par(ps)
      if ps.map(typeCheck(gamma, boundTv, _)) forall (_ === Unit) =>
      Unit

    case Send(rcv, args) => {
      val trcv: TSvc = typeCheck(gamma, boundTv, rcv) match {
        case t: TSvc => t
        case t => throw TypeCheckException(s"Illegal receiver type. Expected: TSvc(_), was $t\n  in ${Send(rcv, args)}")
      }
      val targs = args.map(typeCheck(gamma, boundTv, _))
      if (!targs.corresponds(trcv.params)(_<:<_)) // actual send arguments have subtypes of declared parameters
        throw TypeCheckException(s"Send arguments have wrong types for receiver \n  $rcv.\nArguments: $args\nExpected: ${trcv.params}\nWas: $targs")
      Unit
    }

    case Var(x) =>
      gamma.getOrElse(x, throw TypeCheckException(s"Unbound variable $x\ngamma: $gamma\nboundTv: $boundTv"))

    case ref@ServiceRef(srv, x) =>
      typeCheck(gamma, boundTv, srv) match {
        case TSrv(TSrvRep(svcs)) if svcs.contains(x) => svcs(x)
        case TSrvRep(_) => throw TypeCheckException(s"Cannot refer to service of non-running server in $ref")
        case x => throw TypeCheckException(s"typeCheck failed at $p\ngamma: $gamma\nboundTv: $boundTv\nwith $x")
      }

    case srv@ServerImpl(rules) => {
      val ruleTypes = rules map (typecheckRule(gamma, boundTv, _, srv.signature))

      if (!(FreeTypeVars(srv.signature) subsetOf boundTv))
        throw TypeCheckException(s"Illegal free type variables: ${FreeTypeVars(srv.signature) -- boundTv}")

      srv.signature
    }

    case sp@Spawn(_, e) =>
      typeCheck(gamma, boundTv, e) match {
        case t: TSrvRep => TSrv(t)
        case t => throw TypeCheckException(s"Illegal spwan expression. Expected: TSvrRep(_), was $t\n  in $sp)}")
      }


    case TApp(p2, t) =>
      if (!(FreeTypeVars(t) subsetOf boundTv))
        throw TypeCheckException(s"typeCheck failed at $p\ngamma: $gamma\nboundTv: $boundTv\n  free type vars ${FreeTypeVars(t) -- boundTv}")
      typeCheck(gamma, boundTv, p2) match {
        case TUniv(alpha, bound, t2) if t <:< bound =>
          SubstType(alpha, t)(t2)

        case x => throw TypeCheckException(s"typeCheck failed at $p\ngamma: $gamma\nboundTv: $boundTv\nwith $x")
      }

    case TAbs(alpha, bound1, p1) =>
      val dontSubst = !boundTv(alpha)
      lazy val alphafresh = gensym(alpha, boundTv)
      lazy val p1fresh = SubstType(alpha, TVar(alphafresh))(p1)
      val (alphares, p1res) = if (dontSubst) (alpha, p1) else (alphafresh, p1fresh)
      val t = typeCheck(gamma, boundTv + alphares, p1res)

      TUniv(alphares, bound1, t)

    case UnsafeCast(e, t) => {
      typeCheck(gamma, boundTv, e)
      t
    }

    case UpCast(e, t) => {
      val te = typeCheck(gamma, boundTv, e)
      if (te <:< t)
        t
      else
        throw TypeCheckException(s"Cannot upcast\n  expression $e\n  of type $te\n  to type $t")
    }

    case _ =>
      throw TypeCheckException(s"typeCheck failed at $p\ngamma: $gamma\nboundTv: $boundTv")
  }


  def typecheckRule(gamma: Context, boundTv: Set[Symbol], r: Rule, srvSignature: TSrvRep): Type = {
    val ruleGamma = gamma ++ r.rcvars + ('this -> TSrv(srvSignature))
//    println(s"rule-pats: ${r.ps}")
//    println(s"rule-gamma: ${ruleGamma.keys}")
    val t = typeCheck(ruleGamma, boundTv, r.p)
    if (!(t === Unit))
      throw TypeCheckException(s"Illegal rule type in rule $r, expected: Unit, was: $t")
    t
  }
}