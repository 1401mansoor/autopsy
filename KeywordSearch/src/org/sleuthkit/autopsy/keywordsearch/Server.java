/*
 * Autopsy Forensic Browser
 *
 * Copyright 2011-2019 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.keywordsearch;

import java.awt.event.ActionEvent;
import java.beans.PropertyChangeListener;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import javax.swing.AbstractAction;
import org.apache.commons.io.FileUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient; // for Single User mode
import org.apache.solr.client.solrj.impl.CloudSolrClient; // for Solr Cloud mode
import org.apache.solr.client.solrj.impl.XMLResponseParser;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.response.CoreAdminResponse;
import java.util.concurrent.TimeoutException;
import org.apache.solr.client.solrj.impl.BaseHttpSolrClient.RemoteSolrException;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.TermsResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.CollectionParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.openide.modules.InstalledFileLocator;
import org.openide.modules.Places;
import org.openide.util.NbBundle;
import org.openide.windows.WindowManager;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.casemodule.Case.CaseType;
import org.sleuthkit.autopsy.casemodule.CaseMetadata;
import org.sleuthkit.autopsy.core.UserPreferences;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.coreutils.MessageNotifyUtil;
import org.sleuthkit.autopsy.coreutils.ModuleSettings;
import org.sleuthkit.autopsy.coreutils.PlatformUtil;
import org.sleuthkit.autopsy.healthmonitor.HealthMonitor;
import org.sleuthkit.autopsy.healthmonitor.TimingMetric;
import org.sleuthkit.autopsy.keywordsearchservice.KeywordSearchServiceException;
import org.sleuthkit.datamodel.Content;

/**
 * Handles management of a either a local or centralized Solr server and its
 * collections or cores.
 */
public class Server {

    /**
     * Solr document field names.
     */
    public static enum Schema {

        ID {
            @Override
            public String toString() {
                return "id"; //NON-NLS
            }
        },
        IMAGE_ID {
            @Override
            public String toString() {
                return "image_id"; //NON-NLS
            }
        },
        // This is not stored or indexed. it is copied to text by the schema
        CONTENT {
            @Override
            public String toString() {
                return "content"; //NON-NLS
            }
        },
        // String representation for regular expression searching
        CONTENT_STR {
            @Override
            public String toString() {
                return "content_str"; //NON-NLS
            }
        },
        // default search field.  Populated by schema
        TEXT {
            @Override
            public String toString() {
                return "text"; //NON-NLS
            }
        },
        // no longer populated.  Was used for regular expression searching.
        // Should not be used. 
        CONTENT_WS {
            @Override
            public String toString() {
                return "content_ws"; //NON-NLS
            }
        },
        CONTENT_JA {
            @Override
            public String toString() {
                return "content_ja"; //NON-NLS
            }
        },
        LANGUAGE {
            @Override
            public String toString() {
                return "language"; //NON-NLS
            }
        },
        FILE_NAME {
            @Override
            public String toString() {
                return "file_name"; //NON-NLS
            }
        },
        // note that we no longer store or index this field
        CTIME {
            @Override
            public String toString() {
                return "ctime"; //NON-NLS
            }
        },
        // note that we no longer store or index this field
        ATIME {
            @Override
            public String toString() {
                return "atime"; //NON-NLS
            }
        },
        // note that we no longer store or index this field
        MTIME {
            @Override
            public String toString() {
                return "mtime"; //NON-NLS
            }
        },
        // note that we no longer store or index this field
        CRTIME {
            @Override
            public String toString() {
                return "crtime"; //NON-NLS
            }
        },
        NUM_CHUNKS {
            @Override
            public String toString() {
                return "num_chunks"; //NON-NLS
            }
        },
        CHUNK_SIZE {
            @Override
            public String toString() {
                return "chunk_size"; //NON-NLS
            }
        },
        /**
         * termfreq is a function which returns the number of times the term
         * appears. This is not an actual field defined in schema.xml, but can
         * be gotten from returned documents in the same way as fields.
         */
        TERMFREQ {
            @Override
            public String toString() {
                return "termfreq"; //NON-NLS
            }
        }
    };

    public static final String HL_ANALYZE_CHARS_UNLIMITED = "500000"; //max 1MB in a chunk. use -1 for unlimited, but -1 option may not be supported (not documented)
    //max content size we can send to Solr
    public static final long MAX_CONTENT_SIZE = 1L * 31 * 1024 * 1024;
    private static final Logger logger = Logger.getLogger(Server.class.getName());
    public static final String CORE_EVT = "CORE_EVT"; //NON-NLS
    @Deprecated
    public static final char ID_CHUNK_SEP = '_';
    public static final String CHUNK_ID_SEPARATOR = "_";
    private String javaPath = "java";
    public static final Charset DEFAULT_INDEXED_TEXT_CHARSET = Charset.forName("UTF-8"); ///< default Charset to index text as
    private Process curSolrProcess = null;
    static final String PROPERTIES_FILE = KeywordSearchSettings.MODULE_NAME;
    static final String PROPERTIES_CURRENT_SERVER_PORT = "IndexingServerPort"; //NON-NLS
    static final String PROPERTIES_CURRENT_STOP_PORT = "IndexingServerStopPort"; //NON-NLS
    private static final String KEY = "jjk#09s"; //NON-NLS
    static final String DEFAULT_SOLR_SERVER_HOST = "localhost"; //NON-NLS
    static final int DEFAULT_SOLR_SERVER_PORT = 23232;
    static final int DEFAULT_SOLR_STOP_PORT = 34343;
    private int currentSolrServerPort = 0;
    private int currentSolrStopPort = 0;
    private static final boolean DEBUG = false;//(Version.getBuildType() == Version.Type.DEVELOPMENT);
    private static final String SOLR = "solr";
    private static final String CORE_PROPERTIES = "core.properties";

    public enum CORE_EVT_STATES {

        STOPPED, STARTED
    };

    // A reference to the locally running Solr instance.
    private final HttpSolrClient localSolrServer;

    // A reference to the Solr server we are currently connected to for the Case.
    // This could be a local or remote server.
// ELTODO    private HttpSolrClient currentSolrServer;
    private final Map<List<String>, CloudSolrClient> solrClients = new HashMap<>();
    private CloudSolrClient currentSolrServer;

    private Collection currentCollection;
    private final ReentrantReadWriteLock currentCoreLock;

    private final File solrFolder;
    private Path solrCmdPath;
    private Path solrHome;
    private final ServerAction serverAction;
    private InputStreamPrinterThread errorRedirectThread;

    /**
     * New instance for the server at the given URL
     *
     */
    Server() {
        initSettings();

        this.localSolrServer = new HttpSolrClient.Builder("http://localhost:" + currentSolrServerPort + "/solr").build(); //NON-NLS
        serverAction = new ServerAction();
        solrFolder = InstalledFileLocator.getDefault().locate("solr", Server.class.getPackage().getName(), false); //NON-NLS
        
        // Figure out where Java is located. The Java home location 
        // will be passed as the SOLR_JAVA_HOME environment
        // variable to the Solr script but it can be overridden by the user in
        // either autopsy-solr.cmd or autopsy-solr-in.cmd.
        javaPath = PlatformUtil.getJavaPath();
        // This is our customized version of the Solr batch script to start/stop Solr.
        solrCmdPath = Paths.get(solrFolder.getAbsolutePath(), "bin", "autopsy-solr.cmd"); //NON-NLS

        solrHome = Paths.get(PlatformUtil.getUserDirectory().getAbsolutePath(), "solr"); //NON-NLS
        if (!solrHome.toFile().exists()) {
            try {
                Files.createDirectory(solrHome);
                Files.copy(Paths.get(solrFolder.getAbsolutePath(), "solr", "solr.xml"), solrHome.resolve("solr.xml")); //NON-NLS
                Files.copy(Paths.get(solrFolder.getAbsolutePath(), "solr", "zoo.cfg"), solrHome.resolve("zoo.cfg")); //NON-NLS
                FileUtils.copyDirectory(Paths.get(solrFolder.getAbsolutePath(), "solr", "configsets").toFile(), solrHome.resolve("configsets").toFile()); //NON-NLS
            } catch (IOException ex) {
                logger.log(Level.SEVERE, "Failed to create Solr home folder:", ex); //NON-NLS
            }
        }
        currentCoreLock = new ReentrantReadWriteLock(true);

        logger.log(Level.INFO, "Created Server instance using Java at {0}", javaPath); //NON-NLS
    }

    private void initSettings() {

        if (ModuleSettings.settingExists(PROPERTIES_FILE, PROPERTIES_CURRENT_SERVER_PORT)) {
            try {
                currentSolrServerPort = Integer.decode(ModuleSettings.getConfigSetting(PROPERTIES_FILE, PROPERTIES_CURRENT_SERVER_PORT));
            } catch (NumberFormatException nfe) {
                logger.log(Level.WARNING, "Could not decode indexing server port, value was not a valid port number, using the default. ", nfe); //NON-NLS
                currentSolrServerPort = DEFAULT_SOLR_SERVER_PORT;
            }
        } else {
            currentSolrServerPort = DEFAULT_SOLR_SERVER_PORT;
            ModuleSettings.setConfigSetting(PROPERTIES_FILE, PROPERTIES_CURRENT_SERVER_PORT, String.valueOf(currentSolrServerPort));
        }

        if (ModuleSettings.settingExists(PROPERTIES_FILE, PROPERTIES_CURRENT_STOP_PORT)) {
            try {
                currentSolrStopPort = Integer.decode(ModuleSettings.getConfigSetting(PROPERTIES_FILE, PROPERTIES_CURRENT_STOP_PORT));
            } catch (NumberFormatException nfe) {
                logger.log(Level.WARNING, "Could not decode indexing server stop port, value was not a valid port number, using default", nfe); //NON-NLS
                currentSolrStopPort = DEFAULT_SOLR_STOP_PORT;
            }
        } else {
            currentSolrStopPort = DEFAULT_SOLR_STOP_PORT;
            ModuleSettings.setConfigSetting(PROPERTIES_FILE, PROPERTIES_CURRENT_STOP_PORT, String.valueOf(currentSolrStopPort));
        }
    }

