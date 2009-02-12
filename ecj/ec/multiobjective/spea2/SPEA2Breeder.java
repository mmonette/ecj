package ec.multiobjective.spea2;

import ec.*;
import ec.util.*;
import ec.simple.*; 

/**
 * This a re-write of SPEA2Breeder.java by Robert Hubley, to make it more modular so it's easier for me to extend it.
 * Namely I isolated the following functionality: "Out of N individuals, give me a front of size M < N."
 * 
 * This is also useful for others at the last generation, when you createad a bunch of individuals, evaluated them, 
 * but not give them a chance to enter the archive, as you don't call the breeder on the last generation!
 * 
 * <p>Additonally, I made <code>double[][] distances</code> & <code>int[][] sortedIndex</code> static and REUSED THEM, to reduce GC!!!
 * In my case the the number of individuals is not known in advanced (I have a rather large upper bound), so I chose
 * to have these arrays extended when needed.  
 * 1. Note that they need not be shrunk.
 * 2. Note that in the usual case when the number of individuals from the old population is 
 * always the same, the arrays are only allocated once, so no efficiency loss here.
 * (except I keep asking are they big enough each time loadElites() is called, and that's not a big deal
 * given that loadElites() is O(n^3)).
 * 
 * <p>One more thing, I do fewer iterations in the loop that compacts individuals
 * that made the cut and copies them in the new population (i.e. Hubley was visiting
 * a bunch of nulls at the end of the array).
 * 
 * @author Gabriel Balan (based on SPEA2Breeder.java by Robert Hubley) 
 */

public class SPEA2Breeder extends SimpleBreeder
{

    // overrides the loadElites function to load the archive the way we'd like to do it.
    public void loadElites(EvolutionState state, Population newpop)
    {
        //state.output.println("Loading the SPEA2 archive...",V_DEBUG, Log.D_STDOUT);

        for(int sub=0;sub<state.population.subpops.length;sub++) 
            {
		/** The new population after we are done picking the elites */
		Individual[] newInds = newpop.subpops[sub].individuals;
		SPEA2Subpopulation spop = (SPEA2Subpopulation)state.population.subpops[sub];
		/** The old population from which to pick elites  */
		Individual[] oldInds = spop.individuals;
		loadElites(state, oldInds, newInds, spop.archiveSize);
            }
    }


    /** An array of distances between elites */
    static double[][] _distances = null;
    /** An array of indexes to individuals sorted by distances */
    static int[][] _sortedIndex = null;
    //it's ok that these are static: they're only used in loadElites,
    //which is called before making the breeding threads
    //(i.e. NO RACE CONDITION!)
    
