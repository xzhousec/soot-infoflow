package soot.jimple.infoflow;

import heros.FlowFunction;
import heros.FlowFunctions;
import heros.InterproceduralCFG;
import heros.flowfunc.Identity;
import heros.flowfunc.KillAll;
import heros.solver.PathEdge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import soot.Local;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.ArrayRef;
import soot.jimple.AssignStmt;
import soot.jimple.CaughtExceptionRef;
import soot.jimple.Constant;
import soot.jimple.DefinitionStmt;
import soot.jimple.IdentityStmt;
import soot.jimple.InstanceFieldRef;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.ReturnStmt;
import soot.jimple.StaticFieldRef;
import soot.jimple.Stmt;
import soot.jimple.ThrowStmt;
import soot.jimple.infoflow.data.Abstraction;
import soot.jimple.infoflow.data.AbstractionWithPath;
import soot.jimple.infoflow.data.AccessPath;
import soot.jimple.infoflow.heros.InfoflowSolver;
import soot.jimple.infoflow.source.DefaultSourceSinkManager;
import soot.jimple.infoflow.source.ISourceSinkManager;
import soot.jimple.infoflow.util.BaseSelector;
import soot.jimple.toolkits.ide.icfg.JimpleBasedBiDiICFG;

public class InfoflowProblem extends AbstractInfoflowProblem {

	private InfoflowSolver bSolver; 
	private final ISourceSinkManager sourceSinkManager;

    private final Logger logger = LoggerFactory.getLogger(getClass());

	/**
	 * Computes the taints produced by a taint wrapper object
	 * @param iStmt The call statement the taint wrapper shall check for well-
	 * known methods that introduce black-box taint propagation
	 * @param source The taint source
	 * @return The taints computed by the wrapper
	 */
	private Set<Abstraction> computeWrapperTaints
			(final Stmt iStmt,
			Abstraction source) {
		Set<Abstraction> res = new HashSet<Abstraction>();
		if(taintWrapper == null)
			return Collections.emptySet();
		
		if (!source.getAccessPath().isStaticFieldRef())
			if(iStmt.getInvokeExpr() instanceof InstanceInvokeExpr) {
				InstanceInvokeExpr iiExpr = (InstanceInvokeExpr) iStmt.getInvokeExpr();
				boolean found = iiExpr.getBase().equals(source.getAccessPath().getPlainValue());
				if (!found)
					for (Value param : iiExpr.getArgs())
						if (source.getAccessPath().getPlainValue().equals(param)) {
							found = true;
							break;
				}
				if (!found)
					return Collections.emptySet();
			}
			
		Set<AccessPath> vals = taintWrapper.getTaintsForMethod(iStmt, source.getAccessPath());
		if(vals != null) {
			for (AccessPath val : vals) {
				Abstraction newAbs = source.deriveNewAbstraction(val);
				if (pathTracking == PathTrackingMethod.ForwardTracking)
					((AbstractionWithPath) newAbs).addPathElement(iStmt);
				res.add(newAbs);

				// If the taint wrapper taints the base object (new taint), this must be propagated
				// backwards as there might be aliases for the base object
				if(iStmt.getInvokeExpr() instanceof InstanceInvokeExpr) {
					InstanceInvokeExpr iiExpr = (InstanceInvokeExpr) iStmt.getInvokeExpr();
					if(iiExpr.getBase().equals(newAbs.getAccessPath().getPlainValue())
								|| newAbs.getAccessPath().isStaticFieldRef()) {
						Abstraction bwAbs = source.deriveNewAbstraction(val, false);
						for (Unit predUnit : interproceduralCFG().getPredsOf(iStmt))
							bSolver.processEdge(new PathEdge<Unit, Abstraction>(bwAbs, predUnit, bwAbs));
					}
				}
			}
		}

		return res;
	}
	