    synchronized CloudSolrClient getCloudSolrClient(List<String> solrUrls) {
        CloudSolrClient client;
        if (solrClients.containsKey(solrUrls)) {
            logger.log(Level.INFO, "Re-using exiting CloudSolrClient"); //NON-NLS
            client = solrClients.get(solrUrls);
        } else {
            //client = new CloudSolrClient.Builder()
            //        .withZkHost(zkHost)
            //        .build();
            logger.log(Level.INFO, "Creating new CloudSolrClient"); //NON-NLS
            client = new CloudSolrClient.Builder(solrUrls).build();
            client.connect();
            solrClients.put(solrUrls, client);
        }

        return client;
    }

    @Override
    public void finalize() throws java.lang.Throwable {
        stop();
        super.finalize();
    }

    public void addServerActionListener(PropertyChangeListener l) {
        serverAction.addPropertyChangeListener(l);
    }

    int getCurrentSolrServerPort() {
        return currentSolrServerPort;
    }

    int getCurrentSolrStopPort() {
        return currentSolrStopPort;
    }

    /**
     * Helper threads to handle stderr/stdout from Solr process
     */
    private static class InputStreamPrinterThread extends Thread {

        InputStream stream;
        OutputStream out;
        volatile boolean doRun = true;

        InputStreamPrinterThread(InputStream stream, String type) {
            this.stream = stream;
            try {
                final String log = Places.getUserDirectory().getAbsolutePath()
                        + File.separator + "var" + File.separator + "log" //NON-NLS
                        + File.separator + "solr.log." + type; //NON-NLS
                File outputFile = new File(log.concat(".0"));
                File first = new File(log.concat(".1"));
                File second = new File(log.concat(".2"));
                if (second.exists()) {
                    second.delete();
                }
                if (first.exists()) {
                    first.renameTo(second);
                }
                if (outputFile.exists()) {
                    outputFile.renameTo(first);
                } else {
                    outputFile.createNewFile();
                }
                out = new FileOutputStream(outputFile);

            } catch (Exception ex) {
                logger.log(Level.WARNING, "Failed to create solr log file", ex); //NON-NLS
            }
        }

        void stopRun() {
            doRun = false;
        }

        @Override
        public void run() {

            try (InputStreamReader isr = new InputStreamReader(stream);
                    BufferedReader br = new BufferedReader(isr);
                    OutputStreamWriter osw = new OutputStreamWriter(out, PlatformUtil.getDefaultPlatformCharset());
                    BufferedWriter bw = new BufferedWriter(osw);) {

                String line = null;
                while (doRun && (line = br.readLine()) != null) {
                    bw.write(line);
                    bw.newLine();
                    if (DEBUG) {
                        //flush buffers if dev version for debugging
                        bw.flush();
                    }
                }
                bw.flush();
            } catch (IOException ex) {
                logger.log(Level.SEVERE, "Error redirecting Solr output stream", ex); //NON-NLS
            }
        }
    }

    /**
     * Run a Solr command with the given arguments.
     *
     * @param solrArguments Command line arguments to pass to the Solr command.
     *
     * @return
     *
     * @throws IOException
     */
    private Process runSolrCommand(List<String> solrArguments) throws IOException {
        final String MAX_SOLR_MEM_MB_PAR = "-Xmx" + UserPreferences.getMaxSolrVMSize() + "m"; //NON-NLS

        List<String> commandLine = new ArrayList<>();
	commandLine.add(solrCmdPath.toString());
        commandLine.addAll(solrArguments);

        ProcessBuilder solrProcessBuilder = new ProcessBuilder(commandLine);
        solrProcessBuilder.directory(solrFolder);

        // Redirect stdout and stderr to files to prevent blocking.
        Path solrStdoutPath = Paths.get(Places.getUserDirectory().getAbsolutePath(), "var", "log", "solr.log.stdout"); //NON-NLS
        solrProcessBuilder.redirectOutput(solrStdoutPath.toFile());

        Path solrStderrPath = Paths.get(Places.getUserDirectory().getAbsolutePath(), "var", "log", "solr.log.stderr"); //NON-NLS
        solrProcessBuilder.redirectError(solrStderrPath.toFile());
        
        solrProcessBuilder.environment().put("SOLR_JAVA_HOME", javaPath); // NON-NLS
        solrProcessBuilder.environment().put("SOLR_HOME", solrHome.toString()); // NON-NLS
        solrProcessBuilder.environment().put("STOP_KEY", KEY); // NON-NLS 
        solrProcessBuilder.environment().put("SOLR_JAVA_MEM", MAX_SOLR_MEM_MB_PAR); // NON-NLS 
        logger.log(Level.INFO, "Running Solr command: {0}", solrProcessBuilder.command()); //NON-NLS
        Process process = solrProcessBuilder.start();
        logger.log(Level.INFO, "Finished running Solr command"); //NON-NLS
        return process;
    }

    /**
     * Get list of PIDs of currently running Solr processes
     *
     * @return
     */
    List<Long> getSolrPIDs() {
        List<Long> pids = new ArrayList<>();

        //NOTE: these needs to be in sync with process start string in start()
        final String pidsQuery = "Args.*.eq=-DSTOP.KEY=" + KEY + ",Args.*.eq=start.jar"; //NON-NLS

        long[] pidsArr = PlatformUtil.getJavaPIDs(pidsQuery);
        if (pidsArr != null) {
            for (int i = 0; i < pidsArr.length; ++i) {
                pids.add(pidsArr[i]);
            }
        }

        return pids;
    }

    /**
     * Kill residual Solr processes. Note, this method should be used only if
     * Solr could not be stopped in a graceful manner.
     */
    void killSolr() {
        List<Long> solrPids = getSolrPIDs();
        for (long pid : solrPids) {
            logger.log(Level.INFO, "Trying to kill old Solr process, PID: {0}", pid); //NON-NLS
            PlatformUtil.killProcess(pid);
        }
    }

    /**
     * Tries to start a local Solr instance in a separate process. Returns
     * immediately (probably before the server is ready) and doesn't check
     * whether it was successful.
     */
    @NbBundle.Messages({
        "Server.status.failed.msg=Local Solr server did not respond to status request. This may be because the server failed to start or is taking too long to initialize.",})
    void start() throws KeywordSearchModuleException, SolrServerNoPortException {
        if (isEmbeddedSolrRunning()) {
            // If a Solr server is running we stop it.
            stop();
        }

        if (!isPortAvailable(currentSolrServerPort)) {
            // There is something already listening on our port. Let's see if
            // this is from an earlier run that didn't successfully shut down
            // and if so kill it.
            final List<Long> pids = this.getSolrPIDs();

            // If the culprit listening on the port is not a Solr process
            // we refuse to start.
            if (pids.isEmpty()) {
                throw new SolrServerNoPortException(currentSolrServerPort);
            }

            // Ok, we've tried to stop it above but there still appears to be
            // a Solr process listening on our port so we forcefully kill it.
            killSolr();

            // If either of the ports are still in use after our attempt to kill 
            // previously running processes we give up and throw an exception.
            if (!isPortAvailable(currentSolrServerPort)) {
                throw new SolrServerNoPortException(currentSolrServerPort);
            }
            if (!isPortAvailable(currentSolrStopPort)) {
                throw new SolrServerNoPortException(currentSolrStopPort);
            }
        }

        logger.log(Level.INFO, "Starting Solr server from: {0}", solrFolder.getAbsolutePath()); //NON-NLS

        if (isPortAvailable(currentSolrServerPort)) {
            logger.log(Level.INFO, "Port [{0}] available, starting Solr", currentSolrServerPort); //NON-NLS
            try {
                curSolrProcess = runSolrCommand(new ArrayList<>(Arrays.asList("start", "-p", //NON-NLS
					Integer.toString(currentSolrServerPort),
                        		"-Dbootstrap_confdir=../solr/configsets/AutopsyConfig/conf", //NON-NLS
                                        "-Dcollection.configName=AutopsyConfig"))); //NON-NLS

                // Wait for the Solr server to start and respond to a statusRequest request.
                for (int numRetries = 0; numRetries < 6; numRetries++) {
                    if (isEmbeddedSolrRunning()) {
                        final List<Long> pids = this.getSolrPIDs();
                        logger.log(Level.INFO, "New Solr process PID: {0}", pids); //NON-NLS
                        return;
                    }

                    // Local Solr server did not respond so we sleep for
                    // 5 seconds before trying again.
                    try {
                        TimeUnit.SECONDS.sleep(5);
                    } catch (InterruptedException ex) {
                        logger.log(Level.WARNING, "Timer interrupted"); //NON-NLS
                    }
                }

                // If we get here the Solr server has not responded to connection
                // attempts in a timely fashion.
                logger.log(Level.WARNING, "Local Solr server failed to respond to status requests.");
                WindowManager.getDefault().invokeWhenUIReady(new Runnable() {
                    @Override
                    public void run() {
                        MessageNotifyUtil.Notify.error(
                                NbBundle.getMessage(this.getClass(), "Installer.errorInitKsmMsg"),
                                Bundle.Server_status_failed_msg());
                    }
                });
            } catch (SecurityException ex) {
                logger.log(Level.SEVERE, "Could not start Solr process!", ex); //NON-NLS
                throw new KeywordSearchModuleException(
                        NbBundle.getMessage(this.getClass(), "Server.start.exception.cantStartSolr.msg"), ex);
            } catch (IOException ex) {
                logger.log(Level.SEVERE, "Could not start Solr server process!", ex); //NON-NLS
                throw new KeywordSearchModuleException(
                        NbBundle.getMessage(this.getClass(), "Server.start.exception.cantStartSolr.msg2"), ex);
            }
        }
    }

