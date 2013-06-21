package de.fosd.typechef.crewrite

import de.fosd.typechef.parser.c._
import de.fosd.typechef.typesystem._
import de.fosd.typechef.featureexpr.FeatureModel
import de.fosd.typechef.conditional.{One, ConditionalLib, Opt}

// implements a simple analysis that checks whether the control-flow statements
// of a function with a non-void return type, always end in a return statement
// https://www.securecoding.cert.org/confluence/display/seccode/MSC37-C.+Ensure+that+control+never+reaches+the+end+of+a+non-void+function
// MSC37-C
class CFGNonVoidFunction(env: ASTEnv, fm: FeatureModel, ts: CTypeSystemFrontend with CTypeCache) extends IntraCFG {
    def cfgReturn(f: FunctionDef): List[Opt[AST]] = {
        // get all predecessor elements of the function and look for non-return statements
        val wlist: List[Opt[AST]] = pred(f, fm, env)
        var res: List[Opt[AST]] = List()

        val ftypes = ts.lookupFunType(f)

        for (litem <- wlist) {
            litem match {
                case Opt(_, ReturnStatement(_)) =>
                // feature expr in Opt node is not necessarily complete
                case o@Opt(_, x: AST) => {
                    findPriorASTElem[ReturnStatement](x, env) match {
                        case Some(_) =>
                        case None => {
                            val ftype = ConditionalLib.findSubtree[CType](env.featureExpr(x), ftypes)
                            ftype match {
                                case One(CFunction(_, ret)) if ! ret.isInstanceOf[CVoid] => res ::= o
                                case _ =>
                            }
                        }
                    }
                }
            }
        }

        // filter result elements of which the successor is not the function definition itself
        res.filterNot({ x => succ(x.entry, fm, env).contains(f) })
    }
}
