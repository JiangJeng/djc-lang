package djc.lang.lib.combinators.loadbalance

import djc.lang.TypedSyntax._
import djc.lang.TypedSyntaxDerived.{TThunk => _, _}
import djc.lang.base.Integer._
import djc.lang.lib.combinators._
import djc.lang.typ.Types._

object MkLoadAware {
  def apply(t1: Type, t2: Type) = TApp(combinator, t1, t2)

  val TInternal = TUniv('A, TUniv('W, TWorker('A),
    TSrvRep('init -> ?(), 'work -> TFun(TThunk('A) -> 'A), 'instance -> ?(TSrv('W)),
            'getLoad -> ?(?(TInteger)), 'done -> ?(), 'load -> ?(TInteger))))


  val combinator = TAbs('A, 'W << TWorker('A)) {
    ServerImpl {
      Rule('make?('worker -> 'W, 'k -> ?(TLaWorker('A)))) {
        Let('laWorker, TLaWorker('A),
          ServerImpl (
            Rule('init?()) {
              Let(TInternal('A, 'W))('w, TSrv('W), SpawnLocal('worker)) {
                'w~>'init!!() && 'this~>'instance!!'w && 'this~>'load!!0
              }
            },
            Rule('work?('thunk -> TThunk('A), 'k -> ?('A)),
                 'instance?('w -> TSrv('W)),
                 'load?('n -> TInteger)) {
                  ('this~>'load!!('n + 1) && 'this~>'instance!!'w
                   && Letk(TInternal('A,'W))('res, 'A, 'w~>'work!!'thunk) { 'k!!'res && 'this~>'done!!()  })
            },
            Rule('done?(), 'load?('n -> TInteger)) {
              'this~>'load!!('n - 1)
            },
            Rule('getLoad?('r -> ?(TInteger)), 'load?('n -> TInteger)) {
              'r!!'n && 'this~>'load!!'n
            })) {
          'k!!'laWorker
        }
      }
    }
  }
}