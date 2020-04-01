package scalax.collection
package mutable

import java.io.{ObjectInputStream, ObjectOutputStream}

import scala.collection.mutable.{ArrayBuffer, Set => MutableSet}
import scala.reflect.ClassTag
import scala.math.max

import scalax.collection.{Graph => CommonGraph, GraphLike => CommonGraphLike}
import scalax.collection.GraphEdge.EdgeLike
import scalax.collection.generic.{GraphCompanion, MutableGraphCompanion}
import scalax.collection.config._

trait AbstractBuilder[N, E <: EdgeLike[N]] extends Growable[N, E] {

  /** If an inner edge equaling to `edge` is present in this graph, it is replaced
    * by `edge`, otherwise `edge` will be inserted.
    * This is useful if non-key parts of an immutable edge are to be modified.
    * @return `true` if `edge` has been inserted, `false` if it has been replaced.
    */
  def upsert(edge: E): Boolean

  def clear(): Unit
}

abstract protected[collection] class BuilderImpl[N, E <: EdgeLike[N]](implicit edgeT: ClassTag[E], config: GraphConfig)
    extends AbstractBuilder[N, E] {

  protected val nodes = new ArrayBuffer[N](config.orderHint)
  protected val edges = new ArrayBuffer[E](
    config match {
      case CoreConfig(order, adjList) => order * max(adjList.initialCapacity, 16)
      case _                          => config.orderHint * 32
    }
  )

  def add(node: N): Boolean = (nodes contains node) tap (_ => this += node)
  def add(edge: E): Boolean = (edges contains edge) tap (_ => this += edge)

  def upsert(edge: E): Boolean = (edges contains edge) pipe { found =>
    if (found) edges -= edge
    edges += edge
    !found
  }

  def +=(node: N): this.type = { nodes += node; this }
  def +=(edge: E): this.type = { edges += edge; this }

  def clear(): Unit = {
    nodes.clear
    edges.clear
  }
}

class Builder[N, E <: EdgeLike[N], +CC[N, E <: EdgeLike[N]] <: CommonGraphLike[N, E, CC] with CommonGraph[N, E]](
    companion: GraphCompanion[CC])(implicit edgeT: ClassTag[E], config: GraphConfig)
    extends BuilderImpl[N, E] {

  def result: CC[N, E] = companion.from(nodes, edges)(edgeT, config.asInstanceOf[companion.Config])
}

/** Trait with common mutable Graph methods.
  *
  * @author Peter Empen
  */
trait GraphLike[N, E <: EdgeLike[N], +This[X, Y <: EdgeLike[X]] <: GraphLike[X, Y, This] with Graph[X, Y]]
    extends CommonGraphLike[N, E, This]
    with GraphOps[N, E, This]
    /* TODO
    with EdgeOps[N, E, This]
     */
    with Mutable {
  this: // This[N,E] => see https://youtrack.jetbrains.com/issue/SCL-13199
  This[N, E] with GraphLike[N, E, This] with Graph[N, E] =>

  override def clone: This[N, E] = companion.from[N, E](nodes.toOuter, edges.toOuter)

  type NodeT <: InnerNode
  trait InnerNode extends super.InnerNode { // TODO with InnerNodeOps {
    this: NodeT =>
  }

  type NodeSetT <: NodeSet
  trait NodeSet extends MutableSet[NodeT] with super.NodeSet {
    @inline final override def -=(node: NodeT): this.type = { remove(node); this }
    override def remove(node: NodeT): Boolean             = subtract(node, true, minus, minusEdges)

    /** removes all incident edges of `node` from the edge set leaving the node set unchanged.
      *
      * @param node the node the incident edges of which are to be removed from the edge set.
      */
    protected def minusEdges(node: NodeT): Unit

    override def diff(that: AnySet[NodeT]) = this -- that
    override def clear(): Unit             = this foreach -=
  }

  type EdgeSetT <: EdgeSet
  trait EdgeSet extends MutableSet[EdgeT] with super.EdgeSet with Compat.AddSubtract[EdgeT, EdgeSet] {
    @inline final def addOne(edge: EdgeT)      = { add(edge); this }
    @inline final def subtractOne(edge: EdgeT) = { remove(edge); this }

    /** Same as `upsert` at graph level. */
    def upsert(edge: EdgeT): Boolean
    def removeWithNodes(edge: EdgeT): Boolean
  }

  /** Adds the given node if not yet present and returns it as an inner node.
    *
    * @param node the node to add.
    * @return inner node containing the added node.
    */
  @inline final def addAndGet(node: N): NodeT = { add(node); find(node).get }

  @inline final def +=(node: N): this.type = { add(node); this }
  @inline final def +=(edge: E): this.type = { this add edge; this }

  /** Adds the given edge if not yet present and returns it as an inner edge.
    *
    * @param edge the edge to add.
    * @return the inner edge containing the added edge.
    */
  @inline final def addAndGet(edge: E): EdgeT = { add(edge); find(edge).get }

  @inline final def remove(node: N): Boolean = nodes find node exists (nodes remove _)
  @inline final def -=(node: N): this.type   = { remove(node); this }

  @inline final def remove(edge: E): Boolean = edges remove InnerEdge(edge)
  @inline final def -=(edge: E): this.type   = { remove(edge); this }
}