	@Override
	public FlowFunctions<Unit, Abstraction, SootMethod> createFlowFunctionsFactory() {
		return new FlowFunctions<Unit, Abstraction, SootMethod>() {

			/**
			 * Creates a new taint abstraction for the given value
			 * @param src The source statement from which the taint originated
			 * @param targetValue The target value that shall now be tainted
			 * @param source The incoming taint abstraction from the source
			 * @param taintSet The taint set to which to add all newly produced
			 * taints
			 */
			private void addTaintViaStmt
					(final Unit src,
					final Value targetValue,
					Abstraction source,
					Set<Abstraction> taintSet,
					boolean cutFirstField) {
				// Keep the original taint
				taintSet.add(source);
				
				// Strip array references to their respective base
				Value baseTarget = targetValue;
				if (targetValue instanceof ArrayRef)
					baseTarget = ((ArrayRef) targetValue).getBase();

				// also taint the target of the assignment
				Abstraction newAbs = source.deriveNewAbstraction(baseTarget, cutFirstField, src);
				if (pathTracking == PathTrackingMethod.ForwardTracking)
					((AbstractionWithPath) newAbs).addPathElement(src);
				taintSet.add(newAbs);
				
				// call backwards-check for heap-objects only
				if (triggerInaktiveTaintOrReverseFlow(targetValue, source) && source.isAbstractionActive())
					// If we overwrite the complete local, there is no need for
					// a backwards analysis
					if (!(targetValue.equals(newAbs.getAccessPath().getPlainValue())
							&& newAbs.getAccessPath().isLocal())) {
						Abstraction bwAbs = newAbs.deriveInactiveAbstraction();
						for (Unit predUnit : interproceduralCFG().getPredsOf(src)) {
							bSolver.processEdge(new PathEdge<Unit, Abstraction>(bwAbs.getAbstractionFromCallEdge(), predUnit, bwAbs));
//							if (bSolver.processEdge(new PathEdge<Unit, Abstraction>(bwAbs.getAbstractionFromCallEdge(), predUnit, bwAbs)))
//								System.out.println(interproceduralCFG().getMethodOf(src).getActiveBody());
						}
				}
			}

			@Override
			public FlowFunction<Abstraction> getNormalFlowFunction(final Unit src, final Unit dest) {
				// If we compute flows on parameters, we create the initial
				// flow fact here
				if (src instanceof IdentityStmt) {
					final IdentityStmt is = (IdentityStmt) src;
					return new FlowFunction<Abstraction>() {

						@Override
						public Set<Abstraction> computeTargets(Abstraction source) {
							if (stopAfterFirstFlow && !results.isEmpty())
								return Collections.emptySet();
							
							Set<Abstraction> res = new HashSet<Abstraction>();
							boolean addOriginal = true;
							if (is.getRightOp() instanceof CaughtExceptionRef) {
								if (source.getExceptionThrown()) {
									res.add(source.deriveNewAbstractionOnCatch(is.getLeftOp(), is));
									addOriginal = false;
								}
							}

							if (addOriginal)
								res.add(source);
							
							if (sourceSinkManager.isSource(is, interproceduralCFG())) {
								Abstraction abs;
								if (pathTracking != PathTrackingMethod.NoTracking)
									abs = new AbstractionWithPath(is.getLeftOp(),
										is.getRightOp(),
										is, false, true, is).addPathElement(is);
								else
									abs = new Abstraction(is.getLeftOp(),
										is.getRightOp(), is, false, true, is);
								abs.setZeroAbstraction(source.getZeroAbstraction());
								res.add(abs);
							}
							
							return res;
						}
					};

				}

				// taint is propagated with assignStmt
				else if (src instanceof AssignStmt) {
					final AssignStmt assignStmt = (AssignStmt) src;
					Value right = assignStmt.getRightOp();
					Value left = assignStmt.getLeftOp();

					final Value leftValue = BaseSelector.selectBase(left, true);
					final Set<Value> rightVals = BaseSelector.selectBaseList(right, true);

					return new FlowFunction<Abstraction>() {

						 @Override
						public Set<Abstraction> computeTargets(Abstraction source) {
							if (stopAfterFirstFlow && !results.isEmpty())
								return Collections.emptySet();
							
							boolean addLeftValue = false;
							boolean cutFirstField = false;
							Set<Abstraction> res = new HashSet<Abstraction>();
							
							// shortcuts:
							// on NormalFlow taint cannot be created
							if (source.equals(zeroValue)) {
								return Collections.emptySet();
							}
							Abstraction newSource;
							if (!source.isAbstractionActive() && (src.equals(source.getActivationUnit()) || src.equals(source.getActivationUnitOnCurrentLevel()))){
								newSource = source.getActiveCopy(false);
							}else{
								newSource = source;
							}
							
							for (Value rightValue : rightVals) {
								// check if static variable is tainted (same name, same class)
								//y = X.f && X.f tainted --> y, X.f tainted
								if (newSource.getAccessPath().isStaticFieldRef() && rightValue instanceof StaticFieldRef) {
									StaticFieldRef rightRef = (StaticFieldRef) rightValue;
									if (newSource.getAccessPath().getFirstField().equals(rightRef.getField())) {
										addLeftValue = true;
										cutFirstField = true;
									}
								}
								// if both are fields, we have to compare their fieldName via equals and their bases
								//y = x.f && x tainted --> y, x tainted
								//y = x.f && x.f tainted --> y, x tainted
								else if (rightValue instanceof InstanceFieldRef) {								
									InstanceFieldRef rightRef = (InstanceFieldRef) rightValue;
									Local rightBase = (Local) rightRef.getBase();
									Local sourceBase =  newSource.getAccessPath().getPlainLocal();
									if (rightBase.equals(sourceBase)) {
										if (newSource.getAccessPath().isInstanceFieldRef()) {
											if (rightRef.getField().equals(newSource.getAccessPath().getFirstField())) {
												addLeftValue = true;
												cutFirstField = true;
											}
										}
										else
											addLeftValue = true;
									}
								}
								// indirect taint propagation:
								// if rightvalue is local and source is instancefield of this local:
								// y = x && x.f tainted --> y.f, x.f tainted
								// y.g = x && x.f tainted --> y.g.f, x.f tainted
								else if (rightValue instanceof Local && newSource.getAccessPath().isInstanceFieldRef()) {
									Local base = newSource.getAccessPath().getPlainLocal();
									if (rightValue.equals(base)) {
										addLeftValue = true;
										/*
										if (leftValue instanceof Local) {
											if (pathTracking == PathTrackingMethod.ForwardTracking)
												res.add(((AbstractionWithPath) newSource.deriveNewAbstraction
														(newSource.getAccessPath().copyWithNewValue(leftValue), assignStmt)).addPathElement(src));
											else
												res.add(newSource.deriveNewAbstraction(newSource.getAccessPath().copyWithNewValue(leftValue), assignStmt));												
											res.add(newSource);
											return res;
										}
										else {
											addLeftValue = true;
										}
										*/
									}
								}
								//y = x[i] && x tainted -> x, y tainted
								else if (rightValue instanceof ArrayRef) {
									Local rightBase = (Local) ((ArrayRef) rightValue).getBase();
									if (rightBase.equals(newSource.getAccessPath().getPlainValue()))
										addLeftValue = true;
								}
								// generic case, is true for Locals, ArrayRefs that are equal etc..
								//y = x && x tainted --> y, x tainted
								else if (rightValue.equals(newSource.getAccessPath().getPlainValue())) {
									addLeftValue = true;
								}
							}

							// if one of them is true -> add leftValue
							if (addLeftValue) {
								if (sourceSinkManager.isSink(assignStmt, interproceduralCFG())) {
									if (pathTracking != PathTrackingMethod.NoTracking)
										results.addResult(leftValue, assignStmt,
												newSource.getSource(),
												newSource.getSourceContext(),
												((AbstractionWithPath) newSource).getPropagationPath(),
												assignStmt);
									else
										results.addResult(leftValue, assignStmt,
												newSource.getSource(), newSource.getSourceContext());
								}
								if(triggerInaktiveTaintOrReverseFlow(leftValue, newSource) || newSource.isAbstractionActive())
									addTaintViaStmt(src, leftValue, newSource, res, cutFirstField);
								res.add(newSource);
								return res;
							}
							
							// If we have propagated taint, we have returned from this method by now
							
							//if leftvalue contains the tainted value -> it is overwritten - remove taint:
							//but not for arrayRefs:
							// x[i] = y --> taint is preserved since we do not distinguish between elements of collections 
							//because we do not use a MUST-Alias analysis, we cannot delete aliases of taints 
							if (assignStmt.getLeftOp() instanceof ArrayRef)
								return Collections.singleton(newSource);
							
							if(newSource.getAccessPath().isInstanceFieldRef()) {
								//x.f = y && x.f tainted --> no taint propagated
								if (leftValue instanceof InstanceFieldRef) {
									InstanceFieldRef leftRef = (InstanceFieldRef) leftValue;
									if (leftRef.getBase().equals(newSource.getAccessPath().getPlainValue())) {
										if (leftRef.getField().equals(newSource.getAccessPath().getFirstField())) {
//											if(newSource.isAbstractionActive()){
												return Collections.emptySet();
//											}
										}
										
									}
								}
								//x = y && x.f tainted -> no taint propagated
								else if (leftValue instanceof Local){
									if (leftValue.equals(newSource.getAccessPath().getPlainValue())) {
//										if(newSource.isAbstractionActive()){
											return Collections.emptySet();
//										}
									}
								}	
							}
							//X.f = y && X.f tainted -> no taint propagated
							else if(newSource.getAccessPath().isStaticFieldRef()){
								if(leftValue instanceof StaticFieldRef && ((StaticFieldRef)leftValue).getField().equals(newSource.getAccessPath().getFirstField())){
//									if(newSource.isAbstractionActive()){
										return Collections.emptySet();
//									}
								}
								
							}
							//when the fields of an object are tainted, but the base object is overwritten
							// then the fields should not be tainted any more
							//x = y && x.f tainted -> no taint propagated
							else if(newSource.getAccessPath().isLocal() && leftValue.equals(newSource.getAccessPath().getPlainValue())){
//								if(newSource.isAbstractionActive()){
									return Collections.emptySet();
//								}
							}
							//nothing applies: z = y && x tainted -> taint is preserved
							res.add(newSource);
							return res;
						}
					};
				}
				// for unbalanced problems, return statements correspond to
				// normal flows, not return flows, because there is no return
				// site we could jump to
				else if (src instanceof ReturnStmt) {
					final ReturnStmt returnStmt = (ReturnStmt) src;
					return new FlowFunction<Abstraction>() {

						@Override
						public Set<Abstraction> computeTargets(Abstraction source) {
							if (stopAfterFirstFlow && !results.isEmpty())
								return Collections.emptySet();

							if (returnStmt.getOp().equals(source.getAccessPath().getPlainValue()) && sourceSinkManager.isSink(returnStmt, interproceduralCFG())) {
								if (pathTracking != PathTrackingMethod.NoTracking)
									results.addResult(returnStmt.getOp(), returnStmt,
											source.getSource(),
											source.getSourceContext(),
											((AbstractionWithPath) source).getPropagationPath(),
											returnStmt);
								else
									results.addResult(returnStmt.getOp(), returnStmt,
											source.getSource(), source.getSourceContext());
							}

							return Collections.singleton(source);
						}
					};
				}
				else if (src instanceof ThrowStmt) {
					final ThrowStmt throwStmt = (ThrowStmt) src;
					return new FlowFunction<Abstraction>() {

						@Override
						public Set<Abstraction> computeTargets(Abstraction source) {
							if (stopAfterFirstFlow && !results.isEmpty())
								return Collections.emptySet();
							
							if (throwStmt.getOp().equals(source.getAccessPath().getPlainLocal()))
								return Collections.singleton(source.deriveNewAbstractionOnThrow());
							return Collections.singleton(source);
						}
					};
				}
				return Identity.v();
			}

			@Override
			public FlowFunction<Abstraction> getCallFlowFunction(final Unit src, final SootMethod dest) {
                if (!dest.isConcrete()){
                    logger.debug("Call skipped because target has no body: {} -> {}", src, dest);
                    return KillAll.v();
                }

				final Stmt stmt = (Stmt) src;
				final InvokeExpr ie = stmt.getInvokeExpr();
				final List<Value> callArgs = ie.getArgs();
				final List<Value> paramLocals = new ArrayList<Value>();
				for (int i = 0; i < dest.getParameterCount(); i++) {
					paramLocals.add(dest.getActiveBody().getParameterLocal(i));
				}
				
				return new FlowFunction<Abstraction>() {

					@Override
					public Set<Abstraction> computeTargets(Abstraction source) {
						if (stopAfterFirstFlow && !results.isEmpty())
							return Collections.emptySet();
						if (source.equals(zeroValue)) {
							return Collections.singleton(source);
						}
						if(taintWrapper != null && taintWrapper.isExclusive(stmt, source.getAccessPath())) {
							//taint is propagated in CallToReturnFunction, so we do not need any taint here:
							return Collections.emptySet();
						}
						
						//if we do not have to look into sinks:
						if (!inspectSinks && sourceSinkManager.isSink(stmt, interproceduralCFG())) {
							return Collections.emptySet();
						}
						Abstraction newSource;
						if (!source.isAbstractionActive() && (src.equals(source.getActivationUnit()) || src.equals(source.getActivationUnitOnCurrentLevel()))){
							newSource = source.getActiveCopy(false);
						}else{
							newSource = source;
						}
						
						Set<Abstraction> res = new HashSet<Abstraction>();
						// check if whole object is tainted (happens with strings, for example:)
						if (!dest.isStatic() && ie instanceof InstanceInvokeExpr) {
							InstanceInvokeExpr vie = (InstanceInvokeExpr) ie;
							// this might be enough because every call must happen with a local variable which is tainted itself:
							if (vie.getBase().equals(newSource.getAccessPath().getPlainValue())) {
								Abstraction abs = newSource.deriveNewAbstraction(newSource.getAccessPath().copyWithNewValue
										(dest.getActiveBody().getThisLocal()));
								if (pathTracking == PathTrackingMethod.ForwardTracking)
									((AbstractionWithPath) abs).addPathElement(stmt);
								//add new callArgs:
								assert abs != newSource; 		// our source abstraction must be immutable
								abs.setAbstractionFromCallEdge(abs.clone());
								res.add(abs);
							}
						}

						//special treatment for clinit methods - no param mapping possible
						if(!dest.getName().equals("<clinit>")) {
							assert dest.getParameterCount() == callArgs.size();
							// check if param is tainted:
							for (int i = 0; i < callArgs.size(); i++) {
								if (callArgs.get(i).equals(newSource.getAccessPath().getPlainLocal()) &&
										(triggerInaktiveTaintOrReverseFlow(callArgs.get(i), newSource) || newSource.isAbstractionActive())) {
									Abstraction abs = newSource.deriveNewAbstraction(newSource.getAccessPath().copyWithNewValue
											(paramLocals.get(i)), stmt);
									if (pathTracking == PathTrackingMethod.ForwardTracking)
										((AbstractionWithPath) abs).addPathElement(stmt);
									assert abs != newSource;		// our source abstraction must be immutable
									abs.setAbstractionFromCallEdge(abs.clone());
									res.add(abs);
								}
							}
						}

						// staticfieldRefs must be analyzed even if they are not part of the params:
						if (newSource.getAccessPath().isStaticFieldRef()) {
							Abstraction abs;
							abs = newSource.clone();
							assert (abs.equals(newSource) && abs.hashCode() == newSource.hashCode());
							assert abs != newSource;		// our source abstraction must be immutable
							abs.setAbstractionFromCallEdge(abs.clone());
							res.add(abs);
						}
						
						return res;
					}
				};
			}

			@Override
			public FlowFunction<Abstraction> getReturnFlowFunction(final Unit callSite, final SootMethod callee, final Unit exitStmt, final Unit retSite) {

				return new FlowFunction<Abstraction>() {

					@Override
					public Set<Abstraction> computeTargets(Abstraction source) {
						if (stopAfterFirstFlow && !results.isEmpty())
							return Collections.emptySet();
						if (source.equals(zeroValue)) {
							return Collections.emptySet();
						}
						
						//activate taint if necessary, but in any case we have to take the previous call edge abstraction
						Abstraction newSource;
						if(!source.isAbstractionActive()){
							if(callSite != null
									&& (callSite.equals(source.getActivationUnit()) || callSite.equals(source.getActivationUnitOnCurrentLevel())) ){
								newSource = source.getActiveCopy(true);
							}else{
								newSource = source.cloneUsePredAbstractionOfCG();
								
							}
						}else{
							newSource = source.cloneUsePredAbstractionOfCG();
						}
						
						//if abstraction is not active and activeStmt was in this method, it will not get activated = it can be removed:
						if(!newSource.isAbstractionActive() && newSource.getActivationUnit() != null
								&& interproceduralCFG().getMethodOf(newSource.getActivationUnit()).equals(callee))
							return Collections.emptySet();
						
						Set<Abstraction> res = new HashSet<Abstraction>();

						// Check whether this return is treated as a sink
						if (exitStmt instanceof ReturnStmt) {
							ReturnStmt returnStmt = (ReturnStmt) exitStmt;
							assert returnStmt.getOp() == null
									|| returnStmt.getOp() instanceof Local
									|| returnStmt.getOp() instanceof Constant;
							if (returnStmt.getOp() != null
									&& newSource.getAccessPath().isLocal()
									&& newSource.getAccessPath().getPlainValue().equals(returnStmt.getOp())
									&& sourceSinkManager.isSink(returnStmt, interproceduralCFG())) {

								if (pathTracking != PathTrackingMethod.NoTracking)
									results.addResult(returnStmt.getOp(), returnStmt,
											newSource.getSource(),
											newSource.getSourceContext(),
											((AbstractionWithPath) newSource).getPropagationPath(),
											returnStmt);
								else
									results.addResult(returnStmt.getOp(), returnStmt,
											newSource.getSource(), newSource.getSourceContext());
							}
						}
						
						// If we have no caller, we have nowhere to propagate. This
						// can happen when leaving the main method.
						if (callSite == null)
							return Collections.emptySet();

						// if we have a returnStmt we have to look at the returned value:
						if (exitStmt instanceof ReturnStmt) {
							ReturnStmt returnStmt = (ReturnStmt) exitStmt;
							Value retLocal = returnStmt.getOp();

							if (callSite instanceof DefinitionStmt) {
								DefinitionStmt defnStmt = (DefinitionStmt) callSite;
								Value leftOp = defnStmt.getLeftOp();
								if (retLocal.equals(newSource.getAccessPath().getPlainLocal()) &&
										(triggerInaktiveTaintOrReverseFlow(leftOp, newSource) || newSource.isAbstractionActive())) {
									Abstraction abs = newSource.deriveNewAbstraction(newSource.getAccessPath().copyWithNewValue(leftOp), callSite);
									if (pathTracking == PathTrackingMethod.ForwardTracking)
										((AbstractionWithPath) abs).addPathElement(exitStmt);
									assert abs != newSource;		// our source abstraction must be immutable
									res.add(abs);
									
									 //call backwards-solver:
									if(triggerInaktiveTaintOrReverseFlow(leftOp, abs)){
										Abstraction bwAbs = newSource.deriveNewAbstraction
												(newSource.getAccessPath().copyWithNewValue(leftOp), false);
										if (abs.isAbstractionActive())
											bwAbs = bwAbs.getAbstractionWithNewActivationUnitOnCurrentLevel(callSite);
										for (Unit predUnit : interproceduralCFG().getPredsOf(callSite))
											bSolver.processEdge(new PathEdge<Unit, Abstraction>(bwAbs, predUnit, bwAbs));
									}
								}
							}
						}

						// easy: static
						if (newSource.getAccessPath().isStaticFieldRef()) {
							// Simply pass on the taint
							res.add(newSource);
							
							// call backwards-check:
							Abstraction bwAbs = newSource.deriveInactiveAbstraction();
							if (newSource.isAbstractionActive())
								bwAbs = bwAbs.getAbstractionWithNewActivationUnitOnCurrentLevel(callSite);
							for (Unit predUnit : interproceduralCFG().getPredsOf(callSite))
								bSolver.processEdge(new PathEdge<Unit, Abstraction>(bwAbs, predUnit, bwAbs));
						}
						
						// checks: this/params/fields

						// check one of the call params are tainted (not if simple type)
						Value sourceBase = newSource.getAccessPath().getPlainLocal();
						Value originalCallArg = null;

						for (int i = 0; i < callee.getParameterCount(); i++) {
							if (callee.getActiveBody().getParameterLocal(i).equals(sourceBase)) {
								if (callSite instanceof Stmt) {
									Stmt iStmt = (Stmt) callSite;
									originalCallArg = iStmt.getInvokeExpr().getArg(i);
									//either the param is a fieldref (not possible in jimple?) or an array Or one of its fields is tainted/all fields are tainted
									if (triggerInaktiveTaintOrReverseFlow(originalCallArg, newSource)) {
										Abstraction abs = newSource.deriveNewAbstraction(newSource.getAccessPath().copyWithNewValue(originalCallArg), callSite);
										if (pathTracking == PathTrackingMethod.ForwardTracking)
											abs = ((AbstractionWithPath) abs).addPathElement(exitStmt);
										res.add(abs);
										if(triggerInaktiveTaintOrReverseFlow(originalCallArg, abs)){
											// call backwards-check:
											Abstraction bwAbs = abs.deriveInactiveAbstraction();
											if (abs.isAbstractionActive())
												bwAbs = bwAbs.getAbstractionWithNewActivationUnitOnCurrentLevel(callSite);
											for (Unit predUnit : interproceduralCFG().getPredsOf(callSite))
												bSolver.processEdge(new PathEdge<Unit, Abstraction>(bwAbs, predUnit, bwAbs));
										}
									}
								}
							}
						}


						Local thisL = null;
						if (!callee.isStatic()) {
							thisL = callee.getActiveBody().getThisLocal();
						}
						if (thisL != null) {
							if (thisL.equals(sourceBase)) {
								boolean param = false;
								// check if it is not one of the params (then we have already fixed it)
								for (int i = 0; i < callee.getParameterCount(); i++) {
									if (callee.getActiveBody().getParameterLocal(i).equals(sourceBase)) {
										param = true;
										break;
									}
								}
								if (!param) {
									if (callSite instanceof Stmt) {
										Stmt stmt = (Stmt) callSite;
										if (stmt.getInvokeExpr() instanceof InstanceInvokeExpr) {
											InstanceInvokeExpr iIExpr = (InstanceInvokeExpr) stmt.getInvokeExpr();
											Abstraction abs = newSource.deriveNewAbstraction(newSource.getAccessPath().copyWithNewValue(iIExpr.getBase()));
											if (pathTracking == PathTrackingMethod.ForwardTracking)
												((AbstractionWithPath) abs).addPathElement(stmt);
											res.add(abs);
											if(triggerInaktiveTaintOrReverseFlow(iIExpr.getBase(), abs)){
												Abstraction bwAbs = abs.deriveInactiveAbstraction();
												if (abs.isAbstractionActive())
													bwAbs = bwAbs.getAbstractionWithNewActivationUnitOnCurrentLevel(callSite);
												for (Unit predUnit : interproceduralCFG().getPredsOf(callSite))
													bSolver.processEdge(new PathEdge<Unit, Abstraction>(bwAbs, predUnit, bwAbs));
											}
										}
									}
								}
							}
						}

						return res; 
					} 

				};
			}

			@Override
			public FlowFunction<Abstraction> getCallToReturnFlowFunction(final Unit call, final Unit returnSite) {
				// special treatment for native methods:
				if (call instanceof Stmt) {
					final Stmt iStmt = (Stmt) call;
					final List<Value> callArgs = iStmt.getInvokeExpr().getArgs();

					return new FlowFunction<Abstraction>() {

						@Override
						public Set<Abstraction> computeTargets(Abstraction source) {
							if (stopAfterFirstFlow && !results.isEmpty())
								return Collections.emptySet();
							Abstraction newSource;
							//check inactive elements:
							if (!source.isAbstractionActive() && (call.equals(source.getActivationUnit()))|| call.equals(source.getActivationUnitOnCurrentLevel())){
								newSource = source.getActiveCopy(false);
							}else{
								newSource = source;
							}
							Set<Abstraction> res = new HashSet<Abstraction>();
							res.addAll(computeWrapperTaints(iStmt, newSource));

							// We can only pass on a taint if it is neither a parameter nor the
							// base object of the current call. If this call overwrites the left
							// side, the taint is never passed on.
							boolean passOn = !(call instanceof AssignStmt && ((AssignStmt) call).getLeftOp().equals
									(newSource.getAccessPath().getPlainLocal()));
							//we only can remove the taint if we step into the call/return edges
							//otherwise we will loose taint - see ArrayTests/arrayCopyTest
							if (passOn)
								if(hasValidCallees(call) || (taintWrapper != null
										&& taintWrapper.isExclusive(iStmt, newSource.getAccessPath()))) {
									if (iStmt.getInvokeExpr() instanceof InstanceInvokeExpr)
										if (((InstanceInvokeExpr) iStmt.getInvokeExpr()).getBase().equals
												(newSource.getAccessPath().getPlainLocal())) {
											passOn = false;
										}
										if (passOn)
											for (int i = 0; i < callArgs.size(); i++)
												if (callArgs.get(i).equals(newSource.getAccessPath().getPlainLocal()) && isTransferableValue(callArgs.get(i))) {
													passOn = false;
													break;
												}
										//static variables are always propagated if they are not overwritten. So if we have at least one call/return edge pair,
										//we can be sure that the value does not get "lost" if we do not pass it on:
										if(newSource.getAccessPath().isStaticFieldRef()){
											passOn = false;
										}
									}
							if (passOn)
								res.add(newSource);
							if (iStmt.getInvokeExpr().getMethod().isNative()) {
								if (callArgs.contains(newSource.getAccessPath().getPlainValue())) {
									// java uses call by value, but fields of complex objects can be changed (and tainted), so use this conservative approach:
									res.addAll(ncHandler.getTaintedValues(iStmt, newSource, callArgs));
								}
							}

							if (iStmt instanceof AssignStmt) {
								final AssignStmt stmt = (AssignStmt) iStmt;
								if (sourceSinkManager.isSource(stmt, interproceduralCFG())) {
									logger.debug("Found source: " + stmt.getInvokeExpr().getMethod());
									Abstraction abs; 
									if (pathTracking == PathTrackingMethod.ForwardTracking)
										abs = new AbstractionWithPath(stmt.getLeftOp(),
												stmt.getInvokeExpr(),
												stmt, false, true, iStmt).addPathElement(call);
									else
										abs = new Abstraction(stmt.getLeftOp(),
												stmt.getInvokeExpr(), stmt, false, true, iStmt);
									abs.setZeroAbstraction(source.getZeroAbstraction());
									res.add(abs);
									res.remove(zeroValue);
								}
							}

							// if we have called a sink we have to store the path from the source - in case one of the params is tainted!
							if (sourceSinkManager.isSink(iStmt, interproceduralCFG())) {
								boolean taintedParam = false;
								for (int i = 0; i < callArgs.size(); i++) {
									if (callArgs.get(i).equals(newSource.getAccessPath().getPlainLocal())) {
										taintedParam = true;
										break;
									}
								}

								if (taintedParam) {
									if (pathTracking != PathTrackingMethod.NoTracking)
										results.addResult(iStmt.getInvokeExpr(), iStmt,
												newSource.getSource(),
												newSource.getSourceContext(),
												((AbstractionWithPath) newSource).getPropagationPath(),
												call);
									else
										results.addResult(iStmt.getInvokeExpr(), iStmt,
												newSource.getSource(), newSource.getSourceContext());
								}
								// if the base object which executes the method is tainted the sink is reached, too.
								if (iStmt.getInvokeExpr() instanceof InstanceInvokeExpr) {
									InstanceInvokeExpr vie = (InstanceInvokeExpr) iStmt.getInvokeExpr();
									if (vie.getBase().equals(newSource.getAccessPath().getPlainValue())) {
										if (pathTracking != PathTrackingMethod.NoTracking)
											results.addResult(iStmt.getInvokeExpr(), iStmt,
													newSource.getSource(),
													newSource.getSourceContext(),
													((AbstractionWithPath) newSource).getPropagationPath(),
													call);

										else
											results.addResult(iStmt.getInvokeExpr(), iStmt,
													newSource.getSource(), newSource.getSourceContext());
									}
								}
							}
							return res;
						}

						/**
						 * Checks whether the given call has at least one valid target,
						 * i.e. a callee with a body.
						 * @param call The call site to check
						 * @return True if there is at least one callee implementation
						 * for the given call, otherwise false
						 */
						private boolean hasValidCallees(Unit call) {
							Set<SootMethod> callees = interproceduralCFG().getCalleesOfCallAt(call);
							for (SootMethod callee : callees)
								if (callee.isConcrete())
										return true;
							return false;
						}


					};
				}
				return Identity.v();
			}
		};
	}

