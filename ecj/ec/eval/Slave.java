/*
  Copyright 2006 by Sean Paus, Sean Luke, and George Mason University
  Licensed under the Academic Free License version 3.0
  See the file "LICENSE" for more information
*/


/*
 * Created on Oct 8, 2004
 */
package ec.eval;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.net.UnknownHostException;

import ec.*;
import ec.coevolve.GroupedProblemForm;
import ec.simple.SimpleProblemForm;
import ec.simple.SimpleEvolutionState;
import ec.util.*;

/**
 * Slave.java
 *
 
 <p>Slave is the main entry point for a slave evaluation process.  It replicates most of the functionality of
 the ec.Evolve class, for example in terms of parameters anc checkpointing.  However, the Slave class does
 not implement an evolutionary process, as there is no selection, nor breeding.  Instead, upon initialization,
 the Slave process only waits for jobs (evaluations) from the main evolutionary process (its <i>master</i>).
 Such jobs can be either traditional evolutionary computation evaluations (a single individual that needs to be
 evaluated), or coevolutionary evaluations (groups of individuals that have to be evaluated together).
 
 <p>The slave is run with one of the following argument formats:
 * 
 * @author Sean Paus
 */
public class Slave 
    {
    public final static String P_PRINTACCESSEDPARAMETERS = "print-accessed-params";
        
    public final static String P_PRINTUSEDPARAMETERS = "print-used-params";
        
    public final static String P_PRINTALLPARAMETERS = "print-all-params";
        
    public final static String P_PRINTUNUSEDPARAMETERS = "print-unused-params";
        
    public final static String P_PRINTUNACCESSEDPARAMETERS = "print-unaccessed-params";
        
    public final static String P_EVALSLAVENAME = "eval.slave-name";
        
    public final static String P_EVALMASTERHOST = "eval.master.host";
        
    public final static String P_EVALMASTERPORT = "eval.master.port";
        
    public final static String P_EVALCOMPRESSION = "eval.compression";
    
    public final static String P_RETURNINDIVIDUALS = "eval.return-inds";
        
    public static final String P_SUBPOP = "pop.subpop";
    
    public static final byte V_NOTHING = 0;
    public static final byte V_INDIVIDUAL = 1;
    public static final byte V_FITNESS = 2;
        
    public static final byte V_SHUTDOWN = 0;
    public static final byte V_EVALUATESIMPLE = 1;
    public static final byte V_EVALUATEGROUPED = 2;
    public static final byte V_CHECKPOINT = 3;
        
    /** The argument indicating that we're starting up from a checkpoint file. */
    public static final String A_CHECKPOINT = "-checkpoint";
        
    /** The argument indicating that we're starting fresh from a new parameter file. */
    public static final String A_FILE = "-file";
        
    /** flush announcements parameter */
    public static final String P_FLUSH = "flush";
        
    /** nostore parameter */
    public static final String P_STORE = "store";
        
    /** verbosity parameter */
    public static final String P_VERBOSITY = "verbosity";
        
    /** seed parameter */
    public static final String P_SEED = "seed";
        
    /** 'time' seed parameter value */
    public static final String V_SEED_TIME = "time";
        
    /** state parameter */
    public static final String P_STATE = "state";
        
    /** Time to run evolution on the slaves in seconds */ 
    public static final String P_RUNTIME = "runtime"; 
    public static int runTime=0; 
        
    /** Should slave run its own evolutionary process? */ 
    public static final String P_RUNEVOLVE = "run-evolve"; 
    public static boolean runEvolve=false; 
        
    /** How long we sleep in between attempts to connect to the master (in milliseconds). */
    public static final int SLEEP_TIME = 100;
        
    public static void main(String[] args)
        {
        EvolutionState state = null;
        ParameterDatabase parameters = null;
        Output output;

        MersenneTwisterFast[] random = new MersenneTwisterFast[1];
        random[0] = new MersenneTwisterFast();
        int verbosity;
        boolean store;
        int x;
                
        // 0. find the parameter database
        for (x = 0; x < args.length - 1; x++)
            if (args[x].equals(A_FILE))
                {
                try
                    {
                    parameters = new ParameterDatabase(
                        // not available in jdk1.1: new File(args[x+1]).getAbsoluteFile(),
                        new File(new File(args[x + 1]).getAbsolutePath()),
                        args);
                    break;
                    }
                catch(FileNotFoundException e)
                    { 
                    Output.initialError(
                        "A File Not Found Exception was generated upon" +
                        "reading the parameter file \"" + args[x+1] + 
                        "\".\nHere it is:\n" + e); }
                catch(IOException e)
                    { 
                    Output.initialError(
                        "An IO Exception was generated upon reading the" +
                        "parameter file \"" + args[x+1] +
                        "\".\nHere it is:\n" + e); } 
                }
        if (parameters == null)
            Output.initialError(
                "No parameter file was specified." ); 
                
        // 1. create the output
        store = parameters.getBoolean(new Parameter(P_STORE), null, false);
                
        verbosity = parameters.getInt(new Parameter(P_VERBOSITY), null, 0);
        if (verbosity < 0)
            Output.initialError("Verbosity should be an integer >= 0.\n",
                                new Parameter(P_VERBOSITY));
                
        output = new Output(store, verbosity);
        output.setFlush(
            parameters.getBoolean(new Parameter(P_FLUSH),null,false));
                
        // stdout is always log #0. stderr is always log #1.
        // stderr accepts announcements, and both are fully verbose
        // by default.
        output.addLog(ec.util.Log.D_STDOUT, Output.V_VERBOSE, false);
        output.addLog(ec.util.Log.D_STDERR, Output.V_VERBOSE, true);
                
        // 4. Set up the evolution state
                
        // what evolution state to use?
        state = (EvolutionState)
            parameters.getInstanceForParameter(new Parameter(P_STATE),null,
                                               EvolutionState.class);
        state.parameters = parameters;
        state.output = output;
        state.evalthreads = 1;
        state.breedthreads = 1;
                
        state.setup(null, null);
                
        output.systemMessage(Version.message());
                
        // 5. Determine whether or not to return entire Individuals or just Fitnesses
        //    (plus whether or not the Individual has been evaluated).
        
        boolean returnIndividuals = state.parameters.getBoolean(new Parameter(P_RETURNINDIVIDUALS),null,false);
                
                
        // 6. Open a server socket and listen for requests
        String slaveName = state.parameters.getString(
            new Parameter(P_EVALSLAVENAME),null);
                
        String masterHost = state.parameters.getString(
            new Parameter(P_EVALMASTERHOST),null );
        int masterPort = state.parameters.getInt(
            new Parameter(P_EVALMASTERPORT),null);
        boolean useCompression = state.parameters.getBoolean(new Parameter(P_EVALCOMPRESSION),null,false);
                
        runTime = state.parameters.getInt(new Parameter(P_RUNTIME), null, 0); 
                
        runEvolve = state.parameters.getBoolean(new Parameter(P_RUNEVOLVE),null,false); 
                        
        // Continue to serve new masters until killed.
        while (true)
            {
            try
                {
                Socket socket;
                long connectAttemptCount = 0;
                state.output.message("Connecting to master at "+masterHost+":"+masterPort);
                while (true)
                    {
                    try
                        {
                        socket = new Socket(masterHost, masterPort);
                        break;
                        }
                    catch (ConnectException e)   // it's not up yet...
                        {
                        connectAttemptCount++;
                        try
                            {
                            Thread.sleep(SLEEP_TIME);
                            }
                        catch( InterruptedException f )
                            {
                            }
                        }
                    }
                state.output.message("Connected to master after " + (connectAttemptCount * SLEEP_TIME) + " ms");
                                
                DataInputStream dataIn = null;
                DataOutputStream dataOut = null;

                try
                    {
                    InputStream tmpIn = socket.getInputStream();
                    OutputStream tmpOut = socket.getOutputStream();
                    if (useCompression)
                        {
                        tmpIn = new CompressingInputStream(tmpIn);
                        tmpOut = new CompressingOutputStream(tmpOut);

/*
  com.jcraft.jzlib.ZInputStream in = new com.jcraft.jzlib.ZInputStream(tmpIn, com.jcraft.jzlib.JZlib.Z_BEST_SPEED);
  in.setFlushMode(com.jcraft.jzlib.JZlib.Z_PARTIAL_FLUSH);
  tmpIn = in;
  com.jcraft.jzlib.ZOutputStream out = new com.jcraft.jzlib.ZOutputStream(tmpOut, com.jcraft.jzlib.JZlib.Z_BEST_SPEED);
  out.setFlushMode(com.jcraft.jzlib.JZlib.Z_PARTIAL_FLUSH);
  tmpOut = out;
*/
                        }
                                                
                    dataIn = new DataInputStream(tmpIn);
                    dataOut = new DataOutputStream(tmpOut);
                    }
                catch (IOException e)
                    {
                    state.output.fatal("Unable to open input stream from socket:\n"+e);
                    }
                                
                // specify the slaveName
                if (slaveName==null)
                    {
                    slaveName = socket.getLocalAddress().toString() + "/" + System.currentTimeMillis();
                    state.output.message("No slave name specified.  Using: " + slaveName);
                    }
                                
                dataOut.writeUTF(slaveName);
                dataOut.flush();
                
                // Read random state from Master
                random[0].readState(dataIn);
                System.err.println("read random state");
                                
                state.random = random;
                // Is this a Simple or Grouped ProblemForm?
                int problemType;
                try
                    {
                    while (true)
                        {
                        // 0 means to shut down
                        System.err.println("reading next problem");
                        problemType = dataIn.readByte();
                        System.err.println("Read problem: " + (int)problemType);
                        switch (problemType)
                            {
                            case V_SHUTDOWN:
                                socket.close();
                                return;  // we're outa here
                            case V_EVALUATESIMPLE:
                                evaluateSimpleProblemForm(state, returnIndividuals, dataIn, dataOut, args);
                                break;
                            case V_EVALUATEGROUPED:
                                evaluateGroupedProblemForm(state, returnIndividuals, dataIn, dataOut);
                                break;
                                                                                        
                            case V_CHECKPOINT:
                                state.output.systemMessage("Checkpointing");
                                try
                                    {
                                    state.random[0].writeState(dataOut);
                                    dataOut.flush();
                                    }
                                catch (IOException e)
                                    {
                                    state.output.fatal("Exception while checkpointing random state:\n"+e);
                                    }
                                break;
                            default:
                                state.output.fatal("Unknown problem form specified: "+problemType);
                            }
                        //System.err.println("Done Evaluating Individual");
                        }

                    } catch (IOException e)    {
                        // Since an IOException can happen here if the peer closes the socket
                        // on it's end, we don't necessarily have to exit.  Maybe we don't
                        // even need to print a warning, but we'll do so just to indicate
                        // something happened.
                        state.output.warning("Unable to read type of evaluation from master.  Maybe the master closed its socket and exited?:\n"+e);
                        }
                } 
            catch (UnknownHostException e)
                {
                state.output.fatal(e.getMessage());
                }
            catch (IOException e)
                {
                state.output.fatal("Unable to connect to master:\n" + e);
                }
            }
        }
        
    public static void evaluateSimpleProblemForm( EvolutionState state, boolean returnIndividuals,
                                                  DataInputStream dataIn, DataOutputStream dataOut, String[] args )
        {
        ParameterDatabase params=null; 
        SimpleEvolutionState tempState=null; 
        // Read the subpopulation number
        int subPopNum = -1;
        int numInds=1; 
        try
            {
            System.err.println("reading #individuals and subpop");
            numInds = dataIn.readInt();
            subPopNum = dataIn.readInt(); // assume all individuals are from the same subpopulation
            System.err.println("read #individuals and subpop");
            }
        catch (IOException e)
            {
            state.output.fatal("Unable to read the subpopulation number from the master:\n"+e);
            }
                
        Subpopulation subPop;
                        
        if( state.population == null )
            state.population = new Population();
        if( state.population.subpops == null )
            state.population.subpops = new Subpopulation[subPopNum+1];
        if( state.population.subpops.length <= subPopNum )
            {
            Subpopulation[] temp = state.population.subpops;
            state.population.subpops = new Subpopulation[subPopNum+1];
            System.arraycopy( temp, 0, state.population.subpops, 0, temp.length );
            }
        if( state.population.subpops[subPopNum] == null )
            {
            Parameter param = new Parameter(P_SUBPOP).push("" + subPopNum);
                        
            subPop = 
                (Subpopulation)(state.parameters.getInstanceForParameterEq(
                                    param,null,
                                    Subpopulation.class));
            // Setup the subpopulation so that it is in a valid state.
            subPop.setup(state, param);
            state.population.subpops[subPopNum] = subPop;
            }
        else
            subPop = state.population.subpops[subPopNum];
        
        if (runEvolve) { 
            params = Evolve.loadParameterDatabase(args); 
            tempState = (SimpleEvolutionState) Evolve.initialize(params, 0);
            tempState.startFresh(); 
                        
            tempState.population.subpops = new Subpopulation[1]; 
            tempState.population.subpops[0] = subPop;
            tempState.population.subpops[0].individuals = new Individual[numInds];
            }
                        
        // Read the individual(s) from the stream
        // and evaluate 
        boolean []updateFitness = new boolean[numInds];
        Individual[] inds = new Individual[numInds];
        try
            {
            System.err.println("reading individual");
            for (int i=0; i < numInds; i++) { 
                inds[i] = subPop.species.newIndividual( state, dataIn);
                if (!runEvolve) 
                    ((SimpleProblemForm)(state.evaluator.p_problem)).evaluate( state, inds[i], 0 );
                updateFitness[i] = dataIn.readBoolean(); 
                System.err.println("Read Individual " + i);
                }
            }
        catch (IOException e)
            {
            state.output.fatal("Unable to read individual from master." + e);
            }
        
        if (runEvolve) { 
            // Evaluate the population until time is up, or the evolution stops
            tempState.population.subpops[0].individuals = inds; 
            Individual in = inds[0];
            long startTime = System.currentTimeMillis(); 
            long endTime=0; 
            int result = tempState.R_NOTDONE; 
            while (result == tempState.R_NOTDONE) { 
                result = tempState.evolve(); 
                endTime = System.currentTimeMillis(); 
                if ((endTime - startTime) > runTime) 
                    break;
                }
            inds = tempState.population.subpops[0].individuals;
            tempState.finish(result);
            Evolve.cleanup(tempState);
            }

        System.err.println("Returning Individuals ");
        // Return the evaluated individual to the master
        try { 
            returnIndividualsToMaster(state, inds, updateFitness, dataOut, returnIndividuals); 
            } catch( IOException e ) { state.output.fatal("Caught fatal IOException\n"+e ); }
        }
    
    public static void evaluateGroupedProblemForm( EvolutionState state, boolean returnIndividuals,
                                                   DataInputStream dataIn, DataOutputStream dataOut )
        {
        // Read the subpopulation number and the number of individuals
        // from the master.
        int numInds = -1;
        boolean countVictoriesOnly = false;
        Subpopulation[] subPop = null;
        
        try
            {
            numInds = dataIn.readInt();
            subPop = new Subpopulation[numInds];
            for(int x=0;x<numInds;x++)
                {
                int subPopNum = dataIn.readInt();
                // Here we need to know the subpopulation number so as to create the
                // correct type of subpopulation in order to create the correct type
                // of individual.
                if( state.population == null )
                    state.population = new Population();
                if( state.population.subpops == null )
                    state.population.subpops = new Subpopulation[subPopNum+1];
                if( state.population.subpops.length <= subPopNum )
                    {
                    Subpopulation[] temp = state.population.subpops;
                    state.population.subpops = new Subpopulation[subPopNum+1];
                    System.arraycopy( temp, 0, state.population.subpops, 0, temp.length );
                    }
                if( state.population.subpops[subPopNum] == null )
                    {
                    Parameter param = new Parameter(P_SUBPOP).push("" + subPopNum);
                    subPop[x] = 
                        (Subpopulation)(state.parameters.getInstanceForParameterEq(
                                            param,null,
                                            Subpopulation.class));
                    // Setup the subpopulation so that it is in a valid state.
                    subPop[x].setup(state, param);
                    state.population.subpops[subPopNum] = subPop[x];
                    }
                else
                    subPop[x] = state.population.subpops[subPopNum];
                }
            countVictoriesOnly = dataIn.readBoolean();
            }
        catch (IOException e)
            {
            state.output.fatal("Unable to read the subpopulation number from the master:\n"+e);
            }
                
        // Read the individuals from the stream
        Individual inds[] = new Individual[numInds];
        boolean updateFitness[] = new boolean[numInds];
        try
            {
            for(int i=0;i<inds.length;++i)
                {
                inds[i] = subPop[i].species.newIndividual( state, dataIn );
                updateFitness[i] = dataIn.readBoolean();
                }
            }
        catch (IOException e)
            {
            state.output.fatal("Unable to read individual from master.");
            }
                
        // Evaluate the individual
        // TODO Check to make sure the real problem is an instance of GroupedProblemForm
        ((GroupedProblemForm)(state.evaluator.p_problem)).evaluate( state, inds, updateFitness, countVictoriesOnly, 0 );
                                
        try { 
            returnIndividualsToMaster(state, inds, updateFitness, dataOut, returnIndividuals); 
            } catch( IOException e ) { state.output.fatal("Caught fatal IOException\n"+e ); }
                
        }
        
    private static void returnIndividualsToMaster(EvolutionState state, Individual []inds, boolean[] updateFitness,
                                                  DataOutputStream dataOut, boolean returnIndividuals) throws IOException 
        {
        // Return the evaluated individual to the master
        // just write evaluated and fitness
        for(int i=0;i<inds.length;i++)
            {
            System.err.println("Returning Individual " + i);
            System.err.println("writing byte: " + ( returnIndividuals ? V_INDIVIDUAL : (updateFitness[i] ? V_FITNESS : V_NOTHING)));
            dataOut.writeByte(returnIndividuals ? V_INDIVIDUAL : (updateFitness[i] ? V_FITNESS : V_NOTHING));
//          dataOut.flush();
            System.err.println("wrote byte");
            if (returnIndividuals)
                {
//              System.err.println("Writing Individual");
                inds[i].writeIndividual(state, dataOut);
//              System.err.println("Wrote Individual");
                }
            else if (updateFitness[i])
                {
                dataOut.writeBoolean(inds[i].evaluated);
                inds[i].fitness.writeFitness(state,dataOut);
                }
            }
//      System.err.println("flushing");
        dataOut.flush();
//      System.err.println("flushed");
        }
    }
