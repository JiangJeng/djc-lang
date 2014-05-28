package djc.lang.sem.nondeterm_4_grouped

import scala.language.postfixOps
import util.Bag
import djc.lang.sem.{Crossproduct, AbstractSemantics}
import djc.lang.Syntax._
import Data._
import Router._



class Semantics {
  val router = new Router
  val data = new Data(router)
  import data._

  def newInstance() = new Inner

  class Inner extends AbstractSemantics[(Value, Servers)] {
    import Crossproduct._


    def normalizeVal(v: Val) = v match {
      case (UnitVal, servers) =>
        val sends = servers.values.toSet.foldLeft(Bag[ISendVal]()) {
          case (b, b1) => b ++ b1
        }
        sends.map(sval => sval.toNormalizedResolvedProg)
    }

    override def interp(p: Exp) =
    {
      val res = interp(p, Map(), Map())
      res filter {
        case (v, ss) => interp(v.toNormalizedProg, Map(), ss).size == 1
      }
    }

    def interp(p: Exp, env: Env, servers: Servers): Res[Val] = p match {
      case Var(y) if env.isDefinedAt(y) =>
        Set((env(y), emptyServers))

      case addr@ServerAddr(_) =>
        Set((ServerVal(addr), emptyServers))

      case s@ServerImpl(rules) =>
        val raddr = router.registerServer(ServerClosure(s, env))
        val addr = ServerAddr(raddr)
        val nuServers = Map(raddr -> Bag[ISendVal]())
        Set((ServerVal(addr), nuServers))

      case ServiceRef(srv, x) =>
        nondeterministic[Val, Val](
        interp(srv, env, servers), {
          case (sval@ServerVal(addr), nuServers) =>
            //val ServerClosure(impl, _) = lookupAddr(addr)
            //   if impl.rules.exists(_.ps.exists(_.name == x)) => //TODO add this check back once we have good solution for primitive services
            Set((ServiceVal(sval, x), nuServers))

          //   case ServerVal(impl, _) => throw SemanticException(s"service $x not defined in server $impl")
        }
        )

      case Par(ps) =>
        nondeterministic[Servers, Val](
          crossProductMap(ps map (interp(_, env, servers) map {
            case (UnitVal, nuServers) => nuServers
          })),
          nuServers => interpSends(servers &&& nuServers))

      case Seq(Nil) =>
        Set((UnitVal, servers))
      case Seq(p :: Nil) =>
        interp(p, env, servers)
      case Seq(p :: ps) =>
        nondeterministic[Val, Val](
        interp(p, env, servers), {
          case (UnitVal, nuservers) => interp(Seq(ps), env, servers &&& nuservers)
        }
        )


      case Send(rcv, args) =>
        nondeterministic[Val, Val](
        interp(rcv, env, servers), {
          case (svc@ServiceVal(srvVal, x), nuServers) =>
            val addr = ServerAddr.unapply(srvVal.addr).get
            val s = for (l <- crossProductList(args map (interp(_, env, servers)));
                         (values, maps) = l.map {
                           case (v, ss) => (v, ss)
                         }.unzip)
            yield (values, maps.foldLeft(emptyServers) {
                case (m, m1) => m &&& m1
              })

            nondeterministic[(List[Value], Servers), Val](s, {
              case (argVals, nuServers1) =>
                val srvs = List(servers, nuServers, nuServers1).reduce(_ &&& _)
                interpSends(sendToServer(srvs, addr, SendVal(svc, argVals)))
            }
            )
        }
        )
    }

    def interpSends(servers: Servers): Res[Val] = {
      val canSend = selectServerSends(servers)
      if (canSend.isEmpty)
        Set((UnitVal, servers))
      else
        nondeterministic[(IServerVal, Rule, Match), Val](
        canSend, {
          case (srv, r, m) =>
            val (newProg, newEnv, nuServers) = fireRule(srv, r, m, servers)
            interp(newProg, newEnv, nuServers) + ((UnitVal, servers))
        })
    }

    def selectServerSends(servers: Servers): Res[(IServerVal, Rule, Match)] =
      servers.values.toSet.map((bag: Bag[ISendVal]) => selectSends(bag)).flatten //TODO is a set really adequate? what about bag?

    def selectSends(sends: Bag[ISendVal]): Res[(IServerVal, Rule, Match)] =
      nondeterministic[(IServerVal, Rule), (IServerVal, Rule, Match)](
      (sends map collectRules).flatten, {
        case (srvVal, rule) => matchRule(srvVal, rule.ps, sends) map (x => (srvVal, rule, x))
      }
      )

    def matchRule(server: IServerVal, pats: Bag[Pattern], sends: Bag[ISendVal]): Res[Match] =
      if (pats.isEmpty)
        Set(Match(Map(), Bag()))
      else {
        val name = pats.head.name
        val params = pats.head.params
        val matchingSends = sends.filter({
          case SendVal(ServiceVal(`server`, `name`), args) => params.size == args.size
          case _ => false
        })
        nondeterministic[ISendVal, Match](
          matchingSends,
          s => matchRule(server, pats.tail, sends - s) map (
            p => Match(p.subst ++ (params zip s.args), p.used + s)
            )
        )
      }

    def fireRule(server: IServerVal, rule: Rule, ma: Match, orig: Servers): (Exp, Env, Servers) = {
      val ServerClosure(_, env0) = router.lookupAddr(server.addr)
      val env = env0 ++ ma.subst + ('this -> server)

      val raddr = ServerAddr.unapply(server.addr).get
      val queue = orig(raddr)
      val newQueue = queue -- ma.used
      val rest = orig.updated(raddr, newQueue)

      (rule.p, env, rest)
    }

    def collectRules(s: ISendVal): Bag[(IServerVal, Rule)] = {
      val ServerClosure(impl, _) = router.lookupAddr(s.rcv.srv.addr)
      impl.rules map ((s.rcv.srv, _))
    }
  }
}