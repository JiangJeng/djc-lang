package djc.lang.sem.nondeterm_3_routed

import djc.lang.sem.{Crossproduct, AbstractSemantics}
import util.Bag
import djc.lang.FlatSyntax._
import Crossproduct._
import Data._

object Semantics extends AbstractSemantics[Value] {

  def normalizeVal(v: Val) = v.asInstanceOf[UnitVal].sends map (_.toNormalizedProg)

  override def interp(p: Prog) = {
    Router.routeTable = collection.mutable.Map()
    interp(p, Map(), Bag())
  }

  def interp(p: Prog, env: Env, sends: Bag[SendVal]): Res[Val] = p match {
    case Var(y) if env.isDefinedAt(y) =>
      Set(env(y))

    case Def(x, p1, p2) =>
      nondeterministic[Val, Val](
        interp(p1, env, sends),
        result => interp(p2, env + (x -> result), sends)
      )

    case s@ServerImpl(rules) =>
      val addr = ServerAddr(Router.registerServer(ServerClosure(s, env)))
      Set(ServerVal(addr))

    case ServiceRef(srv, x) =>
      nondeterministic[Val,Val](
        interp(srv, env, sends),
        { case sval@ServerVal(addr) =>
            //val ServerClosure(impl, _) = lookupAddr(addr)
       //   if impl.rules.exists(_.ps.exists(_.name == x)) => //TODO add this check back once we have good solution for primitive services
            Set(ServiceVal(sval, x))

       //   case ServerVal(impl, _) => throw SemanticException(s"service $x not defined in server $impl")
        }
      )

    case Par(ps) =>
      nondeterministic[Bag[SendVal],Val](
        crossProduct(ps map (interp(_, env, Bag()) map {case UnitVal(s) => s})),
        x => interpSends(sends ++ x))

    case Send(rcv, args) =>
      nondeterministic[Val,Val](
        interp(rcv, env, sends),
        { case svc@ServiceVal(srvVal, x) =>
            nondeterministic[List[Val], Val](
              crossProductList(args map (interp(_, env, Bag()))),
              argVals => interpSends(sends + SendVal(svc, argVals))
            )
        }
      )

    case ServerClosure(impl, env) =>
      ???
  }

  def interpSends(sends: Bag[SendVal]): Res[Val] = {
    val canSend = selectSends(sends)
    if (canSend.isEmpty)
      Set(UnitVal(sends))
    else
      nondeterministic[(ServerVal, Rule, Match), Val](
        canSend,
        {case (srv, r, m) =>
          val (newProg, newEnv, restSends) = fireRule(srv, r, m, sends)
          interp(newProg, newEnv, restSends) + UnitVal(sends)
        })
  }

  def selectSends(sends: Bag[SendVal]): Res[(ServerVal, Rule, Match)] =
    nondeterministic[(ServerVal, Rule), (ServerVal, Rule, Match)](
    (sends map collectRules).flatten,
    { case (srvVal, rule) => matchRule(srvVal, rule.ps, sends) map (x => (srvVal, rule, x)) }
    )

  def matchRule(server: ServerVal, pats: Bag[Pattern], sends: Bag[SendVal]): Res[Match] =
    if (pats.isEmpty)
      Set(Match(Map(), Bag()))
    else {
      val name = pats.head.name
      val params = pats.head.params
      val matchingSends = sends.filter({
        case SendVal(ServiceVal(`server`, `name`), args) => params.size == args.size
        case _ => false
      })
      nondeterministic[SendVal, Match](
        matchingSends,
        s => matchRule(server, pats.tail, sends - s) map (
          p => Match(p.subst ++ (params zip s.args), p.used + s)
          )
      )
    }

  def fireRule(server: ServerVal, rule: Rule, ma: Match, orig: Bag[SendVal]): (Prog, Env, Bag[SendVal]) = {
    val ServerClosure(_, env0) = lookupAddr(server.addr)
    val env = env0 ++ ma.subst + ('this -> server)
    val rest = orig diff ma.used
    (rule.p, env, rest)
  }

  def collectRules(s: SendVal): Bag[(ServerVal, Rule)] = {
    val ServerClosure(impl, _) = lookupAddr(s.rcv.srv.addr)
    impl.rules map ((s.rcv.srv, _))
  }
}