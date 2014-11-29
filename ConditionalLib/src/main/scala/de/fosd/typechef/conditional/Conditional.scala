package de.fosd.typechef.conditional

import de.fosd.typechef.featureexpr.FeatureExprFactory.{False, True}
import de.fosd.typechef.featureexpr.{FeatureExpr, FeatureExprFactory, FeatureModel}


case class Opt[+T](val condition: FeatureExpr, val entry: T) {

    /**
     * compares two optional entry. they are only considered equal if their features are identical (not equivalent).
     * this choice was made for performance reasons; use isEquivalent instead if equivalent formulas are considered equal
     */
    override def equals(x: Any) = x match {
        case Opt(f, e) => (f == condition) && (entry == e)
        case _ => false
    }

    /**
     * alternative version of equals, that considers equivalences among conditions.
     * may require a SAT solver
     */
    def equivalentTo(that: Any) = that match {
        case Opt(f, e) => f.equivalentTo(condition) && (entry == e)
        case _ => false
    }

    //helper function
    def and(f: FeatureExpr) = if (f == null) this else new Opt(condition.and(f), entry)
    def andNot(f: FeatureExpr) = if (f == null) this else new Opt(condition.and(f.not), entry)

    def map[U](f: T => U): Opt[U] = Opt(condition, f(entry))

    @deprecated("feature is a misleading name, use .condition instead", "0.4.0")
    def feature: FeatureExpr = condition
}

//Conditional is either Choice or One
abstract class Conditional[+T] extends Product {

    /**
     * flattens datastructure from a Conditional[T] to a single T with the provided merge function
     * @param f flatten function
     */
    def flatten[U >: T](f: (FeatureExpr, U, U) => U): U

    /**
     * simplify rewrites choices, removing infeasible paths and possibly equal entries.
     * this is an expensive operation reasoning about variability
     *
     * a simplified data structure should not contain infeasible entries, but may still
     * contain duplicate entries under different conditions.
     */
    def simplify = _simplify(True, FeatureExprFactory.empty)
    def simplify(ctx: FeatureExpr) = _simplify(ctx, FeatureExprFactory.empty)
    def simplify(fm: FeatureModel) = _simplify(True, fm)
    protected[conditional] def _simplify(context: FeatureExpr, fm: FeatureModel) = this

    /**
     * apply a function to every alternative value of a Conditional data structure
     */
    def map[U](f: T => U): Conditional[U] = flatMap(x => One(f(x)))

    /**
     * apply a function to every alternative value of a Conditional data structure, propagating
     * the current variability context in the process
     */
    def vmap[U](ctx: FeatureExpr, f: (FeatureExpr, T) => U): Conditional[U] = vflatMap(ctx, (c, x) => One(f(c, x)))

    /**
     * apply a function to every alternative value of a Conditional data structure;
     * may introduce new choices, results are merged into a new conditional
     */
    def flatMap[U](f: T => Conditional[U]): Conditional[U]

    /**
     * apply a function to every alternative value of a Conditional data structure, propagating
     * the current variability context in the process;
     * may introduce new choices, results are merged into a new conditional
     */
    def vflatMap[U](ctx: FeatureExpr, f: (FeatureExpr, T) => Conditional[U]): Conditional[U]

    @deprecated("mapf is misnamed and should be replaced by vmap", "0.4.0")
    def mapf = vmap _
    @deprecated("mapr is misnamed and should be replaced by flatMap", "0.4.0")
    def mapr = flatMap _
    @deprecated("mapfr is misnamed and should be replaced by vflatMap", "0.4.0")
    def mapfr = vflatMap _


    def forall(f: T => Boolean): Boolean
    def exists(f: T => Boolean): Boolean = !this.forall(!f(_))
    def toOptList: List[Opt[T]] = ConditionalLib.flatten(List(Opt(True, this)))

    /**
     * Function toList returns a list with all conditional values of this data structure,
     * each value with a corresponding condition
     */
    def toList: List[(FeatureExpr, T)] = this.toOptList.map(o => (o.condition, o.entry))

    /**
     * returns the condition when predicate f is true
     */
    def when(f: T => Boolean): FeatureExpr
}

case class Choice[+T](condition: FeatureExpr, thenBranch: Conditional[T], elseBranch: Conditional[T]) extends Conditional[T] {
    def flatten[U >: T](f: (FeatureExpr, U, U) => U): U = f(condition, thenBranch.flatten(f), elseBranch.flatten(f))
    override def equals(x: Any) = x match {
        case Choice(f, t, e) => f.equivalentTo(condition) && (thenBranch == t) && (elseBranch == e)
        case _ => false
    }
    override def hashCode = thenBranch.hashCode + elseBranch.hashCode
    protected[conditional] override def _simplify(ctx: FeatureExpr, fm: FeatureModel) = {
        lazy val aa = thenBranch._simplify(ctx and condition, fm)
        lazy val bb = elseBranch._simplify(ctx andNot condition, fm)
        if ((ctx and condition).isContradiction(fm)) bb
        else if ((ctx andNot condition).isContradiction(fm)) aa
        else if (aa == bb) aa
        else if ((thenBranch eq aa) && (elseBranch eq bb)) this //if both subtrees are unchanged, no need to create a new object
        else Choice(condition, aa, bb)
    }

    //flatMap could be implemented through vflatMap, but we prefer to save the operations on featureExpr if possible
    def flatMap[U](f: T => Conditional[U]): Conditional[U] =
        Choice(condition, thenBranch flatMap f, elseBranch flatMap f)
    def vflatMap[U](ctx: FeatureExpr, f: (FeatureExpr, T) => Conditional[U]): Conditional[U] = {
        val newResultA = thenBranch.vflatMap(ctx and condition, f)
        val newResultB = elseBranch.vflatMap(ctx andNot condition, f)
        Choice(condition, newResultA, newResultB)
    }
    def forall(f: T => Boolean): Boolean = thenBranch.forall(f) && elseBranch.forall(f)

    def when(f: T => Boolean): FeatureExpr = (thenBranch.when(f) and condition) or (elseBranch.when(f) andNot condition)

    @deprecated("feature is a misleading name, use .condition instead", "0.4.0")
    def feature: FeatureExpr = condition
}

case class One[+T](value: T) extends Conditional[T] {
    //override def toString = value.toString
    def flatten[U >: T](f: (FeatureExpr, U, U) => U): U = value
    def flatMap[U](f: T => Conditional[U]): Conditional[U] = f(value)
    def vflatMap[U](ctx: FeatureExpr, f: (FeatureExpr, T) => Conditional[U]): Conditional[U] = f(ctx, value)
    def forall(f: T => Boolean): Boolean = f(value)

    def when(f: T => Boolean): FeatureExpr = if (f(value)) True else False
}