    /**
     * Checks to see if a specific port is available.
     *
     * @param port the port to check for availability
     */
    static boolean isPortAvailable(int port) {
        ServerSocket ss = null;
        try {

            ss = new ServerSocket(port, 0, java.net.Inet4Address.getByName("localhost")); //NON-NLS
            if (ss.isBound()) {
                ss.setReuseAddress(true);
                ss.close();
                return true;
            }

        } catch (IOException e) {
        } finally {
            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException e) {
                    /*
                     * should not be thrown
                     */
                }
            }
        }
        return false;
    }

    /**
     * Changes the current solr server port. Only call this after available.
     *
     * @param port Port to change to
     */
    void changeSolrServerPort(int port) {
        currentSolrServerPort = port;
        ModuleSettings.setConfigSetting(PROPERTIES_FILE, PROPERTIES_CURRENT_SERVER_PORT, String.valueOf(port));
    }

    /**
     * Changes the current solr stop port. Only call this after available.
     *
     * @param port Port to change to
     */
    void changeSolrStopPort(int port) {
        currentSolrStopPort = port;
        ModuleSettings.setConfigSetting(PROPERTIES_FILE, PROPERTIES_CURRENT_STOP_PORT, String.valueOf(port));
    }

    /**
     * Tries to stop the local Solr instance.
     *
     * Waits for the stop command to finish before returning.
     */
    synchronized void stop() {

        try {
            // Close any open core before stopping server
            closeCore();
        } catch (KeywordSearchModuleException e) {
            logger.log(Level.WARNING, "Failed to close core: ", e); //NON-NLS
        }

        try {
            logger.log(Level.INFO, "Stopping Solr server from: {0}", solrFolder.getAbsolutePath()); //NON-NLS

            //try graceful shutdown
            Process process = runSolrCommand(new ArrayList<>(Arrays.asList("stop", "-k", KEY, "-p", Integer.toString(currentSolrServerPort)))); //NON-NLS

            logger.log(Level.INFO, "Waiting for Solr server to stop"); //NON-NLS
            process.waitFor();

            //if still running, forcefully stop it
            if (curSolrProcess != null) {
                curSolrProcess.destroy();
                curSolrProcess = null;
            }

        } catch (IOException | InterruptedException ex) {
            logger.log(Level.WARNING, "Error while attempting to stop Solr server", ex);
        } finally {
            //stop Solr stream -> log redirect threads
            try {
                if (errorRedirectThread != null) {
                    errorRedirectThread.stopRun();
                    errorRedirectThread = null;
                }
            } finally {
                //if still running, kill it
                killSolr();
            }

            logger.log(Level.INFO, "Finished stopping Solr server"); //NON-NLS
        }
    }

    /**
     * Tests if there's a local Solr server running by sending it a core-statusRequest
 request.
     *
     * @return false if the request failed with a connection error, otherwise
     * true
     */
    synchronized boolean isEmbeddedSolrRunning() throws KeywordSearchModuleException {
        try {

            if (isPortAvailable(currentSolrServerPort)) {
                return false;
            }

            // making a statusRequest request here instead of just doing solrServer.ping(), because
            // that doesn't work when there are no cores
            //TODO handle timeout in cases when some other type of server on that port
            connectToEbmeddedSolrServer(localSolrServer);

            logger.log(Level.INFO, "Solr server is running"); //NON-NLS
        } catch (SolrServerException ex) {

            Throwable cause = ex.getRootCause();

            // TODO: check if SocketExceptions should actually happen (is
            // probably caused by starting a connection as the server finishes
            // shutting down)
            if (cause instanceof ConnectException || cause instanceof SocketException) { //|| cause instanceof NoHttpResponseException) {
                logger.log(Level.INFO, "Solr server is not running, cause: {0}", cause.getMessage()); //NON-NLS
                return false;
            } else {
                throw new KeywordSearchModuleException(
                        NbBundle.getMessage(this.getClass(), "Server.isRunning.exception.errCheckSolrRunning.msg"), ex);
            }
        } catch (SolrException ex) {
            // Just log 404 errors for now...
            logger.log(Level.INFO, "Solr server is not running", ex); //NON-NLS
            return false;
        } catch (IOException ex) {
            throw new KeywordSearchModuleException(
                    NbBundle.getMessage(this.getClass(), "Server.isRunning.exception.errCheckSolrRunning.msg2"), ex);
        }

        return true;
    }

    /*
     * ** Convenience methods for use while we only open one case at a time ***
     */
    /**
     * Creates/opens a Solr core (index) for a case.
     *
     * @param theCase The case for which the core is to be created/opened.
     * @param index The text index that the Solr core should be using.
     *
     * @throws KeywordSearchModuleException If an error occurs while
     * creating/opening the core.
     */
    void openCoreForCase(Case theCase, Index index) throws KeywordSearchModuleException {
        currentCoreLock.writeLock().lock();
        try {
            currentCollection = openCore(theCase, index);

            try {
                // execute a test query. if it fails, an exception will be thrown
                queryNumIndexedFiles();
            } catch (NoOpenCoreException ex) {
                throw new KeywordSearchModuleException(NbBundle.getMessage(this.getClass(), "Server.openCore.exception.cantOpen.msg"), ex);
            }

            serverAction.putValue(CORE_EVT, CORE_EVT_STATES.STARTED);
        } finally {
            currentCoreLock.writeLock().unlock();
        }
    }

    /**
     * Determines whether or not there is a currently open core (index).
     *
     * @return true or false
     */
    boolean coreIsOpen() {
        currentCoreLock.readLock().lock();
        try {
            return (null != currentCollection);
        } finally {
            currentCoreLock.readLock().unlock();
        }
    }

    Index getIndexInfo() throws NoOpenCoreException {
        currentCoreLock.readLock().lock();
        try {
            if (null == currentCollection) {
                throw new NoOpenCoreException();
            }
            return currentCollection.getIndexInfo();
        } finally {
            currentCoreLock.readLock().unlock();
        }
    }

    void closeCore() throws KeywordSearchModuleException {
        currentCoreLock.writeLock().lock();
        try {
            if (null != currentCollection) {
                currentCollection.close();
                currentCollection = null;
                serverAction.putValue(CORE_EVT, CORE_EVT_STATES.STOPPED);
            }
        } finally {
            currentCoreLock.writeLock().unlock();
        }
    }

    void addDocument(SolrInputDocument doc) throws KeywordSearchModuleException, NoOpenCoreException {
        currentCoreLock.readLock().lock();
        try {
            if (null == currentCollection) {
                throw new NoOpenCoreException();
            }
            TimingMetric metric = HealthMonitor.getTimingMetric("Solr: Index chunk");
            currentCollection.addDocument(doc);
            HealthMonitor.submitTimingMetric(metric);
        } finally {
            currentCoreLock.readLock().unlock();
        }
    }

    /**
     * ** end single-case specific methods ***
     */
    /**
     * Deletes the keyword search core for a case.
     *
     * @param coreName The core name.
     */
    @NbBundle.Messages({
        "# {0} - core name", "Server.deleteCore.exception.msg=Failed to delete Solr core {0}",})
    void deleteCore(String coreName, CaseMetadata metadata) throws KeywordSearchServiceException {
        try {
            //IndexingServerProperties properties = getMultiUserServerProperties(metadata.getCaseDirectory());
            List<String> solrServerList = UserPreferences.getAllIndexingServers();
            List<String> solrUrls = new ArrayList<>();
            for (String server : solrServerList) {
                solrUrls.add("http://" + server + "/solr");
            }
            //solrUrls.add("http://" + properties.getHost() + ":" + properties.getPort() + "/solr");
            CloudSolrClient solrServer = new CloudSolrClient.Builder(solrUrls).build();
            
            //HttpSolrClient solrServer;
            if (metadata.getCaseType() == CaseType.SINGLE_USER_CASE) {
                Integer localSolrServerPort = Integer.decode(ModuleSettings.getConfigSetting(PROPERTIES_FILE, PROPERTIES_CURRENT_SERVER_PORT));
                // ELTODO solrServer = new HttpSolrClient.Builder("http://localhost:" + localSolrServerPort + "/solr").build(); //NON-NLS
            } else {
                //IndexingServerProperties properties = getMultiUserServerProperties(metadata.getCaseDirectory());
                // ELTODO solrServer = new CloudSolrClient.Builder().withZkHost(zkHosts).build();
                // ELTODO solrServer = new HttpSolrClient.Builder("http://" + properties.getHost() + ":" + properties.getPort() + "/solr").build(); //NON-NLS
            }
            connectToSolrServer(solrServer);
            
            CollectionAdminRequest.Delete deleteCollectionRequest = CollectionAdminRequest.deleteCollection(coreName);
            CollectionAdminResponse response = deleteCollectionRequest.process(solrServer);
            if (response.isSuccess()) {
                logger.log(Level.INFO, "Deleted collection {0}", coreName); //NON-NLS
            } else {
                logger.log(Level.WARNING, "Unable to delete collection {0}", coreName); //NON-NLS
            }
            /* It is possible to send a core unload request to the Solr server, with the
                 * parameter set that request deleting the index and the
                 * instance directory (deleteInstanceDir = true). Note that this
                 * removes everything related to the core on the server (the
                 * index directory, the configuration files, etc.), but does not
                 * delete the actual Solr text index because it is currently
                 * stored in the case directory.
             */
        } catch (SolrServerException | IOException ex) {
            // We will get a RemoteSolrException with cause == null and detailsMessage
            // == "Already closed" if the core is not loaded. This is not an error in this scenario.
            if (!ex.getMessage().equals("Already closed")) { // NON-NLS
                throw new KeywordSearchServiceException(Bundle.Server_deleteCore_exception_msg(coreName), ex);
            }
        }
    }

    /**
     * Creates/opens a Solr core (index) for a case.
     *
     * @param theCase The case for which the core is to be created/opened.
     * @param index The text index that the Solr core should be using.
     *
     * @return An object representing the created/opened core.
     *
     * @throws KeywordSearchModuleException If an error occurs while
     * creating/opening the core.
     */
    @NbBundle.Messages({
        "Server.exceptionMessage.unableToCreateCollection=Unable to create Solr collection",
    })
    private Collection openCore(Case theCase, Index index) throws KeywordSearchModuleException {

        List<String> solrUrls = new ArrayList<>();
        try {
            if (theCase.getCaseType() == CaseType.SINGLE_USER_CASE) {
                // ELTODO make embedded Solr work with localSolrServer
                // currentSolrServer = this.localSolrServer;

                // check if the embedded Solr server is running
                if (!this.isEmbeddedSolrRunning()) {
                    logger.log(Level.SEVERE, "Embedded Solr server is not running"); //NON-NLS
                    throw new KeywordSearchModuleException(NbBundle.getMessage(this.getClass(), "Server.openCore.exception.msg")); 
                }
                
                // ELTODO connectToEbmeddedSolrServer(currentSolrServer);                
            } else {
                /*List<String> solrServerList = UserPreferences.getAllIndexingServers();
                for (String server : solrServerList) {
                    solrUrls.add("http://" + server + "/solr"); // ELTODO optimize this
                    logger.log(Level.INFO, "Using Solr server: {0}", server);
                }*/
                // connect to the Solr server that was specified in MU options
                solrUrls.add("http://" + UserPreferences.getIndexingServerHost() + ":" + UserPreferences.getIndexingServerPort() + "/solr");
                currentSolrServer = getCloudSolrClient(solrUrls);
                
                // get list of all live Solr servers in the cluster
                List<String> solrServerList = getSolrServerList();
                if (solrServerList.size() > 1) {
                    // there are multiple Solr servers in the Solr Cloud
                    solrUrls.clear();
                    for (String server : solrServerList) {
                        String severHostName = server.substring(0, server.indexOf("_solr"));
                        solrUrls.add("http://" + severHostName + "/solr"); // ELTODO optimize this
                        logger.log(Level.INFO, "Using Solr server: {0}", server);
                    }
                    // connect to all live Solr servers
                    currentSolrServer = getCloudSolrClient(solrUrls);
                }
                UserPreferences.setMaxNumShards(solrUrls.size());
                UserPreferences.setMaxNumShards(1); // ELTODO REMOVE THIS!
                currentSolrServer.setDefaultCollection(null);
                
                // ELTODO currentSolrServer = new CloudSolrClient.Builder(solrUrls).build(); // (new CloudSolrClient.Builder(solrUrls));
                // ELTODO currentSolrServer.connect(10, TimeUnit.SECONDS);
                
                /* ELTODO 
                String zkHost = "localhost"; //properties.getHost();
                String zkPort = "9983";
                List<String> zkHosts = new ArrayList<>();
                zkHosts.add(zkHost + ":" + zkPort);
                currentSolrServer = new CloudSolrClient.Builder(zkHosts, Optional.empty()).build();
                currentSolrServer.connect();*/

                //currentSolrServer = new HttpSolrClient.Builder("http://" + properties.getHost() + ":" + properties.getPort() + "/solr").build(); //NON-NLS
                // ELTODO connectToSolrServer(currentSolrServer);
            }
        } catch (/* ELTODO SolrServerException | IOException*/ Exception ex) {
            throw new KeywordSearchModuleException(NbBundle.getMessage(Server.class, "Server.connect.exception.msg", ex.getLocalizedMessage()), ex);
        }

        try {
            File dataDir = new File(new File(index.getIndexPath()).getParent()); // "data dir" is the parent of the index directory
            if (!dataDir.exists()) {
                dataDir.mkdirs();
            }

            String collectionName = index.getIndexName();
            
            if (theCase.getCaseType() == CaseType.MULTI_USER_CASE) {
                if (!clusterStatusWithCollection(collectionName)) {
                // ELTODO if (!collectionExists(collectionName)) {
                    /*
                    * The core either does not exist or it is not loaded. Make a
                    * request that will cause the core to be created.
                    */
                    int NUM_RETRIES = 10;
                    boolean doRetry = false;
                    for (int reTryAttempt = 0; reTryAttempt < NUM_RETRIES; reTryAttempt++) {
                        try {
                            doRetry = false;
                            createMultiUserCollection(collectionName, dataDir.getAbsolutePath(), UserPreferences.getMaxNumShards());
                        } catch (Exception ex) {
                            if (reTryAttempt >= NUM_RETRIES) {
                                logger.log(Level.SEVERE, "Unable to create Solr collection " + collectionName, ex); //NON-NLS
                                throw new KeywordSearchModuleException(NbBundle.getMessage(this.getClass(), "Server.openCore.exception.cantOpen.msg"), ex);
                            } else {
                                logger.log(Level.SEVERE, "Unable to create Solr collection " + collectionName + ". Re-trying...", ex); //NON-NLS
                                Thread.sleep(1000L);
                                doRetry = true;
                            }
                        }
                        if (!doRetry) {
                            // ELTODO testing backup and restore
                            //backupCollection(collectionName, collectionName + "_backup");
                            //restoreCollection(collectionName + "_backup", collectionName + "_restored");
                            break;
                        }
                    }
                } else {
                    // collection exists, we are opening an existing case
                    // ELTODO testing backup, delete and restore
                    //backupCollection(collectionName, collectionName + "_backup");
                    
                    // delete the collection
                    //deleteCollection(collectionName);
                    
                    // restore into the same collection name
                    //restoreCollection(collectionName + "_backup", collectionName);
                }
            } else {                
                // In single user mode, if there is a core.properties file already,
                // we've hit a solr bug. Compensate by deleting it.
                if (theCase.getCaseType() == CaseType.SINGLE_USER_CASE) {
                    Path corePropertiesFile = Paths.get(solrFolder.toString(), SOLR, collectionName, CORE_PROPERTIES);
                    if (corePropertiesFile.toFile().exists()) {
                        try {
                            corePropertiesFile.toFile().delete();
                        } catch (Exception ex) {
                            logger.log(Level.INFO, "Could not delete pre-existing core.properties prior to opening the core."); //NON-NLS
                        }
                    }
                }

                // for single user cases, we unload the core when we close the case. So we have to load the core again. 
                CoreAdminRequest.Create createCoreRequest = new CoreAdminRequest.Create();
                createCoreRequest.setDataDir(dataDir.getAbsolutePath());
                createCoreRequest.setCoreName(collectionName);
                createCoreRequest.setConfigSet("AutopsyConfig"); //NON-NLS
                createCoreRequest.setIsLoadOnStartup(false);
                createCoreRequest.setIsTransient(true);
                currentSolrServer.request(createCoreRequest);

                if (!coreIndexFolderExists(collectionName)) {
                    throw new KeywordSearchModuleException(NbBundle.getMessage(this.getClass(), "Server.openCore.exception.noIndexDir.msg"));
                }
            }

            return new Collection(collectionName, theCase, index, solrUrls);

        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Exception during Solr collection creation.", ex); //NON-NLS
            throw new KeywordSearchModuleException(NbBundle.getMessage(this.getClass(), "Server.openCore.exception.cantOpen.msg"), ex);
        }
    }
    
    private boolean clusterStatusWithCollection(String collectionName) throws IOException, SolrServerException {
        ModifiableSolrParams params = new ModifiableSolrParams();
        params.set("action", CollectionParams.CollectionAction.CLUSTERSTATUS.toString());
        params.set("collection", collectionName);
        SolrRequest request = new QueryRequest(params);
        request.setPath("/admin/collections");

        NamedList<Object> rsp;
        try {
            rsp = currentSolrServer.request(request);
        } catch (RemoteSolrException ex) {
            // collection doesn't exist
            return false;
        }
        
        if (rsp == null) {
            logger.log(Level.SEVERE, "Collections response should not be null"); //NON-NLS
            return false;
        }
        
        NamedList<Object> cluster = (NamedList<Object>) rsp.get("cluster");
        if (cluster == null) {
            logger.log(Level.SEVERE, "Cluster should not be null"); //NON-NLS
            return false;
        }
        NamedList<Object> collections = (NamedList<Object>) cluster.get("collections");
        if (cluster == null) {
            logger.log(Level.SEVERE, "Collections should not be null in cluster state"); //NON-NLS
            return false;
        }
        if (collections.size() == 0) {
            logger.log(Level.SEVERE, "Collections should not be empty in cluster state"); //NON-NLS
            return false;
        } 

        // NOTE: in case we need this, core name is (collectionName + "_shard1_replica_n1")
        Object collection = collections.get(collectionName);
        return (collection != null);

        //Map<String, Object=""> collection = (Map < String, Object = "" >) collections.get(collectionName);
        //assertNotNull(collection);
        //assertEquals("conf1", collection.get("configName"));
    }
    
    private void createMultiUserCollection(String collectionName, String dataDirPath, int numShardsToUse) throws KeywordSearchModuleException, SolrServerException, IOException {
        /*
        * The core either does not exist or it is not loaded. Make a
        * request that will cause the core to be created if it does not
        * exist or loaded if it already exists.
        */

        //Properties properties = new Properties(); 
        //properties.setProperty("dataDir", dataDirPath);
        //logger.log(Level.INFO, "dataDirPath: {0}", dataDirPath);
        // properties.setProperty("transient", "true");
        // properties.setProperty("loadOnStartup", "false");

        Integer numShards = numShardsToUse;
        logger.log(Level.INFO, "numShardsToUse: {0}", numShardsToUse);
        Integer numNrtReplicas = 1;
        Integer numTlogReplicas = 0;
        Integer numPullReplicas = 0;
        CollectionAdminRequest.Create createCollectionRequest = CollectionAdminRequest.createCollection(collectionName, "AutopsyConfig", numShards, numNrtReplicas, numTlogReplicas, numPullReplicas);
        //        .setProperties(properties);
        //CollectionAdminRequest.Create createCollectionRequest = CollectionAdminRequest.createCollection(collectionName, "AutopsyConfig", numShards, numNrtReplicas, numTlogReplicas, numPullReplicas);

        CollectionAdminResponse createResponse = createCollectionRequest.process(currentSolrServer);
        if (createResponse.isSuccess()) {
            logger.log(Level.INFO, "Collection {0} successfully created.", collectionName);
        } else {
            logger.log(Level.SEVERE, "Unable to create Solr collection {0}", collectionName); //NON-NLS
            throw new KeywordSearchModuleException(Bundle.Server_exceptionMessage_unableToCreateCollection());
        }

        /* If we need core name:
        Map<String, NamedList<Integer>> status = createResponse.getCollectionCoresStatus();
        existingCoreName = status.keySet().iterator().next();*/
        if (!collectionExists(collectionName)) {
            throw new KeywordSearchModuleException(NbBundle.getMessage(this.getClass(), "Server.openCore.exception.noIndexDir.msg"));
        }
    }
    
    private void backupCollection(String collectionName, String backupName) throws SolrServerException, IOException, KeywordSearchModuleException {
        CollectionAdminRequest.Backup backup = CollectionAdminRequest.backupCollection(collectionName, backupName)
                .setLocation("C:\\TEST\\DELETE\\BACKUPS");
        
        CollectionAdminResponse backupResponse = backup.process(currentSolrServer);
        if (backupResponse.isSuccess()) {
            logger.log(Level.INFO, "Collection {0} successfully backep up.", collectionName);
        } else {
            logger.log(Level.SEVERE, "Unable to back up Solr collection {0}", collectionName); //NON-NLS
            throw new KeywordSearchModuleException(Bundle.Server_exceptionMessage_unableToCreateCollection()); // ELTODO
        }
    }
    
    private void restoreCollection(String backupName, String restoreCollectionName) throws SolrServerException, IOException, KeywordSearchModuleException {
        
        CollectionAdminRequest.Restore restore = CollectionAdminRequest.restoreCollection(restoreCollectionName, backupName)
                .setLocation("C:\\TEST\\DELETE\\BACKUPS");
        
        CollectionAdminResponse restoreResponse = restore.process(currentSolrServer);
        if (restoreResponse.isSuccess()) {
            logger.log(Level.INFO, "Collection {0} successfully resored.", restoreCollectionName);
        } else {
            logger.log(Level.SEVERE, "Unable to restore Solr collection {0}", restoreCollectionName); //NON-NLS
            throw new KeywordSearchModuleException(Bundle.Server_exceptionMessage_unableToCreateCollection()); // ELTODO
        }
    }
    
    private void deleteCollection(String coreName) throws KeywordSearchServiceException, SolrServerException, IOException {
        
        CollectionAdminRequest.Delete deleteCollectionRequest = CollectionAdminRequest.deleteCollection(coreName);
        CollectionAdminResponse response = deleteCollectionRequest.process(currentSolrServer);
        if (response.isSuccess()) {
            logger.log(Level.INFO, "Deleted collection {0}", coreName); //NON-NLS
        } else {
            logger.log(Level.WARNING, "Unable to delete collection {0}", coreName); //NON-NLS
        }
    }
    
    /**
     * Determines whether or not a particular Solr core exists and is loaded.
     *
     * @param coreName The name of the core.
     *
     * @return True if the core exists and is loaded, false if the core does not
     * exist or is not loaded
     *
     * @throws SolrServerException If there is a problem communicating with the
     * Solr server.
     * @throws IOException If there is a problem communicating with the Solr
     * server.
     */
    private boolean coreIsLoaded(String coreName) throws SolrServerException, IOException {
        CoreAdminResponse response = CoreAdminRequest.getStatus(coreName, currentSolrServer);
        return response.getCoreStatus(coreName).get("instanceDir") != null; //NON-NLS
    }
    
    /**
     * Determines whether or not the index files folder for a Solr core
     * exists.
     *
     * @param coreName the name of the core.
     *
     * @return true or false
     *
     * @throws SolrServerException
     * @throws IOException
     */
    private boolean coreIndexFolderExists(String coreName) throws SolrServerException, IOException {
        CoreAdminResponse response = CoreAdminRequest.getStatus(coreName, currentSolrServer);
        Object dataDirPath = response.getCoreStatus(coreName).get("dataDir"); //NON-NLS
        if (null != dataDirPath) {
            File indexDir = Paths.get((String) dataDirPath, "index").toFile();  //NON-NLS
            return indexDir.exists();
        } else {
            return false;
        }
    }    

    /**
     * Get the host and port for a multiuser case. If the file solrserver.txt
     * exists, then use the values from that file. Otherwise use the settings
     * from the properties file.
     *
     * @param caseDirectory Current case directory
     * @return IndexingServerProperties containing the solr host/port for this
     * case
     */
    public static IndexingServerProperties getMultiUserServerProperties(String caseDirectory) {

        Path serverFilePath = Paths.get(caseDirectory, "solrserver.txt");
        if (serverFilePath.toFile().exists()) {
            try {
                List<String> lines = Files.readAllLines(serverFilePath);
                if (lines.isEmpty()) {
                    logger.log(Level.SEVERE, "solrserver.txt file does not contain any data");
                } else if (!lines.get(0).contains(",")) {
                    logger.log(Level.SEVERE, "solrserver.txt file is corrupt - could not read host/port from " + lines.get(0));
                } else {
                    String[] parts = lines.get(0).split(",");
                    if (parts.length != 2) {
                        logger.log(Level.SEVERE, "solrserver.txt file is corrupt - could not read host/port from " + lines.get(0));
                    } else {
                        return new IndexingServerProperties(parts[0], parts[1]);
                    }
                }
            } catch (IOException ex) {
                logger.log(Level.SEVERE, "solrserver.txt file could not be read", ex);
            }
        }

        // Default back to the user preferences if the solrserver.txt file was not found or if an error occurred
        String host = UserPreferences.getIndexingServerHost();
        String port = UserPreferences.getIndexingServerPort();
        return new IndexingServerProperties(host, port);
    }

    /**
     * Pick a solr server to use for this case and record it in the case
     * directory. Looks for a file named "solrServerList.txt" in the root output
     * directory - if this does not exist then no server is recorded.
     *
     * Format of solrServerList.txt: (host),(port) Ex: 10.1.2.34,8983
     *
     * @param rootOutputDirectory
     * @param caseDirectoryPath
     * @throws KeywordSearchModuleException
     */
    public static void selectSolrServerForCase(Path rootOutputDirectory, Path caseDirectoryPath) throws KeywordSearchModuleException {
        // Look for the solr server list file
        String serverListName = "solrServerList.txt";
        Path serverListPath = Paths.get(rootOutputDirectory.toString(), serverListName);
        if (serverListPath.toFile().exists()) {

            // Read the list of solr servers
            List<String> lines;
            try {
                lines = Files.readAllLines(serverListPath);
            } catch (IOException ex) {
                throw new KeywordSearchModuleException(serverListName + " could not be read", ex);
            }

            // Remove any lines that don't contain a comma (these are likely just whitespace)
            for (Iterator<String> iterator = lines.iterator(); iterator.hasNext();) {
                String line = iterator.next();
                if (!line.contains(",")) {
                    // Remove the current element from the iterator and the list.
                    iterator.remove();
                }
            }
            if (lines.isEmpty()) {
                throw new KeywordSearchModuleException(serverListName + " had no valid server information");
            }

            // Choose which server to use
            int rnd = new Random().nextInt(lines.size());
            String[] parts = lines.get(rnd).split(",");
            if (parts.length != 2) {
                throw new KeywordSearchModuleException("Invalid server data: " + lines.get(rnd));
            }

            // Split it up just to do a sanity check on the data
            String host = parts[0];
            String port = parts[1];
            if (host.isEmpty() || port.isEmpty()) {
                throw new KeywordSearchModuleException("Invalid server data: " + lines.get(rnd));
            }

            // Write the server data to a file
            Path serverFile = Paths.get(caseDirectoryPath.toString(), "solrserver.txt");
            try {
                caseDirectoryPath.toFile().mkdirs();
                if (!caseDirectoryPath.toFile().exists()) {
                    throw new KeywordSearchModuleException("Case directory " + caseDirectoryPath.toString() + " does not exist");
                }
                Files.write(serverFile, lines.get(rnd).getBytes());
            } catch (IOException ex) {
                throw new KeywordSearchModuleException(serverFile.toString() + " could not be written", ex);
            }
        }
    }
    
    /**
     * Helper class to store the current server properties
     */
    public static class IndexingServerProperties {

        private final String host;
        private final String port;

        IndexingServerProperties(String host, String port) {
            this.host = host;
            this.port = port;
        }

        /**
         * Get the host
         *
         * @return host
         */
        public String getHost() {
            return host;
        }

        /**
         * Get the port
         *
         * @return port
         */
        public String getPort() {
            return port;
        }
    }

    /**
     * Commits current core if it exists
     *
     * @throws SolrServerException, NoOpenCoreException
     */
    void commit() throws SolrServerException, NoOpenCoreException {
        currentCoreLock.readLock().lock();
        try {
            if (null == currentCollection) {
                throw new NoOpenCoreException();
            }
            currentCollection.commit();
        } finally {
            currentCoreLock.readLock().unlock();
        }
    }

    NamedList<Object> request(SolrRequest request) throws SolrServerException, RemoteSolrException, NoOpenCoreException {
        currentCoreLock.readLock().lock();
        try {
            if (null == currentCollection) {
                throw new NoOpenCoreException();
            }
            return currentCollection.request(request);
        } finally {
            currentCoreLock.readLock().unlock();
        }
    }

    /**
     * Execute query that gets only number of all Solr files indexed without
     * actually returning the files. The result does not include chunks, only
     * number of actual files.
     *
     * @return int representing number of indexed files
     *
     * @throws KeywordSearchModuleException
     * @throws NoOpenCoreException
     */
    public int queryNumIndexedFiles() throws KeywordSearchModuleException, NoOpenCoreException {
        currentCoreLock.readLock().lock();
        try {
            if (null == currentCollection) {
                throw new NoOpenCoreException();
            }
            try {
                return currentCollection.queryNumIndexedFiles();
            } catch (SolrServerException | RemoteSolrException | IOException ex) {
                throw new KeywordSearchModuleException(NbBundle.getMessage(this.getClass(), "Server.queryNumIdxFiles.exception.msg"), ex);
            }
        } finally {
            currentCoreLock.readLock().unlock();
        }
    }

    /**
     * Execute query that gets only number of all Solr file chunks (not logical
     * files) indexed without actually returning the content.
     *
     * @return int representing number of indexed chunks
     *
     * @throws KeywordSearchModuleException
     * @throws NoOpenCoreException
     */
    public int queryNumIndexedChunks() throws KeywordSearchModuleException, NoOpenCoreException {
        currentCoreLock.readLock().lock();
        try {
            if (null == currentCollection) {
                throw new NoOpenCoreException();
            }
            try {
                return currentCollection.queryNumIndexedChunks();
            } catch (SolrServerException | IOException ex) {
                throw new KeywordSearchModuleException(NbBundle.getMessage(this.getClass(), "Server.queryNumIdxChunks.exception.msg"), ex);
            }
        } finally {
            currentCoreLock.readLock().unlock();
        }
    }

    /**
     * Execute query that gets only number of all Solr documents indexed (files
     * and chunks) without actually returning the documents
     *
     * @return int representing number of indexed files (files and chunks)
     *
     * @throws KeywordSearchModuleException
     * @throws NoOpenCoreException
     */
    public int queryNumIndexedDocuments() throws KeywordSearchModuleException, NoOpenCoreException {
        currentCoreLock.readLock().lock();
        try {
            if (null == currentCollection) {
                throw new NoOpenCoreException();
            }
            try {
                return currentCollection.queryNumIndexedDocuments();
            } catch (SolrServerException | IOException ex) {
                throw new KeywordSearchModuleException(NbBundle.getMessage(this.getClass(), "Server.queryNumIdxDocs.exception.msg"), ex);
            }
        } finally {
            currentCoreLock.readLock().unlock();
        }
    }

    /**
     * Return true if the file is indexed (either as a whole as a chunk)
     *
     * @param contentID
     *
     * @return true if it is indexed
     *
     * @throws KeywordSearchModuleException
     * @throws NoOpenCoreException
     */
    public boolean queryIsIndexed(long contentID) throws KeywordSearchModuleException, NoOpenCoreException {
        currentCoreLock.readLock().lock();
        try {
            if (null == currentCollection) {
                throw new NoOpenCoreException();
            }
            try {
                return currentCollection.queryIsIndexed(contentID);
            } catch (SolrServerException | IOException ex) {
                throw new KeywordSearchModuleException(NbBundle.getMessage(this.getClass(), "Server.queryIsIdxd.exception.msg"), ex);
            }

        } finally {
            currentCoreLock.readLock().unlock();
        }
    }

    /**
     * Execute query that gets number of indexed file chunks for a file
     *
     * @param fileID file id of the original file broken into chunks and indexed
     *
     * @return int representing number of indexed file chunks, 0 if there is no
     * chunks
     *
     * @throws KeywordSearchModuleException
     * @throws NoOpenCoreException
     */
    public int queryNumFileChunks(long fileID) throws KeywordSearchModuleException, NoOpenCoreException {
        currentCoreLock.readLock().lock();
        try {
            if (null == currentCollection) {
                throw new NoOpenCoreException();
            }
            try {
                return currentCollection.queryNumFileChunks(fileID);
            } catch (SolrServerException | IOException ex) {
                throw new KeywordSearchModuleException(NbBundle.getMessage(this.getClass(), "Server.queryNumFileChunks.exception.msg"), ex);
            }
        } finally {
            currentCoreLock.readLock().unlock();
        }
    }

    /**
     * Execute solr query
     *
     * @param sq query
     *
     * @return query response
     *
     * @throws KeywordSearchModuleException
     * @throws NoOpenCoreException
     */
    public QueryResponse query(SolrQuery sq) throws KeywordSearchModuleException, NoOpenCoreException, IOException {
        currentCoreLock.readLock().lock();
        try {
            if (null == currentCollection) {
                throw new NoOpenCoreException();
            }
            try {
                return currentCollection.query(sq);
            } catch (SolrServerException ex) {
                logger.log(Level.SEVERE, "Solr query failed: " + sq.getQuery(), ex); //NON-NLS
                throw new KeywordSearchModuleException(NbBundle.getMessage(this.getClass(), "Server.query.exception.msg", sq.getQuery()), ex);
            }
        } finally {
            currentCoreLock.readLock().unlock();
        }
    }

    /**
     * Execute solr query
     *
     * @param sq the query
     * @param method http method to use
     *
     * @return query response
     *
     * @throws KeywordSearchModuleException
     * @throws NoOpenCoreException
     */
    public QueryResponse query(SolrQuery sq, SolrRequest.METHOD method) throws KeywordSearchModuleException, NoOpenCoreException {
        currentCoreLock.readLock().lock();
        try {
            if (null == currentCollection) {
                throw new NoOpenCoreException();
            }
            try {
                return currentCollection.query(sq, method);
            } catch (SolrServerException | IOException ex) {
                logger.log(Level.SEVERE, "Solr query failed: " + sq.getQuery(), ex); //NON-NLS
                throw new KeywordSearchModuleException(NbBundle.getMessage(this.getClass(), "Server.query2.exception.msg", sq.getQuery()), ex);
            }
        } finally {
            currentCoreLock.readLock().unlock();
        }
    }

    /**
     * Execute Solr terms query
     *
     * @param sq the query
     *
     * @return terms response
     *
     * @throws KeywordSearchModuleException
     * @throws NoOpenCoreException
     */
    public TermsResponse queryTerms(SolrQuery sq) throws KeywordSearchModuleException, NoOpenCoreException {
        currentCoreLock.readLock().lock();
        try {
            if (null == currentCollection) {
                throw new NoOpenCoreException();
            }
            try {
                return currentCollection.queryTerms(sq);
            } catch (SolrServerException | IOException ex) {
                logger.log(Level.SEVERE, "Solr terms query failed: " + sq.getQuery(), ex); //NON-NLS
                throw new KeywordSearchModuleException(NbBundle.getMessage(this.getClass(), "Server.queryTerms.exception.msg", sq.getQuery()), ex);
            }
        } finally {
            currentCoreLock.readLock().unlock();
        }
    }

    /**
     * Delete a data source from SOLR.
     *
     * @param dataSourceId to delete
     *
     * @throws NoOpenCoreException
     */
    void deleteDataSource(Long dataSourceId) throws IOException, KeywordSearchModuleException, NoOpenCoreException, SolrServerException {
        try {
            currentCoreLock.writeLock().lock();
            if (null == currentCollection) {
                throw new NoOpenCoreException();
            }
            currentCollection.deleteDataSource(dataSourceId);
            currentCollection.commit();
        } finally {
            currentCoreLock.writeLock().unlock();
        }
    }

    /**
     * Get the text contents of the given file as stored in SOLR.
     *
     * @param content to get the text for
     *
     * @return content text string or null on error
     *
     * @throws NoOpenCoreException
     */
    public String getSolrContent(final Content content) throws NoOpenCoreException {
        currentCoreLock.readLock().lock();
        try {
            if (null == currentCollection) {
                throw new NoOpenCoreException();
            }
            return currentCollection.getSolrContent(content.getId(), 0);
        } finally {
            currentCoreLock.readLock().unlock();
        }
    }

    /**
     * Get the text contents of a single chunk for the given file as stored in
     * SOLR.
     *
     * @param content to get the text for
     * @param chunkID chunk number to query (starting at 1), or 0 if there is no
     * chunks for that content
     *
     * @return content text string or null if error quering
     *
     * @throws NoOpenCoreException
     */
    public String getSolrContent(final Content content, int chunkID) throws NoOpenCoreException {
        currentCoreLock.readLock().lock();
        try {
            if (null == currentCollection) {
                throw new NoOpenCoreException();
            }
            return currentCollection.getSolrContent(content.getId(), chunkID);
        } finally {
            currentCoreLock.readLock().unlock();
        }
    }

    /**
     * Get the text contents for the given object id.
     *
     * @param objectID
     *
     * @return
     *
     * @throws NoOpenCoreException
     */
    public String getSolrContent(final long objectID) throws NoOpenCoreException {
        currentCoreLock.readLock().lock();
        try {
            if (null == currentCollection) {
                throw new NoOpenCoreException();
            }
            return currentCollection.getSolrContent(objectID, 0);
        } finally {
            currentCoreLock.readLock().unlock();
        }
    }

    /**
     * Get the text contents for the given object id and chunk id.
     *
     * @param objectID
     * @param chunkID
     *
     * @return
     *
     * @throws NoOpenCoreException
     */
    public String getSolrContent(final long objectID, final int chunkID) throws NoOpenCoreException {
        currentCoreLock.readLock().lock();
        try {
            if (null == currentCollection) {
                throw new NoOpenCoreException();
            }
            return currentCollection.getSolrContent(objectID, chunkID);
        } finally {
            currentCoreLock.readLock().unlock();
        }
    }

    /**
     * Given file parent id and child chunk ID, return the ID string of the
     * chunk as stored in Solr, e.g. FILEID_CHUNKID
     *
     * @param parentID the parent file id (id of the source content)
     * @param childID the child chunk id
     *
     * @return formatted string id
     */
    public static String getChunkIdString(long parentID, int childID) {
        return Long.toString(parentID) + Server.CHUNK_ID_SEPARATOR + Integer.toString(childID);
    }
    
    /**
     * Attempts to connect to the embedded Solr server, which is NOT running in SolrCloud mode.
     *
     * @param solrServer
     *
     * @throws SolrServerException
     * @throws IOException
     */
    void connectToEbmeddedSolrServer(HttpSolrClient solrServer) throws SolrServerException, IOException {
        TimingMetric metric = HealthMonitor.getTimingMetric("Solr: Connectivity check");
        CoreAdminRequest.getStatus(null, solrServer);
        HealthMonitor.submitTimingMetric(metric);
    }

    /**
     * Attempts to connect to the given Solr server, which is running in SoulrCloud mode. This API does not work
     * for the embedded Solr which is NOT running in SolrCloud mode.
     *
     * @param solrServer
     *
     * @throws SolrServerException
     * @throws IOException
     */
    // ELTODO void connectToSolrServer(HttpSolrClient solrServer) throws SolrServerException, IOException {
    void connectToSolrServer(CloudSolrClient solrServer) throws SolrServerException, IOException {
        TimingMetric metric = HealthMonitor.getTimingMetric("Solr: Connectivity check");
        CollectionAdminRequest.ClusterStatus statusRequest = CollectionAdminRequest.getClusterStatus();
        CollectionAdminResponse statusResponse = statusRequest.process(solrServer);
        int statusCode = Integer.valueOf(((NamedList) statusResponse.getResponse().get("responseHeader")).get("status").toString());
        if (statusCode != 0) {
            logger.log(Level.WARNING, "Could not connect to Solr server "); //NON-NLS
        } else {
            logger.log(Level.INFO, "Connected to Solr server "); //NON-NLS
        }
        HealthMonitor.submitTimingMetric(metric);
    }
    
    private List<String> getSolrServerList() throws SolrServerException, IOException {
        
        CollectionAdminRequest.ClusterStatus statusRequest = CollectionAdminRequest.getClusterStatus();
        CollectionAdminResponse statusResponse;
        try {
            statusResponse = statusRequest.process(currentSolrServer);
        } catch (RemoteSolrException ex) {
            // collection doesn't exist
            return Collections.EMPTY_LIST;
        }                
        if (statusResponse == null) {
            return Collections.EMPTY_LIST;
        }
        
        NamedList error = (NamedList) statusResponse.getResponse().get("error");
        if (error != null) {
            return Collections.EMPTY_LIST;
        }        
        
        // For some reason this returns info about all collections even though it's supposed to only return about the one we are requesting
        NamedList cluster = (NamedList) statusResponse.getResponse().get("cluster");
        ArrayList<String> live_nodes = (ArrayList) cluster.get("live_nodes");
        return live_nodes;
    }
    
    private boolean collectionExists(String collectionName) throws SolrServerException, IOException {

        // ELTODO investigate this. Get exception "Solr instance is not running in SolrCloud mode"
        // List<String> list = CollectionAdminRequest.listCollections(localSolrServer);
        
        CollectionAdminRequest.ClusterStatus statusRequest = CollectionAdminRequest.getClusterStatus().setCollectionName(collectionName);
        CollectionAdminResponse statusResponse;
        try {
            statusResponse = statusRequest.process(currentSolrServer);
        } catch (RemoteSolrException ex) {
            // collection doesn't exist
            return false;
        }
        
        if (statusResponse == null) {
            return false;
        }
        
        NamedList error = (NamedList) statusResponse.getResponse().get("error");
        if (error != null) {
            return false;
        }
        
        
        // For some reason this returns info about all collections even though it's supposed to only return about the one we are requesting
        NamedList cluster = (NamedList) statusResponse.getResponse().get("cluster");
        NamedList collections = (NamedList) cluster.get("collections");
        // NOTE: in case we need this, core name is (collectionName + "_shard1_replica_n1")
        if (collections != null) {
            Object collection = (Object) collections.get(collectionName);
            return (collection != null);
        } else {
            return false;
        }
    }

    /** Leaving this code as a reference, in case we need a programmatic way to obtain info
     * regarding the core, replicas, node names, etc.
    private boolean loadCoreForCollection(String collectionName, String dataDir) throws SolrServerException, IOException, KeywordSearchModuleException {
        // ELTODO remove code duplication with collectionExists()
        
        CollectionAdminRequest.ClusterStatus statusRequest = CollectionAdminRequest.getClusterStatus().setCollectionName(collectionName);
        CollectionAdminResponse statusResponse;
        try {
            statusResponse = statusRequest.process(currentSolrServer);
        } catch (RemoteSolrException ex) {
            // collection doesn't exist
            return false;
        }

        if (statusResponse == null) {
            return false;
        }

        NamedList error = (NamedList) statusResponse.getResponse().get("error");
        if (error != null) {
            return false;
        }

        // For some reason this returns info about all collections even though it's supposed to only return about the one we are requesting
        NamedList cluster = (NamedList) statusResponse.getResponse().get("cluster");
        NamedList collections = (NamedList) cluster.get("collections");
        // NOTE: in case we need this, core name is (collectionName + "_shard1_replica_n1")
        if (collections != null) {
            LinkedHashMap collection = (LinkedHashMap) collections.get(collectionName);
            LinkedHashMap shards = (LinkedHashMap) collection.get("shards");
            String shardId = (String) shards.keySet().toArray()[0];
            LinkedHashMap shard = (LinkedHashMap) shards.get(shardId);
            LinkedHashMap replicas = (LinkedHashMap) shard.get("replicas");
            String coreNodeName = (String) replicas.keySet().toArray()[0];
            LinkedHashMap core = (LinkedHashMap) replicas.get(coreNodeName);
            String coreName = (String) core.get("core");
            coreNodeName = (String) core.get("node_name");

            //if (coreIsLoaded(coreName)) {
            //    return true;
            //}
            try {
                CoreAdminRequest.Unload unloadCoreRequest = new CoreAdminRequest.Unload(false);
                unloadCoreRequest.setDeleteDataDir(false);
                unloadCoreRequest.setDeleteInstanceDir(false);
                unloadCoreRequest.setCoreName(coreName);
                currentSolrServer.request(unloadCoreRequest);
                //CoreAdminRequest.unloadCore(coreName, currentSolrServer);
            } catch (SolrServerException ex) {
                throw new KeywordSearchModuleException(
                        NbBundle.getMessage(this.getClass(), "Server.close.exception.msg"), ex);
            } catch (IOException ex) {
                throw new KeywordSearchModuleException(
                        NbBundle.getMessage(this.getClass(), "Server.close.exception.msg2"), ex);
            }
            
            CoreAdminRequest.Create createCoreRequest = new CoreAdminRequest.Create();
            createCoreRequest.setDataDir(dataDir);
            createCoreRequest.setCoreName(coreName);
            createCoreRequest.setCoreNodeName(coreNodeName);
            createCoreRequest.setShardId(shardId);
            createCoreRequest.setConfigSet("AutopsyConfig"); //NON-NLS
            createCoreRequest.setIsLoadOnStartup(false);
            createCoreRequest.setIsTransient(true);
            currentSolrServer.request(createCoreRequest);
            return true;
        } else {
            return false;
        }
    }*/

    class Collection {

        // handle to the collection in Solr
        private final String name;

        private final CaseType caseType;

        private final Index textIndex;

        // the server to access a collection needs to be built from a URL with the
        // collection in it, and is only good for collection-specific operations
        private final CloudSolrClient solrClient;

        private final int QUERY_TIMEOUT_MILLISECONDS = 86400000; // 24 Hours = 86,400,000 Milliseconds

        private Collection(String name, Case theCase, Index index, List<String> solrUrls) throws TimeoutException, InterruptedException {
            this.name = name;
            this.caseType = theCase.getCaseType();
            this.textIndex = index;

            // ELTODO get this from some configuration or UI
            //IndexingServerProperties properties = getMultiUserServerProperties(Case.getCurrentCase().getCaseDirectory());
           
            //IndexingServerProperties properties = getMultiUserServerProperties(theCase.getCaseDirectory());
            /*List<String> solrUrls = new ArrayList<>();
            List<String> solrServerList = UserPreferences.getAllIndexingServers();
            for (String server : solrServerList) {
                solrUrls.add("http://" + server + "/solr"); // ELTODO optimize this
                logger.log(Level.INFO, "Using Solr server: {0}", server);
            }*/
            solrClient = getCloudSolrClient(solrUrls);
            solrClient.setDefaultCollection(name); // ELTODO do we care that this solrClient instance gets reused?
            // ELTODO solrClient.connect(10, TimeUnit.SECONDS);
            // ELTODO solrClient.withSocketTimeout(QUERY_TIMEOUT_MILLISECONDS);
            // ELTODO solrClient.allowCompression(true);
            /* ELTODO this.solrClient = new HttpSolrClient.Builder(currentSolrServer.getBaseURL() + "/" + name)
                    .withSocketTimeout(QUERY_TIMEOUT_MILLISECONDS)
                    .allowCompression(true) // allowCompression defaults to false. Server side must support gzip or deflate for this to have any effect.
                    .build(); //NON-NLS
            */

            //TODO test these settings
            //solrCore.setConnectionTimeout(1000);
            // ELTODO solrClient.setFollowRedirects(false);  // defaults to false

            solrClient.setParser(new XMLResponseParser()); // binary parser is used by default

        }

        /**
         * Get the name of the collection
         *
         * @return the String name of the collection
         */
        String getName() {
            return name;
        }

        private Index getIndexInfo() {
            return this.textIndex;
        }

        private QueryResponse query(SolrQuery sq) throws SolrServerException, IOException {
            return solrClient.query(sq);
        }

        private NamedList<Object> request(SolrRequest request) throws SolrServerException, RemoteSolrException {
            try {
                return solrClient.request(request);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not issue Solr request. ", e); //NON-NLS
                throw new SolrServerException(
                        NbBundle.getMessage(this.getClass(), "Server.request.exception.exception.msg"), e);
            }

        }

        private QueryResponse query(SolrQuery sq, SolrRequest.METHOD method) throws SolrServerException, IOException {
            return solrClient.query(sq, method);
        }

        private TermsResponse queryTerms(SolrQuery sq) throws SolrServerException, IOException {
            QueryResponse qres = solrClient.query(sq);
            return qres.getTermsResponse();
        }

        private void commit() throws SolrServerException {
            try {
                //commit and block
                solrClient.commit(true, true);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not commit index. ", e); //NON-NLS
                throw new SolrServerException(NbBundle.getMessage(this.getClass(), "Server.commit.exception.msg"), e);
            }
        }

        private void deleteDataSource(Long dsObjId) throws IOException, SolrServerException {
            String dataSourceId = Long.toString(dsObjId);
            String deleteQuery = "image_id:" + dataSourceId;

            solrClient.deleteByQuery(deleteQuery);
        }

        void addDocument(SolrInputDocument doc) throws KeywordSearchModuleException {
            try {
                solrClient.add(doc);
            } catch (SolrServerException | RemoteSolrException ex) {
                logger.log(Level.SEVERE, "Could not add document to index via update handler: " + doc.getField("id"), ex); //NON-NLS
                throw new KeywordSearchModuleException(
                        NbBundle.getMessage(this.getClass(), "Server.addDoc.exception.msg", doc.getField("id")), ex); //NON-NLS
            } catch (IOException ex) {
                logger.log(Level.SEVERE, "Could not add document to index via update handler: " + doc.getField("id"), ex); //NON-NLS
                throw new KeywordSearchModuleException(
                        NbBundle.getMessage(this.getClass(), "Server.addDoc.exception.msg2", doc.getField("id")), ex); //NON-NLS
            }
        }

        /**
         * get the text from the content field for the given file
         *
         * @param contentID Solr document ID
         * @param chunkID Chunk ID of the Solr document
         *
         * @return Text from matching Solr document (as String). Null if no
         *         matching Solr document found or error while getting content
         *         from Solr
         */
        private String getSolrContent(long contentID, int chunkID) {
            final SolrQuery q = new SolrQuery();
            q.setQuery("*:*");
            String filterQuery = Schema.ID.toString() + ":" + KeywordSearchUtil.escapeLuceneQuery(Long.toString(contentID));
            if (chunkID != 0) {
                filterQuery = filterQuery + Server.CHUNK_ID_SEPARATOR + chunkID;
            }
            q.addFilterQuery(filterQuery);
            q.setFields(Schema.TEXT.toString());
            try {
                // Get the first result. 
                SolrDocumentList solrDocuments = solrClient.query(q).getResults();

                if (!solrDocuments.isEmpty()) {
                    SolrDocument solrDocument = solrDocuments.get(0);
                    if (solrDocument != null) {
                        java.util.Collection<Object> fieldValues = solrDocument.getFieldValues(Schema.TEXT.toString());
                        if (fieldValues.size() == 1) // The indexed text field for artifacts will only have a single value.
                        {
                            return fieldValues.toArray(new String[0])[0];
                        } else // The indexed text for files has 2 values, the file name and the file content.
                        // We return the file content value.
                        {
                            return fieldValues.toArray(new String[0])[1];
                        }
                    }
                }
            } catch (SolrServerException | IOException ex) {
                logger.log(Level.SEVERE, "Error getting content from Solr. Solr document id " + contentID + ", chunk id " + chunkID + ", query: " + filterQuery, ex); //NON-NLS
                return null;
            }

            return null;
        }

        synchronized void close() throws KeywordSearchModuleException {
            // We only unload cores for "single-user" cases.
            if (this.caseType == CaseType.MULTI_USER_CASE) {
                solrClient.setDefaultCollection(null);
                return;
            }

            /* NOTE: there is no way to unload collections. Only cores can be unloaded.
            Since we set "transient=true" and "loadOnStartup=false" on all cores that 
            we create, we shouldn't need to explicitly unload cores. Leaving the core below
            for reference. */
            
            // core name is (collectionName + "_shard1_replica_n1")
            //String coreName = this.name + "_shard1_replica_n1";
            try {
                CoreAdminRequest.unloadCore(this.name, currentSolrServer);
            } catch (SolrServerException ex) {
                throw new KeywordSearchModuleException(
                        NbBundle.getMessage(this.getClass(), "Server.close.exception.msg"), ex);
            } catch (IOException ex) {
                throw new KeywordSearchModuleException(
                        NbBundle.getMessage(this.getClass(), "Server.close.exception.msg2"), ex);
            }
        }

        /**
         * Execute query that gets only number of all Solr files (not chunks)
         * indexed without actually returning the files
         *
         * @return int representing number of indexed files (entire files, not
         * chunks)
         *
         * @throws SolrServerException
         */
        private int queryNumIndexedFiles() throws SolrServerException, IOException {
            return queryNumIndexedDocuments() - queryNumIndexedChunks();
        }

        /**
         * Execute query that gets only number of all chunks (not logical
         * folders, or all documents) indexed without actually returning the
         * content
         *
         * @return int representing number of indexed chunks
         *
         * @throws SolrServerException
         */
        private int queryNumIndexedChunks() throws SolrServerException, IOException {
            SolrQuery q = new SolrQuery(Server.Schema.ID + ":*" + Server.CHUNK_ID_SEPARATOR + "*");
            q.setRows(0);
            int numChunks = (int) query(q).getResults().getNumFound();
            return numChunks;
        }

        /**
         * Execute query that gets only number of all Solr documents indexed
         * without actually returning the documents. Documents include entire
         * indexed files as well as chunks, which are treated as documents.
         *
         * @return int representing number of indexed documents (entire files
         * and chunks)
         *
         * @throws SolrServerException
         */
        private int queryNumIndexedDocuments() throws SolrServerException, IOException {
            SolrQuery q = new SolrQuery("*:*");
            q.setRows(0);
            return (int) query(q).getResults().getNumFound();
        }

        /**
         * Return true if the file is indexed (either as a whole as a chunk)
         *
         * @param contentID
         *
         * @return true if it is indexed
         *
         * @throws SolrServerException
         */
        private boolean queryIsIndexed(long contentID) throws SolrServerException, IOException {
            String id = KeywordSearchUtil.escapeLuceneQuery(Long.toString(contentID));
            SolrQuery q = new SolrQuery("*:*");
            q.addFilterQuery(Server.Schema.ID.toString() + ":" + id);
            //q.setFields(Server.Schema.ID.toString());
            q.setRows(0);
            return (int) query(q).getResults().getNumFound() != 0;
        }

        /**
         * Execute query that gets number of indexed file chunks for a file
         *
         * @param contentID file id of the original file broken into chunks and
         * indexed
         *
         * @return int representing number of indexed file chunks, 0 if there is
         * no chunks
         *
         * @throws SolrServerException
         */
        private int queryNumFileChunks(long contentID) throws SolrServerException, IOException {
            String id = KeywordSearchUtil.escapeLuceneQuery(Long.toString(contentID));
            final SolrQuery q
                    = new SolrQuery(Server.Schema.ID + ":" + id + Server.CHUNK_ID_SEPARATOR + "*");
            q.setRows(0);
            return (int) query(q).getResults().getNumFound();
        }
    }

    class ServerAction extends AbstractAction {

        private static final long serialVersionUID = 1L;

        @Override
        public void actionPerformed(ActionEvent e) {
            logger.log(Level.INFO, e.paramString().trim());
        }
    }

    /**
     * Exception thrown if solr port not available
     */
    class SolrServerNoPortException extends SocketException {

        private static final long serialVersionUID = 1L;

        /**
         * the port number that is not available
         */
        private final int port;

        SolrServerNoPortException(int port) {
            super(NbBundle.getMessage(Server.class, "Server.solrServerNoPortException.msg", port,
                    Server.PROPERTIES_CURRENT_SERVER_PORT));
            this.port = port;
        }

        int getPortNumber() {
            return port;
        }
    }
}
