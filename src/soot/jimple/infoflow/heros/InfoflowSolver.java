package soot.jimple.infoflow.heros;

import heros.IFDSTabulationProblem;
import heros.InterproceduralCFG;
import heros.edgefunc.EdgeIdentity;
import heros.solver.CountingThreadPoolExecutor;
import heros.solver.IFDSSolver;
import heros.solver.PathEdge;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.infoflow.data.Abstraction;
import soot.jimple.toolkits.ide.JimpleIFDSSolver;
/**
 * We are subclassing the JimpleIFDSSolver because we need the same executor for both the forward and the backward analysis
 * Also we need to be able to insert edges containing new taint information
 * 
 */
public class InfoflowSolver extends JimpleIFDSSolver<Abstraction, InterproceduralCFG<Unit, SootMethod>> {

	public InfoflowSolver(IFDSTabulationProblem<Unit, Abstraction, SootMethod, InterproceduralCFG<Unit, SootMethod>> problem, boolean dumpResults, CountingThreadPoolExecutor executor) {
		super(problem, dumpResults);
		this.executor = executor;
	}
	
	@Override
	protected CountingThreadPoolExecutor getExecutor() {
		return executor;
	}

	public boolean processEdge(PathEdge<Unit, Abstraction> edge){
		// We are generating a fact out of thin air here. If we have an
		// edge <d1,n,d2>, there need not necessarily be a jump function
		// to <n,d2>.
		if (!jumpFn.forwardLookup(edge.factAtSource(), edge.getTarget()).containsKey(edge.factAtTarget())) {
			jumpFn.addFunction(edge.factAtSource(), edge.getTarget(), edge.factAtTarget(),
					EdgeIdentity.<IFDSSolver.BinaryDomain>v());
			scheduleEdgeProcessing(edge);
			return true;
		}
		return false;
	}
}