/** The main trait for mutable graphs bundling the functionality of traits concerned with
  * specific aspects.
  *
  * @tparam N the type of the nodes (vertices) in this graph.
  * @tparam E the kind of the edges in this graph.
  * @author Peter Empen
  */
trait Graph[N, E <: EdgeLike[N]] extends CommonGraph[N, E] with GraphLike[N, E, Graph] {
  override def empty: Graph[N, E] = Graph.empty[N, E]
}

/** The main companion object for mutable graphs.
  *
  * @author Peter Empen
  */
object Graph extends MutableGraphCompanion[Graph] {

  def empty[N, E <: EdgeLike[N]](implicit edgeT: ClassTag[E], config: Config = defaultConfig): Graph[N, E] =
    new DefaultGraphImpl[N, E]()(edgeT, config)

  override def from[N, E <: EdgeLike[N]](nodes: Traversable[N] = Nil, edges: Traversable[E])(
      implicit edgeT: ClassTag[E],
      config: Config = defaultConfig): Graph[N, E] =
    DefaultGraphImpl.from[N, E](nodes, edges)(edgeT, config)

  override def from[N, E[X] <: EdgeLike[X]](edges: Traversable[E[N]])(implicit edgeT: ClassTag[E[N]]) =
    DefaultGraphImpl.from[N, E[N]](Nil, edges)(edgeT, defaultConfig)
}

@SerialVersionUID(74L)
class DefaultGraphImpl[N, E <: EdgeLike[N]](iniNodes: Traversable[N] = Set[N](), iniEdges: Traversable[E] = Set[E]())(
    implicit override val edgeT: ClassTag[E],
    override val config: DefaultGraphImpl.Config with AdjacencyListArrayConfig)
    extends Graph[N, E]
    with AdjacencyListGraph[N, E, DefaultGraphImpl]
    /* TODO
    with GraphTraversalImpl[N, E]
     */ {
  final override val companion = DefaultGraphImpl
  protected type Config = DefaultGraphImpl.Config

  type NodeSetT = NodeSet
  @inline final protected def newNodeSet: NodeSetT = new NodeSet
  @transient private[this] var _nodes: NodeSetT    = newNodeSet
  @inline final override def nodes                 = _nodes

  @transient private[this] var _edges: EdgeSetT = new EdgeSet
  @inline final override def edges              = _edges

  initialize(iniNodes, iniEdges)

  override protected[this] def newBuilder          = new Builder[N, E, DefaultGraphImpl](DefaultGraphImpl)
  final override def empty: DefaultGraphImpl[N, E] = DefaultGraphImpl.empty[N, E]
  final override def clone: this.type              = super.clone.asInstanceOf[this.type]

  @SerialVersionUID(7370L)
  final protected class NodeBase(val outer: N, hints: ArraySet.Hints) extends InnerNodeImpl(outer, hints)
// TODO      with    InnerNodeTraversalImpl

  type NodeT = NodeBase

  @inline final protected def newNodeWithHints(n: N, h: ArraySet.Hints) = new NodeT(n, h)

  private def writeObject(out: ObjectOutputStream): Unit = serializeTo(out)

  private def readObject(in: ObjectInputStream): Unit = {
    _nodes = newNodeSet
    _edges = new EdgeSet
    initializeFrom(in, _nodes, _edges)
  }
}

object DefaultGraphImpl extends MutableGraphCompanion[DefaultGraphImpl] {

  def empty[N, E <: EdgeLike[N]](implicit edgeT: ClassTag[E], config: Config = defaultConfig) =
    new DefaultGraphImpl[N, E]()(edgeT, config)

  override def from[N, E <: EdgeLike[N]](nodes: Traversable[N], edges: Traversable[E])(implicit edgeT: ClassTag[E],
                                                                                       config: Config = defaultConfig) =
    new DefaultGraphImpl[N, E](nodes, edges)(edgeT, config)

  override def from[N, E[X] <: EdgeLike[X]](edges: Traversable[E[N]])(implicit edgeT: ClassTag[E[N]]) =
    new DefaultGraphImpl[N, E[N]](Nil, edges)(edgeT, defaultConfig)
}
