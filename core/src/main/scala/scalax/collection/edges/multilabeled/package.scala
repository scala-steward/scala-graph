package scalax.collection.edges

import scalax.collection.generic
import scalax.collection.generic.Edge

package object multilabeled {

  /** Factory shortcut for weighted edges that can be used like `a ~ b %% w`.
    */
  implicit final class MultiWUnDiEdgeFactory[N](val e: UnDiEdge[N]) extends AnyVal {
    def %%(weight: Double): WUnDiEdge[N] = WUnDiEdge[N](e.source, e.target, weight)
  }

  /** Factory shortcut for weighted edges that can be used like `a ~> b %% w`.
    */
  implicit final class MultiWDiEdgeFactory[N](val e: DiEdge[N]) extends AnyVal {
    def %%(weight: Double): WDiEdge[N] = WDiEdge[N](e.source, e.target, weight)
  }

  val %% = generic.%

  type GenericEdgeMapper[+CC[X] <: Edge[X]] = generic.GenericEdgeMapper[CC]
}