    /*
     * Apparently it's not enough to sort the array and keep the best |Archive| ?!?
     * Zitzler says: copy all undominated into the new archive.
     * -if still room in the archive, fill it with dominated inds in order of their fitness;
     * -if too many points, you prune the archive with an ITERATIVE process in which 
     * you drop the point with the closest neighbor in the [still overpopulated] archive.
     * 
     *  
     * S_i= number of inds ind_i dominates 	//strength
     * R_i = sum_{j dom i} S_j 				//raw fitness
     * D_i = 1/[2+dist_to_kth...]			//density
     * F=R+D 								//fitness,
     * 
     * D<=1/2 and S,R \in N so D matters only if R is a tie!
     * R_undominated = 0.
     * 
     * So all undominated come before the dominated no matter what!!!
     * It's when there are too many undominated that you need to work hard :(
     */
    public static void loadElites(EvolutionState state, Individual[] oldInds, Individual[] newInds, int archiveSize)
    {

        // Sort the old guys
        //sort(oldInds);
        QuickSort.qsort(oldInds, new SortComparator()
            {
		/** Returns true if a < b, else false */
		public boolean lt(Object a, Object b)
                {
		    return ((SPEA2MultiObjectiveFitness)(((Individual)a).fitness)).SPEA2Fitness <
			((SPEA2MultiObjectiveFitness)(((Individual)b).fitness)).SPEA2Fitness;
                            
                }
                    
		/** Returns true if a > b, else false */
		public boolean gt(Object a, Object b)
                {
		    return ((SPEA2MultiObjectiveFitness)(((Individual)a).fitness)).SPEA2Fitness >
			((SPEA2MultiObjectiveFitness)(((Individual)b).fitness)).SPEA2Fitness;
                            
                }
            });

        // Null out non-candidates and count
        int nIndex = 1;
        for(int x=0;x<oldInds.length;x++)
            {
		if ( nIndex > archiveSize && 
		     ((SPEA2MultiObjectiveFitness)oldInds[x].fitness).SPEA2Fitness >= 1 )
		    {
			oldInds[x] = null;
		    }else {
		    nIndex++;
                }
            }
        nIndex--;

        // Check to see if we need to truncate the archive
        if ( nIndex > archiveSize ) 
            {
        	double[][] distances = _distances;
        	int[][] sortedIndex = _sortedIndex;
        	//I'll reuse the previously allocated matrices, unless they're too small.
        	if(distances==null ||distances.length<nIndex)
		    {
        		distances = _distances = new double[nIndex][nIndex];
        		sortedIndex = _sortedIndex = new int[nIndex][nIndex];
		    }
        	
		// Set distances
		state.output.println("  Truncating the archive",Output.V_NO_MESSAGES, Log.D_STDOUT);
		//state.output.println("    - Calculating distances",V_DEBUG, Log.D_STDOUT);
		for ( int y=0; y<nIndex; y++ ) 
		    {
			for(int z=y+1;z<nIndex;z++)
			    {
				distances[y][z] =
				    ((SPEA2MultiObjectiveFitness)oldInds[y].fitness).
				    calcDistance( (SPEA2MultiObjectiveFitness)oldInds[z].fitness );
				distances[z][y] = distances[y][z];
			    } // For each individual yz calculate fitness distance
			distances[y][y] = -1;
			//Sure, you'll ask "why not POSITIVE infinity?"
			//all points have -1 as their first min (so an n-way tie that prunes nobody);
			//might as well make it the last tie!
			//Hubley skips the first tie, so it's correct.
                
		    } // For each individual y  calculate fitness distances

		//state.output.println("    - Sorting distances",V_DEBUG, Log.D_STDOUT);
		// create sorted index lists
		for (int i=0; i<nIndex; i++)
		    {
			sortedIndex[i][0] = 0;
			for (int j=1; j<nIndex; j++)
			    { // for all columns
				int k = j;  // insertion position
				while (k>0 && distances[i][j] < distances[i][sortedIndex[i][k-1]])
				    //TODO this looks like O(N^2), but hopefully insert-sort is better than quick sort for small sizes
				    {
					sortedIndex[i][k] = sortedIndex[i][k-1];
					k--;
				    }
				sortedIndex[i][k] = j;
			    }
		    }

            	
		int mf = nIndex;
		//state.output.println("    - Searching for minimal distances",V_DEBUG, Log.D_STDOUT);
		while (mf > archiveSize)
		    {
			// search for minimal distances
			int minpos = 0;
			for (int i=1; i<nIndex; i++)
			    //we start from 1 cause the current candidate (minpos) starts at 0.
			    {
				for (int j=1; j<mf; j++)//j is rank
				    //I'm guessing we start form 1 cause the first min is -1 for everybody.
				    {
					double dist_i_sortedIndex_i_j = distances[i][sortedIndex[i][j]];
					double dist_min_sortedIndex_min_j = distances[minpos][sortedIndex[minpos][j]];
					//no reason to read these twice.
					if (dist_i_sortedIndex_i_j<dist_min_sortedIndex_min_j)
					    {
						minpos = i;
						break;
					    }
					else if (dist_i_sortedIndex_i_j > dist_min_sortedIndex_min_j)
					    break;
				    }
			    }
			// kill entries of pos (which is now minpos) from lists
                
			for (int i=0; i<nIndex; i++)
			    {
				// Don't choose these positions again
				distances[i][minpos] = Double.POSITIVE_INFINITY;
				distances[minpos][i] = Double.POSITIVE_INFINITY;

				int[] sortedIndicesForI = sortedIndex[i];//this is to cut down on range checks.
				for (int j=1; j<mf-1; j++)
				    {
					if (sortedIndicesForI[j]==minpos)
					    {
						sortedIndicesForI[j] = sortedIndicesForI[j+1];
						sortedIndicesForI[j+1] = minpos;
					    }
				    }
			    }
			oldInds[minpos] = null;
			mf--;
		    } // end while ( mf > thisSubpop.archiveSize )
		//state.output.println("  Done the truncation thang...",V_DEBUG, Log.D_STDOUT);
            } // end if ( nIndex > thisSubpop.archiveSize )

        // Compress and place in newpop
        // NOTE: The archive is maintained at the top block of the individuals
        //       vector.  Immediately prior to selection we copy the archive to
        //       the next generation (top block) and then pass along the old
        //       individuals (archive only) as the bottom block of the oldInds
        //       vector.  The SPEA2TournamentSelection depends on the individuals
        //       being between 0-archiveSize in this vector!
        //
	//TODO is this note SEAN's or Hubley's???
        
        int nullIndex = -1;
        int newIndex = 1;
        //for (int i=0; i<oldInds.length; i++)
        for (int i=0; i<nIndex; i++)//no need to visit oldInds.len-nIndex nulls (I know nIndex>=archiveSize)
            {
		if ( oldInds[i] == null )
		    {
			if ( nullIndex == -1 )
			    {
				nullIndex = i;
			    }
		    }else
		    {
			newInds[newInds.length-newIndex++] = (Individual)(oldInds[i].clone());
			if ( nullIndex > -1 ) 
			    {
				oldInds[nullIndex++] = oldInds[i];
				oldInds[i] = null;
			    }
		    }
            }
       
        // Right now the archive is in the beginning of the array; we move it
        // to the end of the array here to be consistent with ECJ's assumptions.
	for (int i=0; i < oldInds.length - archiveSize; i++) 
            {
		oldInds[oldInds.length - i - 1] = oldInds[i];
		oldInds[i] = null;
            }
      
        // NOTE: This is a key place for debugging.  The archive has been built and all the individuals
        //       have *not* yet been mutated/crossed-over.  

    }
}

