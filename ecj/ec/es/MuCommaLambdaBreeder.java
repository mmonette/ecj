/*
  Copyright 2006 by Sean Luke
  Licensed under the Academic Free License version 3.0
  See the file "LICENSE" for more information
*/


package ec.es;
import ec.*;
import ec.util.*;

/* 
 * MuCommaLambdaBreeder.java
 * 
 * Created: Thu Sep  7 17:27:47 2000
 * By: Sean Luke
 */

/**
 * MuCommaLambdaBreeder is a Breeder which, together with
 * ESSelection, implements the (mu,lambda) breeding strategy and gathers
 * the comparison data you can use to implement a 1/5-rule mutation mechanism.
 * 
 * <p>Evolution strategies breeders require a "mu" parameter and a "lambda"
 * parameter for each subpopulation.  "mu" refers to the number of parents
 * from which the new population will be built.  "lambda" refers to the
 * number of children generated by the mu parents.  Subpopulation sizes
 * will change as necessary to accommodate this fact in later generations.
 * The only rule for initial subpopulation sizes is that they must be
 * greater than or equal to the mu parameter for that subpopulation.
 *
 * <p>You can now set your initial subpopulation
 * size to whatever you like, totally independent of lambda and mu,
 * as long as it is &gt;= mu.
 *
 * <p>MuCommaLambdaBreeder stores mu and lambda values for each subpopulation
 * in the population, as well as comparisons.  A comparison tells you
 * if &gt;1/5, &lt;1/5 or =1/5 of the new population was better than its
 * parents (the so-called evolution strategies "one-fifth rule".
 * Although the comparisons are gathered, no mutation objects are provided
 * which actually <i>use</i> them -- you're free to use them in any mutation
 * objects you care to devise which requires them.
 *
 * <p>To do evolution strategies evolution, the
 * breeding pipelines must contain <b>exactly</b> one ESSelection object called
 * each time an individual is generated.   For example, if you're just 
 * generating children by mutating a single selected individual into a child,
 * then you use the ESSelection object to pick that individual.  If you're
 * generating two children at a time by selecting two parents and crossing
 * them over, then each parent should be selected with ESSelection (and
 * in this case, you had better have a population size that's an even number!)
 * If you're generating one child at a time by selecting two parents and
 * crossing them over, then throwing away one of the children and mutating
 * the other, then you should have only <b>one</b> parent chosen through
 * ESSelection; the other might be chosen with Tournament Selection, say.
 *

 <p><b>Parameters</b><br>
 <table>
 <tr><td valign=top>es.lambda.<i>subpop-num</i><br>
 <font size=-1>int >= 0</font></td><td>Specifies the 'lambda' parameter for the subpopulation.</td>
 </tr>
 <tr><td valign=top>es.mu.<i>subpop-num</i><br>
 <font size=-1>int:  a multiple of "lambda"</font></td><td>Specifies the 'mu' parameter for the subpopulation.</td>
 </tr>
 </table>

 * @author Sean Luke
 * @version 1.0 
 */

