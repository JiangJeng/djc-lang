package djc.lang.sem.nondeterm_5_parallel

import Data._

/**
 * Created by seba on 01/04/14.
 */
object Router {
  type Addr = String

  var routeTable: collection.mutable.Map[Addr, ServerClosure] = null

  var addrNum = 0
  val addrPrefix = "Server@"
  def nextAddr: Addr = {
    addrNum += 1
    val addr = addrPrefix + addrNum
    if (!routeTable.isDefinedAt(addr))
      addr
    else
      nextAddr
  }

  def registerServer(s: ServerClosure): Addr = {
    val addr = nextAddr
    routeTable += (addr -> s)
    addr
  }

  def lookupAddr(addr: Addr): ServerClosure = routeTable(addr)
}