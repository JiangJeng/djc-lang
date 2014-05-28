package djc.lang.sem.nondeterm_5_parallel

import util.Bag
import djc.lang.sem.Substitution
import djc.lang.Syntax._

import Router._

object Data {
  type Env = Map[Symbol, Value]

  abstract class Value {
    def toNormalizedProg: Exp
    def toNormalizedResolvedProg: Exp
  }

  case class ServerClosure(srv: ServerImpl, env: Env) {
    private[this] var addr_ : ServerAddr = null
    def addr_=(a: Router.Addr) = {
      addr_ = ServerAddr(a)
    }
    def addr = addr_
    def normalize = env.foldLeft(srv) {
      case (srv1, (x, value)) => Substitution(x, value.toNormalizedResolvedProg)(srv1).asInstanceOf[ServerImpl]
    }
  }

  trait IServerVal extends Value {
    def addr: ServerAddr
  }

  trait IServiceVal {
    def srv: IServerVal
    def x: Symbol
  }

  trait ISendVal {
    def rcv: IServiceVal
    def args: List[Value]
    def toNormalizedResolvedProg: Send
  }

  type Servers = Map[Router.Addr, Bag[ISendVal]]
  val emptyServers: Servers = Map()
  def sendToServer(servers: Servers, addr: Router.Addr, sv: ISendVal): Servers = {
    servers + (addr -> (servers.getOrElse(addr, Bag()) + sv))
  }
}
import Data._

class Data(router: Router) {
  case object UnitVal extends Value {
    def toNormalizedProg = Par()
    def toNormalizedResolvedProg = Par()
  }

  case class ServerVal(addr: ServerAddr) extends Value with IServerVal {
    def toNormalizedProg = addr
    def toNormalizedResolvedProg = router.lookupAddr(addr).normalize
  }

  case class ServiceVal(srv: ServerVal, x: Symbol) extends Value with IServiceVal {
    def toNormalizedProg = ServiceRef(srv.toNormalizedProg, x)
    def toNormalizedResolvedProg = ServiceRef(srv.toNormalizedResolvedProg, x)
  }


  case class Match(subst: Map[Symbol, Value], used: Bag[ISendVal])

  case class SendVal(rcv: ServiceVal, args: List[Value]) extends ISendVal {
    def toNormalizedResolvedProg = Send(rcv.toNormalizedResolvedProg, args map (_.toNormalizedResolvedProg))
  }


  case class ExpClosure(p: Exp, env: Env) extends Exp
}