public class MuCommaLambdaBreeder extends Breeder
    {    
    public static final String P_MU = "mu";
    public static final String P_LAMBDA = "lambda";

    public int[] mu;
    public int[] lambda;
    
    public Population parentPopulation;

    public byte[] comparison; 
    public static final byte C_OVER_ONE_FIFTH_BETTER = 1;
    public static final byte C_UNDER_ONE_FIFTH_BETTER = -1;
    public static final byte C_EXACTLY_ONE_FIFTH_BETTER = 0;
   
    /** Modified by multiple threads, don't fool with this */
    public int[] count;

    public void setup(final EvolutionState state, final Parameter base)
        {
        // we're not using the base
        Parameter p = new Parameter(Initializer.P_POP).push(Population.P_SIZE);
        int size = state.parameters.getInt(p,null,1);  // if size is wrong, we'll let Population complain about it -- for us, we'll just make 0-sized arrays and drop out.
        
        mu = new int[size];
        lambda = new int[size];
        comparison = new byte[size];
        
        // load mu and lambda data
        for(int x=0;x<size;x++)
            {
            lambda[x] = state.parameters.getInt(ESDefaults.base().push(P_LAMBDA).push(""+x),null,1);            
            if (lambda[x]==0) state.output.error("lambda must be an integer >= 1",ESDefaults.base().push(P_LAMBDA).push(""+x));
            mu[x] = state.parameters.getInt(ESDefaults.base().push(P_MU).push(""+x),null,1);            
            if (mu[x]==0) state.output.error("mu must be an integer >= 1",ESDefaults.base().push(P_MU).push(""+x));
            else if ((lambda[x] / mu[x]) * mu[x] != lambda[x]) // note integer division
                state.output.error("mu must be a multiple of lambda", ESDefaults.base().push(P_MU).push(""+x));
            }
        state.output.exitIfErrors();
        }



    /** Sets all subpopulations in pop to the expected lambda size.  Does not fill new slots with individuals. */
    public Population setToLambda(Population pop, EvolutionState state)
        {
        for(int x=0;x<pop.subpops.length;x++)
            {
            int s = lambda[x];
            
            // check to see if the array's not the right size
            if (pop.subpops[x].individuals.length != s)
                // need to increase
                {
                Individual[] newinds = new Individual[s];
                System.arraycopy(pop.subpops[x].individuals,0,newinds,0,
                                 s < pop.subpops[x].individuals.length ? 
                                 s : pop.subpops[x].individuals.length);
                pop.subpops[x].individuals = newinds;
                }
            }
        return pop;
        }
                

    public Population breedPopulation(EvolutionState state) 
        {
        // Complete 1/5 statistics for last population
        
        if (parentPopulation != null)
            {
            // Only go from 0 to lambda-1, as the remaining individuals may be parents.
            // A child C's parent's index I is equal to C / mu[subpopulation].
            for (int x=0;x<state.population.subpops.length;x++)
                {
                int numChildrenBetter = 0;
                for (int i = 0; i < lambda[x]; i++)
                    {
                    int parent = i / (lambda[x] / mu[x]);  // note integer division
                    if (state.population.subpops[x].individuals[i].fitness.betterThan(
                            parentPopulation.subpops[x].individuals[parent].fitness))
                        numChildrenBetter++;
                    }
                if (numChildrenBetter > lambda[x] / 5.0)  // note float division
                    comparison[x] = C_OVER_ONE_FIFTH_BETTER;
                else if (numChildrenBetter < lambda[x] / 5.0)  // note float division
                    comparison[x] = C_UNDER_ONE_FIFTH_BETTER;
                else comparison[x] = C_EXACTLY_ONE_FIFTH_BETTER;
                }
            }
                        
        // load the parent population
        parentPopulation = state.population;
        
        // MU COMPUTATION
        
        // At this point we need to do load our population info
        // and make sure it jibes with our mu info

        // the first issue is: is the number of subpopulations
        // equal to the number of mu's?

        if (mu.length!=state.population.subpops.length) // uh oh
            state.output.fatal("For some reason the number of subpops is different than was specified in the file (conflicting with Mu and Lambda storage).",null);

        // next, load our population, make sure there are no subpopulations smaller than the mu's
        for(int x=0;x<state.population.subpops.length;x++)
            {
            if (state.population.subpops[0].individuals.length < mu[x])
                state.output.error("Subpopulation " + x + " must be a multiple of the equivalent mu (that is, "+ mu[x]+").");
            }
        state.output.exitIfErrors();
        
        


        int numinds[][] = 
            new int[state.breedthreads][state.population.subpops.length];
        int from[][] = 
            new int[state.breedthreads][state.population.subpops.length];
            
        // sort evaluation to get the Mu best of each subpopulation
        
        for(int x=0;x<state.population.subpops.length;x++)
            {
            final Individual[] i = state.population.subpops[x].individuals;
            /*
              QuickSort.qsort(i,
              new SortComparator()
              {
              // gt implies that object a should appear after object b in the sorted array.
              // we want this to be the case if object a has WORSE fitness
              public boolean gt(Object a, Object b)
              {
              return ((Individual)b).fitness.betterThan(
              ((Individual)a).fitness);
              }
              // gt implies that object a should appear before object b in the sorted array
              // we want this to be the case if object a has BETTER fitness
              public boolean lt(Object a, Object b)
              {
              return ((Individual)a).fitness.betterThan(
              ((Individual)b).fitness);
              }
              });
            */
            java.util.Arrays.sort(i,
                                  new java.util.Comparator()
                                      {
                                      public int compare(Object o1, Object o2)
                                          {
                                          Individual a = (Individual) o1;
                                          Individual b = (Individual) o2;
                                          // return 1 if should appear after object b in the array.
                                          // This is the case if a has WORSE fitness.
                                          if (b.fitness.betterThan(a.fitness)) return 1;
                                          // return -1 if a should appear before object b in the array.
                                          // This is the case if b has WORSE fitness.
                                          if (a.fitness.betterThan(b.fitness)) return -1;
                                          // else return 0
                                          return 0;
                                          }
                                      });
            }

        // now the subpops are sorted so that the best individuals
        // appear in the lowest indexes.

        Population newpop = setToLambda((Population) state.population.emptyClone(),state);

        // create the count array
        count = new int[state.breedthreads];

        // divvy up the lambda individuals to create

        for(int y=0;y<state.breedthreads;y++)
            for(int x=0;x<state.population.subpops.length;x++)
                {
                // figure numinds
                if (y<state.breedthreads-1) // not last one
                    numinds[y][x]=
                        lambda[x]/state.breedthreads;
                else // in case we're slightly off in division
                    numinds[y][x]=
                        lambda[x]/state.breedthreads +
                        (lambda[x] - (lambda[x] / state.breedthreads)  // note integer division
                         *state.breedthreads);                   
                
                // figure from
                from[y][x]=
                    (lambda[x]/
                     state.breedthreads) * y;
                }
            
        int[][] bettercount= new int[state.breedthreads][state.population.subpops.length];

        if (state.breedthreads==1)
            {
            breedPopChunk(newpop,state,bettercount,numinds[0],from[0],0);
            }
        else
            {
            Thread[] t = new Thread[state.breedthreads];
                
            // start up the threads
            for(int y=0;y<state.breedthreads;y++)
                {
                MuLambdaBreederThread r = new MuLambdaBreederThread();
                r.threadnum = y;
                r.newpop = newpop;
                r.numinds = numinds[y];
                r.from = from[y];
                r.me = this;
                r.state = state;
                t[y] = new Thread(r);
                t[y].start();
                }
                
            // gather the threads
            for(int y=0;y<state.breedthreads;y++) try
                {
                t[y].join();
                }
            catch(InterruptedException e)
                {
                state.output.fatal("Whoa! The main breeding thread got interrupted!  Dying...");
                }
            }

        /*
        // determine our comparisons
        for(int x=0;x<state.population.subpops.length;x++)
        {
        int total = 0;
        for(int y=0;y<state.breedthreads;y++)
        total += bettercount[y][x];
        if (((double)total)/state.population.subpops[x].individuals.length > 0.2)
        comparison[x] = C_OVER_ONE_FIFTH_BETTER;
        else if (((double)total)/state.population.subpops[x].individuals.length < 0.2)
        comparison[x] = C_UNDER_ONE_FIFTH_BETTER;
        else comparison[x] = C_EXACTLY_ONE_FIFTH_BETTER;
        }
        */
        return postProcess(newpop,state.population,state);
        }

    /** A hook for Mu+Lambda, not used in Mu,Lambda */

    public Population postProcess(Population newpop, Population oldpop, EvolutionState state)
        {
        return newpop;
        }
    
    
    /** A private helper function for breedPopulation which breeds a chunk
        of individuals in a subpopulation for a given thread.
        Although this method is declared
        public (for the benefit of a private helper class in this file),
        you should not call it. */
    
    public void breedPopChunk(Population newpop, EvolutionState state, 
                              int[][] bettercount,
                              int[] numinds, int[] from, int threadnum) 
        {
        // reset the appropriate count slot
        count[threadnum]=0;
        
        for(int subpop=0;subpop<newpop.subpops.length;subpop++)
            {
            BreedingPipeline bp = (BreedingPipeline) newpop.subpops[subpop].
                species.pipe_prototype.clone();
            
            // check to make sure that the breeding pipeline produces
            // the right kind of individuals.  Don't want a mistake there! :-)
            if (!bp.produces(state,newpop,subpop,threadnum))
                state.output.fatal("The Breeding Pipeline of subpopulation " + subpop + " does not produce individuals of the expected species " + newpop.subpops[subpop].species.getClass().getName() + " or fitness " + newpop.subpops[subpop].f_prototype );
            bp.prepareToProduce(state,subpop,threadnum);
            
            // start breedin'!
            
            int upperbound = from[subpop]+numinds[subpop];
            for(int x=from[subpop];x<upperbound;x++)
                {
                int prevcount = count[threadnum];
                if (bp.produce(1,1,x,subpop, newpop.subpops[subpop].individuals,
                               state,threadnum) != 1)
                    state.output.fatal("Whoa! Breeding Pipeline for subpop " + subpop + " is not producing one individual at a time, as is required by the MuLambda strategies.");
                if (count[threadnum]-prevcount != 1)
                    state.output.fatal("Whoa!  Breeding Pipeline for subpop " + subpop + " used an ESSelector more or less than exactly once.  Number of times used: " + (count[threadnum]-prevcount));
                }
            bp.finishProducing(state,subpop,threadnum);
            }
        }
    }


/** A private helper class for implementing multithreaded breeding */
class MuLambdaBreederThread implements Runnable
    {
    Population newpop;
    public int[][] bettercount;
    public int[] numinds;
    public int[] from;
    public MuCommaLambdaBreeder me;
    public EvolutionState state;
    public int threadnum;
    public void run()
        {
        me.breedPopChunk(newpop,state,bettercount,numinds,from,threadnum);
        }
    }


