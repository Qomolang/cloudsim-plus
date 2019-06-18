/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */
package org.cloudbus.cloudsim.util;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModel;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Reads resource traces and creates a list of ({@link Cloudlet Cloudlets}) (jobs).
 * It follows the <a href="http://www.cs.huji.ac.il/labs/parallel/workload/">Standard Workload Format (*.swf files)</a>
 * from <a href="new.huji.ac.il/en">The Hebrew University of Jerusalem</a>.
 * Check important details at {@link TraceReaderAbstract}.
 *
 * <p>
 * <b>NOTES:</b>
 * <ul>
 *   <li>The default Cloudlet reader size for sending to and receiving from a Datacenter is
 *       {@link DataCloudTags#DEFAULT_MTU}. However, you can
 *       specify the reader size by using {@link Cloudlet#setFileSize(long)}.
 *   </li>
 *   <li>A job run time considers the time spent for a single PE (since all PEs will
 *       be used for the same amount of time)<b>not</b>
 *       not the total execution time across all PEs.
 *       For example, job #1 in the trace has a run time of 100 seconds for 2
 *       processors. This means each processor runs job #1 for 100 seconds, if the
 *       processors have the same specification.
 *   </li>
 * </ul>
 * </p>
 *
 * @see #getInstance(String, int)
 * @see #generateWorkload()
 *
 * @author Anthony Sulistio
 * @author Marcos Dias de Assuncao
 * @author Manoel Campos da Silva Filho
 */
public final class SwfWorkloadFileReader extends TraceReaderAbstract {

    /**
     * Field index of job number.
     * Jub number values start from 1.
     */
    private static final int JOB_NUM_INDEX = 0;

    /**
     * Field index of submit time of a job (in seconds).
     */
    private static final int SUBMIT_TIME_INDEX = 1;

    /**
     * Field index of execution time of a job (in seconds).
     * The wall clock time the job was running (end time minus start time).
     */
    private static final int RUN_TIME_INDEX = 3;

    /**
     * Field index of number of processors needed for a job.
     * In most cases this is also the number of processors the job uses;
     * if the job does not use all of them, we typically don't know about it.
     */
    private static final int NUM_PROC_INDEX = 4;

    /**
     * Field index of required number of processors.
     */
    private static final int REQ_NUM_PROC_INDEX = 7;

    /**
     * Field index of required running time.
     * This can be either runtime (measured in wall-clock seconds), or average CPU time per processor (also in seconds)
     * -- the exact meaning is determined by a header comment.
     * If a log contains a request for total CPU time, it is divided by the number of requested processors.
     */
    private static final int REQ_RUN_TIME_INDEX = 8;

    /**
     * Field index of user who submitted the job.
     */
    private static final int USER_ID_INDEX = 11;

    /**
     * Field index of group of the user who submitted the job.
     */
    private static final int GROUP_ID_INDEX = 12;

    /**
     * Max number of fields in the trace reader.
     */
    private static final int FIELD_COUNT = 18;

    /**
     * If the field index of the job number ({@link #JOB_NUM_INDEX}) is equals to this
     * constant, it means the number of the job doesn't have to be gotten from
     * the trace reader, but has to be generated by this workload generator class.
     */
    private static final int IRRELEVANT = -1;

    /**
     * @see #getMips()
     */
    private int mips;

    /**
     * List of Cloudlets created from the trace {@link #getInputStream()}.
     */
    private final List<Cloudlet> cloudlets;

    /**
     * @see #setPredicate(Predicate)
     */
    private Predicate<Cloudlet> predicate;

    /**
     * Gets a {@link SwfWorkloadFileReader} instance from a workload file
     * inside the <b>application's resource directory</b>.
     * Use the available constructors if you want to load a file outside the resource directory.
     *
     * @param fileName the workload trace <b>relative file name</b> in one of the following formats: <i>ASCII text, zip, gz.</i>
     * @param mips     the MIPS capacity of the PEs from the VM where each created Cloudlet is supposed to run.
     *                 Considering the workload reader provides the run time for each
     *                 application registered inside the reader, the MIPS value will be used
     *                 to compute the {@link Cloudlet#getLength() length of the Cloudlet (in MI)}
     *                 so that it's expected to execute, inside the VM with the given MIPS capacity,
     *                 for the same time as specified into the workload reader.
     * @throws IllegalArgumentException when the workload trace file name is null or empty; or the resource PE mips is less or equal to 0
     * @throws UncheckedIOException     when the file cannot be accessed (such as when it doesn't exist)
     */
    public static SwfWorkloadFileReader getInstance(final String fileName, final int mips) {
        final InputStream reader = ResourceLoader.getInputStream(fileName, SwfWorkloadFileReader.class);
        return new SwfWorkloadFileReader(fileName, reader, mips);
    }

    /**
     * Create a new SwfWorkloadFileReader object.
     *
     * @param filePath the workload trace file path in one of the following formats: <i>ASCII text, zip, gz.</i>
     * @param mips     the MIPS capacity of the PEs from the VM where each created Cloudlet is supposed to run.
     *                 Considering the workload reader provides the run time for each
     *                 application registered inside the reader, the MIPS value will be used
     *                 to compute the {@link Cloudlet#getLength() length of the Cloudlet (in MI)}
     *                 so that it's expected to execute, inside the VM with the given MIPS capacity,
     *                 for the same time as specified into the workload reader.
     * @throws IllegalArgumentException when the workload trace file name is null or empty; or the resource PE mips is less or equal to 0
     * @throws FileNotFoundException    when the file is not found
     * @see #getInstance(String, int)
     */
    public SwfWorkloadFileReader(final String filePath, final int mips) throws IOException {
        this(filePath, Files.newInputStream(Paths.get(filePath)), mips);
    }

    /**
     * Create a new SwfWorkloadFileReader object.
     *
     * @param filePath the workload trace file path in one of the following formats: <i>ASCII text, zip, gz.</i>
     * @param reader   a {@link InputStreamReader} object to read the file
     * @param mips     the MIPS capacity of the PEs from the VM where each created Cloudlet is supposed to run.
     *                 Considering the workload reader provides the run time for each
     *                 application registered inside the reader, the MIPS value will be used
     *                 to compute the {@link Cloudlet#getLength() length of the Cloudlet (in MI)}
     *                 so that it's expected to execute, inside the VM with the given MIPS capacity,
     *                 for the same time as specified into the workload reader.
     * @throws IllegalArgumentException when the workload trace file name is null or empty; or the resource PE mips is less or equal to 0
     * @see #getInstance(String, int)
     */
    private SwfWorkloadFileReader(final String filePath, final InputStream reader, final int mips) {
        super(filePath, reader);

        this.setMips(mips);
        this.cloudlets = new ArrayList<>();

        /*
        A default predicate which indicates that a Cloudlet will be
        created for any job read from the workload reader.
        That is, there isn't an actual condition to create a Cloudlet.
        */
        this.predicate = cloudlet -> true;
    }

    /**
     * Generates a list of jobs ({@link Cloudlet Cloudlets}) to be executed,
     * if it wasn't generated yet.
     *
     * @return a generated Cloudlet list
     */
    public List<Cloudlet> generateWorkload() {
        if (cloudlets.isEmpty()) {
            readFile(this::createCloudletFromTraceLine);
        }

        return cloudlets;
    }

    /**
     * Defines a {@link Predicate} which indicates when a {@link Cloudlet}
     * must be created from a trace line read from the workload file.
     * If a Predicate is not set, a Cloudlet will be created for any line read.
     *
     * @param predicate the predicate to define when a Cloudlet must be created from a line read from the workload file
     * @return
     */
    public SwfWorkloadFileReader setPredicate(final Predicate<Cloudlet> predicate) {
        this.predicate = predicate;
        return this;
    }

    /**
     * Extracts relevant information from a given array of fields, representing
     * a line from the trace reader, and creates a cloudlet using this
     * information.
     *
     * @param parsedLineArray an array containing the field values from a parsed trace line
     * @return true if the parsed line is valid and the Cloudlet was created, false otherwise
     */
    private boolean createCloudletFromTraceLine(final String[] parsedLineArray) {
        //If all the fields couldn't be read, don't create the Cloudlet.
        if (parsedLineArray.length < FIELD_COUNT) {
            return false;
        }

        final int id = JOB_NUM_INDEX <= IRRELEVANT ? cloudlets.size() + 1 : Integer.parseInt(parsedLineArray[JOB_NUM_INDEX].trim());

        /* according to the SWF manual, runtime of 0 is possible due
         to rounding down. E.g. runtime is 0.4 seconds -> runtime = 0*/
        final int runTime = Math.max(Integer.parseInt(parsedLineArray[RUN_TIME_INDEX].trim()), 1);

        /* if the required num of allocated processors field is ignored
        or zero, then use the actual field*/
        final int maxNumProc = Math.max(
                                    Integer.parseInt(parsedLineArray[REQ_NUM_PROC_INDEX].trim()),
                                    Integer.parseInt(parsedLineArray[NUM_PROC_INDEX].trim())
                               );
        final int numProc = Math.max(maxNumProc, 1);

        final Cloudlet cloudlet = createCloudlet(id, runTime, numProc);
        final long submitTime = Long.parseLong(parsedLineArray[SUBMIT_TIME_INDEX].trim());
        cloudlet.setSubmissionDelay(submitTime);

        if(predicate.test(cloudlet)){
            cloudlets.add(cloudlet);
            return true;
        }

        return false;
    }

    /**
     * Creates a Cloudlet with the given information.
     *
     * @param id      a Cloudlet ID
     * @param runTime The number of seconds the Cloudlet has to run.
     *                {@link Cloudlet#getLength()} is computed based on
     *                the {@link #getMips() mips} and this value.
     * @param numProc number of Cloudlet's PEs
     * @return the created Cloudlet
     * @see #mips
     */
    private Cloudlet createCloudlet(final int id, final int runTime, final int numProc) {
        final int len = runTime * mips;
        final UtilizationModel utilizationModel = new UtilizationModelFull();

        return new CloudletSimple(id, len, numProc)
            .setFileSize(DataCloudTags.DEFAULT_MTU)
            .setOutputSize(DataCloudTags.DEFAULT_MTU)
            .setUtilizationModel(utilizationModel);
    }

    /**
     * Gets the MIPS capacity of the PEs from the VM where each created Cloudlet is supposed to run.
     * Considering the workload reader provides the run time for each
     * application registered inside the reader, the MIPS value will be used
     * to compute the {@link Cloudlet#getLength() length of the Cloudlet (in MI)}
     * so that it's expected to execute, inside the VM with the given MIPS capacity,
     * for the same time as specified into the workload reader.
     */
    public int getMips() {
        return mips;
    }

    /**
     * Sets the MIPS capacity of the PEs from the VM where each created Cloudlet is supposed to run.
     * Considering the workload reader provides the run time for each
     * application registered inside the reader, the MIPS value will be used
     * to compute the {@link Cloudlet#getLength() length of the Cloudlet (in MI)}
     * so that it's expected to execute, inside the VM with the given MIPS capacity,
     * for the same time as specified into the workload reader.
     *
     * @param mips the MIPS value to set
     */
    public SwfWorkloadFileReader setMips(final int mips) {
        if (mips <= 0) {
            throw new IllegalArgumentException("MIPS must be greater than 0.");
        }
        this.mips = mips;
        return this;
    }
}