	public InfoflowProblem(List<String> sourceList, List<String> sinkList) {
		this(new JimpleBasedBiDiICFG(), new DefaultSourceSinkManager(sourceList, sinkList));
	}

	public InfoflowProblem(ISourceSinkManager sourceSinkManager) {
		this(new JimpleBasedBiDiICFG(), sourceSinkManager);
	}

	public InfoflowProblem(InterproceduralCFG<Unit, SootMethod> icfg, List<String> sourceList, List<String> sinkList) {
		this(icfg, new DefaultSourceSinkManager(sourceList, sinkList));
	}

	public InfoflowProblem(InterproceduralCFG<Unit, SootMethod> icfg, ISourceSinkManager sourceSinkManager) {
		super(icfg);
		this.sourceSinkManager = sourceSinkManager;
	}

	public InfoflowProblem(ISourceSinkManager mySourceSinkManager, Set<Unit> analysisSeeds) {
	    this(new JimpleBasedBiDiICFG(), mySourceSinkManager);
	    for (Unit u : analysisSeeds)
	    	this.initialSeeds.put(u, Collections.singleton(zeroValue));
    }

	public void setBackwardSolver(InfoflowSolver backwardSolver){
		bSolver = backwardSolver;
	}

	@Override
	public boolean autoAddZero() {
		return false;
	}

}

