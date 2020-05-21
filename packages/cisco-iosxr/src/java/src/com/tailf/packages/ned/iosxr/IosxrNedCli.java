package com.tailf.packages.ned.iosxr;

import java.util.EnumSet;

import com.tailf.packages.ned.nedcom.Schema;
import com.tailf.packages.ned.nedcom.NedComCliBase;
import com.tailf.packages.ned.nedcom.NedDiff;
import com.tailf.packages.ned.nedcom.NedSettings;
import com.tailf.packages.ned.nedcom.NedSecretCliExt;
import com.tailf.packages.ned.nedcom.livestats.NedLiveStats;
import com.tailf.packages.ned.nedcom.livestats.NedLiveStatsException;
import com.tailf.packages.ned.nedcom.livestats.NedLiveStatsShowHandler;
import com.tailf.packages.ned.nedcom.NedCommonLib.PlatformInfo;
import com.tailf.packages.ned.nedcom.NedCommonLib.Verbosity;

import static com.tailf.packages.ned.nedcom.NedString.getMatch;
import static com.tailf.packages.ned.nedcom.NedString.getMatches;
import static com.tailf.packages.ned.nedcom.NedString.fillGroups;
import static com.tailf.packages.ned.nedcom.NedString.stringQuote;
import static com.tailf.packages.ned.nedcom.NedString.stringDequote;
import static com.tailf.packages.ned.nedcom.NedString.passwordQuote;
import static com.tailf.packages.ned.nedcom.NedString.passwordDequote;
import static com.tailf.packages.ned.nedcom.NedString.linesToString;
import static com.tailf.packages.ned.nedcom.NedString.calculateMd5Sum;
import static com.tailf.packages.ned.nedcom.NedString.findString;
import static com.tailf.packages.ned.nedcom.NedString.matcherToString;

import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.SFTPv3Client;
import ch.ethz.ssh2.SFTPv3FileHandle;

import java.io.IOException;
import java.io.BufferedReader;
import java.text.StringCharacterIterator;
import java.text.CharacterIterator;
import java.security.NoSuchAlgorithmException;

import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.Comparator;
import java.util.Collections;

import com.tailf.conf.Conf;
import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfPath;
import com.tailf.conf.ConfValue;
import com.tailf.conf.ConfObject;
import com.tailf.conf.ConfXMLParam;
import com.tailf.conf.ConfXMLParamValue;
import com.tailf.conf.ConfException;

import com.tailf.maapi.MaapiInputStream;
import com.tailf.maapi.MaapiConfigFlag;
import com.tailf.maapi.MaapiCrypto;
import com.tailf.maapi.MaapiCursor;
import com.tailf.maapi.MaapiException;

import com.tailf.ned.NedException;
import com.tailf.ned.NedExpectResult;

import com.tailf.ned.NedMux;
import com.tailf.ned.NedWorker;
import com.tailf.ned.CliSession;
import com.tailf.ned.SSHSessionException;


/**
 * Implements the cisco-iosxr CLI NED
 * @author lbang
 *
 */
@SuppressWarnings("deprecation")
public class IosxrNedCli extends NedComCliBase {

    // ned-settings developer trace-level
    protected static final int TRACE_INFO = 6;
    protected static final int TRACE_DEBUG = 7;
    protected static final int TRACE_DEBUG2 = 8;
    protected static final int TRACE_DEBUG3 = 9;

    // Constants
    private static final String PFX = "cisco-ios-xr:";
    private static final String META_DATA = "! meta-data :: ";

    private static final int SFTP_MAX_SIZE = 32768;
    private static final String UNKNOWN = "unknown";
    private static final String NSKEY = "__key__";

    // start of input, 1 character, > 0 non-# and ' ', one #, >= 0 ' ', eol
    private static final String PROMPT = "\\A[a-zA-Z0-9][^\\# ]+#[ ]?$";
    private static final String CONFIG_PROMPT = "\\A.*\\(.*\\)#[ ]?$";
    private static final String CMD_ERROR = "xyzERRORxyz";

    // List used by the showPartial() handler.
    private static Pattern[] protectedPaths = new Pattern[] {
        Pattern.compile("policy-map \\S+( \\\\ class\\s+\\S+).*"),
        Pattern.compile("admin( \\\\.*)")
    };

    // NEDLIVESTATS prompts
    private static final Pattern[] NEDLIVESTATS_PROMPT = new Pattern[] {
        Pattern.compile("\\A.*\\(.*\\)#[ ]?$"),
        Pattern.compile("\\A[a-zA-Z0-9][^\\# ]+#[ ]?$")
    };

    private static final Pattern[] MOVE_TO_TOP_PATTERN = new Pattern[] {
        Pattern.compile("\\A.*\\((admin-)?config\\)#"),
        Pattern.compile("Invalid input detected at"),
        Pattern.compile("\\A.*\\(.*\\)#"),
        Pattern.compile(PROMPT)
    };

    private static final Pattern[] NOPRINT_LINE_WAIT_PATTERN = new Pattern[] {
        Pattern.compile("\\A.*Uncommitted changes found, commit them"),
        Pattern.compile("\\A.*\\([a-z-]+[^\\(\\)# ]+\\)#"),
        Pattern.compile(PROMPT)
    };

    private static final Pattern[] ENTER_CONFIG_PATTERN = new Pattern[] {
        Pattern.compile("\\A\\S*\\((admin-)?config.*\\)#"),
        Pattern.compile("\\A.*running configuration is inconsistent with persistent configuration"),
        Pattern.compile("\\A\\S*#")
    };

    private static final Pattern[] EXIT_CONFIG_PATTERN = new Pattern[] {
        Pattern.compile("\\A.*Uncommitted changes found, commit them"),
        Pattern.compile("You are exiting after a 'commit confirm'"),
        Pattern.compile("Invalid input detected at "),
        Pattern.compile(PROMPT)
    };

    /**
     * Warnings, regular expressions. NOTE: Lowercase!
     */
    private static final String[] staticErrors = {
        "error",
        "aborted",
        "exceeded",
        "invalid",
        "incomplete",
        "duplicate name",
        "may not be configured",
        "should be in range",
        "is used by",
        "being used",
        "cannot be deleted",
        "bad mask",
        "failed"
    };

    // PROTECTED:
    protected int devTraceLevel;
    protected boolean adminLogin = false; // logged into admin mode directly
    protected boolean inConfig = false; // NED in config mode
    protected StringBuilder delayedCommit = new StringBuilder();

    // Utility classes
    private MetaDataModify metaData;
    private MaapiCrypto mCrypto = null;
    private NedCommand nedCommand;
    private NedDiff nedDiff;

    // devices info
    private String iosversion = UNKNOWN;
    private String iosmodel = UNKNOWN;
    private String iosserial = UNKNOWN;
    private String deviceProfile = "null";

    // SUPPORT:
    private boolean supportCommitShowError = true;

    // NED-SETTINGS
    private ArrayList<String> dynamicWarning = new ArrayList<>();
    private ArrayList<String[]> injectCommand = new ArrayList<>();
    private ArrayList<String[]> autoPrompts = new ArrayList<>();
    private ArrayList<String[]> replaceConfig = new ArrayList<>();
    private String transActionIdMethod;
    private String commitMethod;
    private int commitConfirmedTimeout;
    private int commitConfirmedDelay;
    private String commitOverrideChanges;
    private String configMethod;
    private boolean showRunningStrictMode;
    private int chunkSize;
    private boolean includeCachedShowVersion;
    private boolean autoCSCtk60033Patch;
    private boolean autoCSCtk60033Patch2;
    private boolean autoAclDeletePatch;
    private boolean autoAaaTacacsPatch;
    private boolean apiEditRoutePolicy;
    private boolean apiSpList;
    private boolean apiClassMapMatchAGList;
    private String remoteConnection;
    private String readDeviceMethod;
    private String connSerialMethod;
    private String connAdminName;
    private String connAdminPassword;
    private String readDeviceFile;
    private boolean readStripComments;
    private boolean readAdminShowRun;
    private int writeSftpThreshold;
    private String writeDeviceFile;
    private int writeOobExclusiveRetries;
    private String revertMethod;
    private boolean preferPlatformSN;
    private String platformModelRegex;

    // States
    private long lastTimeout;
    private String lastGetConfig = null;
    private String commitCommand;
    private int numCommit = 0;
    private int numAdminCommit = 0;
    private String syncFile = null;
    private String offlineData = null;
    private boolean showRaw = false;
    private String operPath;
    private String confPath;

    // Debug
    private String failphase = "";
    private int applyDelay = 0;



    /*
     **************************************************************************
     * Constructors
     **************************************************************************
     */

    /**
     * NED cisco-iosxr constructor
     */
    public IosxrNedCli() {
        super();
    }


    /**
     * NED cisco-iosxr constructor
     * @param deviceId
     * @param mux
     * @param trace
     * @param worker
     */
    public IosxrNedCli(String deviceId, NedMux mux, boolean trace, NedWorker worker) throws Exception {
        super(deviceId, mux, trace, worker);
        operPath = "/ncs:devices/ncs:device{"+deviceId+"}/ncs:ned-settings/iosxr-op:cisco-iosxr-oper";
        confPath = "/ncs:devices/ncs:device{"+deviceId+"}/config/cisco-ios-xr:";
    }


    /*
     **************************************************************************
     * setupParserContext
     **************************************************************************
     */

    /**
     * Override and init Schema.ParserContext with tailfned api info
     * @param
     */
    @Override
    protected void setupParserContext(Schema.ParserContext parserContext) {
        NedWorker worker = (NedWorker)parserContext.externalContext;

        // TO_DEVICE
        if (parserContext.parserDirection == Schema.ParserDirection.TO_DEVICE) {
            traceVerbose(worker, "   [output parser] adding to-transaction data-provider");
            addTransactionDataProvider(parserContext);
        }

        // FROM_DEVICE
        else {
            if (apiEditRoutePolicy) {
                traceVerbose(worker, "Adding /tailfned/api/edit-route-policy");
                parserContext.addVirtualLeaf("/tailfned/api/edit-route-policy", "");
            }
            if (apiSpList) {
                traceVerbose(worker, "Adding /tailfned/api/service-policy-list");
                parserContext.addVirtualLeaf("/tailfned/api/service-policy-list", "");
            }
            if (apiClassMapMatchAGList) {
                traceVerbose(worker, "Adding /tailfned/api/class-map-match-access-group-list");
                parserContext.addVirtualLeaf("/tailfned/api/class-map-match-access-group-list", "");
            }
        }
    }


    /*
     **************************************************************************
     * nedSettingsDidChange
     **************************************************************************
     */

    /**
     * Called when ned-settings changed
     * @param
     * @throws Exception
     */
    @Override
    public void nedSettingsDidChange(NedWorker worker, Set<String> changedKeys, boolean isConnected) throws Exception {
        final long start = tick(0);
        logInfo(worker, "BEGIN nedSettingsDidChange");

        // Cache ned-settings
        try {
            // connection
            preferPlatformSN = nedSettings.getBoolean("connection/prefer-platform-serial-number");
            platformModelRegex = nedSettings.getString("connection/platform-model-regex");
            connSerialMethod = nedSettings.getString("connection/serial-number-method");
            connAdminName = nedSettings.getString("connection/admin/name");
            connAdminPassword = nedSettings.getString("connection/admin/password");

            // proxy
            remoteConnection = nedSettings.getString("proxy/remote-connection");

            // read
            transActionIdMethod = nedSettings.getString("read/transaction-id-method");
            readDeviceMethod = nedSettings.getString("read/method");
            readDeviceFile = nedSettings.getString("read/file");
            readStripComments = nedSettings.getBoolean("read/strip-comments");
            showRunningStrictMode = nedSettings.getBoolean("read/show-running-strict-mode");
            readAdminShowRun = nedSettings.getBoolean("read/admin-show-running-config");

            // write
            commitMethod = nedSettings.getString("write/commit-method");
            String commitOptions = nedSettings.getString("write/commit-options");
            commitCommand = "commit " + commitOptions.trim();
            commitConfirmedTimeout = nedSettings.getInt("write/commit-confirmed-timeout");
            commitConfirmedDelay = nedSettings.getInt("write/commit-confirmed-delay");
            commitOverrideChanges = nedSettings.getString("write/commit-override-changes");
            revertMethod = nedSettings.getString("write/revert-method");
            configMethod = nedSettings.getString("write/config-method");
            chunkSize = nedSettings.getInt("write/number-of-lines-to-send-in-chunk");
            writeSftpThreshold = nedSettings.getInt("write/sftp-threshold");
            writeDeviceFile = nedSettings.getString("write/file");
            writeOobExclusiveRetries = nedSettings.getInt("write/oob-exclusive-retries");

            // api
            apiEditRoutePolicy = nedSettings.getBoolean("api/edit-route-policy");
            apiSpList = nedSettings.getBoolean("api/service-policy-list");
            apiClassMapMatchAGList = nedSettings.getBoolean("api/class-map-match-access-group-list");

            // auto
            autoCSCtk60033Patch = nedSettings.getBoolean("auto/CSCtk60033-patch");
            autoCSCtk60033Patch2 = nedSettings.getBoolean("auto/CSCtk60033-patch2");
            autoAclDeletePatch =  nedSettings.getBoolean("auto/acl-delete-patch");
            autoAaaTacacsPatch = nedSettings.getBoolean("auto/aaa-tacacs-patch");

            // developer
            devTraceLevel = nedSettings.getInt("developer/trace-level");
            if (logVerbose && devTraceLevel < TRACE_DEBUG) {
                traceInfo(worker, "ned-settings log-verbose true => developer trace-level = 7 (DEBUG)");
                devTraceLevel = TRACE_DEBUG;
            }

            // deprecated
            includeCachedShowVersion = nedSettings.getBoolean("deprecated/cached-show-enable/version");

            //
            // Get read/replace-config
            //
            List<Map<String,String>> entries;
            entries = nedSettings.getListEntries("read/replace-config");
            for (Map<String,String> entry : entries) {
                String[] newEntry = new String[3];
                newEntry[0] = entry.get(NSKEY); // "id"
                newEntry[1] = entry.get("regexp");
                newEntry[2] = entry.get("replacement");
                String buf = "read/replace-config "+newEntry[0];
                buf += " regexp "+stringQuote(newEntry[1]);
                if (newEntry[1] == null) {
                    throw new NedException("ned-settings: read/replace-config "+newEntry[0]+" missing regexp");
                }
                if (newEntry[2] != null) {
                    buf += " to "+stringQuote(newEntry[2]);
                } else {
                    newEntry[2] = "";
                    buf += " filtered";
                }
                traceVerbose(worker, buf);
                replaceConfig.add(newEntry);
            }

            //
            // Get config warnings
            //
            entries = nedSettings.getListEntries("write/config-warning");
            for (Map<String,String> entry : entries) {
                String key = entry.get(NSKEY);
                traceVerbose(worker, "write/config-warning "+key);
                dynamicWarning.add(stringDequote(key));
            }

            //
            // Get inject command(s)
            //
            entries = nedSettings.getListEntries("write/inject-command");
            for (Map<String,String> entry : entries) {
                String[] newEntry = new String[4];
                newEntry[0] = entry.get(NSKEY); // "id"
                newEntry[1] = entry.get("config");
                newEntry[2] = entry.get("command");
                newEntry[3] = entry.get("where");
                if (newEntry[1] == null || newEntry[2] == null || newEntry[3] == null) {
                    throw new NedException("write/inject-command "+newEntry[0]
                                           +" missing config, command or <where>, check your ned-settings");
                }
                traceVerbose(worker, "inject-command "+newEntry[0]
                             +" cfg "+stringQuote(newEntry[1])
                             +" cmd "+stringQuote(newEntry[2])
                             +" "+newEntry[3]);
                injectCommand.add(newEntry);
            }

            //
            // write config-dependency
            //
            setupNedDiff(worker);

            //
            // Get auto-prompts
            //
            entries = nedSettings.getListEntries("live-status/auto-prompts");
            for (Map<String,String> entry : entries) {
                String[] newEntry = new String[3];
                newEntry[0] = entry.get(NSKEY); // "id"
                newEntry[1] = entry.get("question");
                newEntry[2] = entry.get("answer");
                traceVerbose(worker, "cisco-iosxr-auto-prompts "+newEntry[0]
                             + " q \"" +newEntry[1]+"\""
                             + " a \"" +newEntry[2]+"\"");
                autoPrompts.add(newEntry);
            }
        } catch (Exception e) {
            throw new NedException("Failed to read ned-settings :: "+e.getMessage(), e);
        }

        if (logVerbose) {
            ch.ethz.ssh2.log.Logger.enabled = true;
        }

        logInfo(worker, "DONE nedSettingsDidChange "+tickToString(start));
    }


    /**
     * Setup NedDiff
     *    Rule syntax: line to move :: after|before :: line to stay
     * @param
     * @throws NedException
     */
    private void setupNedDiff(NedWorker worker) {

        //
        // Initialize NedDiff
        //
        nedDiff = new NedDiff(this, devTraceLevel > TRACE_DEBUG);

        // delete before create (NSO being silly)
        nedDiff.add(">no snmp-server host :: before :: >snmp-server host");

        // Add user rules from ned-settings 'write config-dependency' list
        nedDiff.addNedSettings(worker, nedSettings);

        traceDebug2(worker, nedDiff.toString());
    }


    /*
     **************************************************************************
     * updateNedCapabilities
     **************************************************************************
     */

    /**
     * Update NED capas regarding reverse diff and path format for partial show
     * @param
     */
    @Override
    protected NedCapabilities updateNedCapabilities(NedCapabilities capabilities) {
        if ("filter-mode".equals(nedSettings.getString("read/partial-show-method"))) {
            /*
             * On NETSIM and if explicitly configured, use the show-partial filter-mode.
             * The 'key-path' feature does not work properly on NSO versions < 4.4.3
             */
            capabilities.partialFormat = (nsoRunningVersion >= 0x6040300) ? "key-path" : "cmd-path-modes-only";
        } else {
            capabilities.partialFormat = "cmd-path-modes-only";
        }
        return capabilities;
    }


    /*
     **************************************************************************
     * setupDevice
     **************************************************************************
     */

    /**
     * Setup device
     * @param
     * @return PlatformInfo
     * @throws Exception
     */
    protected PlatformInfo setupDevice(NedWorker worker) throws Exception {
        tracer = trace ? worker : null;
        final long start = tick(0);
        logInfo(worker, "BEGIN PROBE");

        //
        // Logged in, set terminal settings and check device type
        //
        int th = -1;
        try {
            resetTimeout(worker, this.connectTimeout, 0);

            // Set terminal settings
            print_line_exec(worker, "terminal length 0");
            print_line_exec(worker, "terminal width 0");

            // Show version
            String version = print_line_exec(worker, "show version brief");
            if (isExecError(version)) {
                version = print_line_exec(worker, "show version");
            }
            version = version.replace("\r", "");

            /* Verify we connected to a XR device */
            if (version.contains("Cisco IOS XR Admin Software")) {
                traceInfo(worker, "connecting to admin mode");
                adminLogin = true;
            } else if (!version.contains("Cisco IOS XR Software")) {
                throw new NedException("Unknown device :: " + version);
            }

            setUserSession(worker);
            th = maapi.startTrans(Conf.DB_RUNNING, Conf.MODE_READ);

            // Read device-profile (for debug purposes)
            this.deviceProfile = getDeviceProfile(th);

            //
            // NETSIM
            //
            if (version.contains("NETSIM")) {
                this.iosmodel = "NETSIM";
                this.iosversion = "cisco-iosxr-" + nedVersion;
                this.iosserial = this.device_id;

                // Show CONFD & NED version used by NETSIM in ned trace
                print_line_exec(worker, "show confd-state version");
                print_line_exec(worker, "show confd-state loaded-data-models data-model tailf-ned-cisco-ios-xr");

                // Set NETSIM data and defaults
                transActionIdMethod = "config-hash"; // override
                readDeviceMethod = "show running-config";
                writeSftpThreshold = 2147483647;
            }

            //
            // DEVICE
            //
            else {
                // Get version
                Pattern p = Pattern.compile("Cisco IOS XR(?: Admin)? Software, Version (\\S+)");
                Matcher m = p.matcher(version);
                if (m.find()) {
                    iosversion = m.group(1);
                    int n;
                    if ((n = iosversion.indexOf('[')) > 0) {
                        iosversion = iosversion.substring(0,n);
                    }
                }

                // Admin mode is deprecated for XR version 7..
                if (readAdminShowRun && iosversion.startsWith("7")) {
                    traceInfo(worker, "Disabling admin mode, deprecated in XR version 7..");
                    readAdminShowRun = false;
                }

                // Get model
                if (platformModelRegex != null) {
                    p = Pattern.compile(platformModelRegex);
                    m = p.matcher(version);
                    if (m.find()) {
                        this.iosmodel = m.group(1);
                    } else {
                        throw new NedException("No match for platform-model-regex = "+stringQuote(platformModelRegex));
                    }
                } else {
                    p = Pattern.compile("\ncisco (.+?) (?:Series |\\().*");
                    m = p.matcher(version);
                    if (m.find()) {
                        this.iosmodel = m.group(1);
                    } else {
                        p = Pattern.compile("\n(NCS.+)");
                        m = p.matcher(version);
                        if (m.find()) {
                            this.iosmodel = m.group(1);
                        }
                    }
                }

                // Get serial-number
                iosserial = getSerial(worker, th, version);
                if (iosserial == null) {
                    traceInfo(worker, "WARNING: failed to retrieve serial-number");
                    iosserial = UNKNOWN;
                }

                // Include active packages in the trace
                if (logVerbose) {
                    print_line_exec(worker, "show install active summary");
                }
            }

        } catch (Exception e) {
            throw new NedException("Failed to setup NED :: "+e.getMessage(), e);
        } finally {
            if (th != -1) {
                maapi.finishTrans(th);
            }
        }

        logInfo(worker, "DONE PROBE "+tickToString(start));
        return new PlatformInfo("ios-xr", iosversion, iosmodel, iosserial);
    }


    /**
     * Get device profile from CDB
     * @param
     * @return device profile name or null if error or not found
     */
    private String getDeviceProfile(int th) {
        try {
            String p = "/ncs:devices/device{"+this.device_id+"}/device-profile";
            if (maapi.exists(th, p)) {
                return ConfValue.getStringByValue(p, maapi.getElem(th, p));
            }
        } catch (Exception ignore) {
            // Ignore Exception
        }
        return "null";
    }


    /**
     * Get serial number from device
     * @param
     * @return Configuration
     * @throws Exception
     */
    private String getSerial(NedWorker worker, int th, String version)
        throws IOException, SSHSessionException, ConfException {
        String serial = null;

        // ned-settings cisco-iosxr connection prefer-platform-serial-number
        if (preferPlatformSN) {
            long perf = tick(0);
            serial = getPlatformData(th, "serial-number");
            if (serial != null && !UNKNOWN.equals(serial)) {
                traceInfo(worker, "devices device platform serial-number = "
                          +serial+" "+tickToString(perf));
                return serial;
            }
        }

        // Auto-update of default method
        if ("auto".equals(connSerialMethod)) {
            // NOTE: Seems 'show inventory' is faster on CRS/NCS (ncs6k), but slower on ASRs
            if (version.contains("cisco CRS") || version.contains("cisco NCS")) {
                connSerialMethod = "prefer-inventory";
            } else {
                connSerialMethod = "prefer-diag";
            }
        }

        // ned-settings cisco-iosxr connection serial-number-method
        if ("prefer-diag".equals(connSerialMethod)) {
            serial = getSerialDiag(worker);
            if (serial == null) {
                serial = getSerialInventory(worker);
            }
        } else if ("prefer-inventory".equals(connSerialMethod)) {
            serial = getSerialInventory(worker);
            if (serial == null) {
                serial = getSerialDiag(worker);
            }
        } else if ("inventory".equals(connSerialMethod)) {
            serial = getSerialInventory(worker);
        } else if ("diag".equals(connSerialMethod)) {
            serial = getSerialDiag(worker);
        }

        return serial;
    }


    /**
     * Get serial number using 'show diag'
     * @param
     * @return Configuration
     * @throws Exception
     */
    private String getSerialDiag(NedWorker worker) throws IOException, SSHSessionException {
        final long perf = tick(0);
        String serial = print_line_exec(worker, "show diag | i S/N");
        if ((serial = getMatch(serial, "S/N\\s*(?:[:])?\\s+(\\S+)")) != null) {
            traceInfo(worker, "show diag serial-number = "+serial+" "+tickToString(perf));
        } else {
            traceInfo(worker, "Failed to retrieve serial-number using 'show diag' "+tickToString(perf));
        }
        return serial;
    }


    /**
     * Get serial number using 'show inventory'
     * @param
     * @return Configuration
     * @throws Exception
     */
    private String getSerialInventory(NedWorker worker) throws IOException, SSHSessionException {
        final long perf = tick(0);
        String serial = print_line_exec(worker, "show inventory | i SN");
        if ((serial = getMatch(serial, "SN[:]\\s+(\\S+)")) != null) {
            traceInfo(worker, "show inventory serial-number = "+serial+" "+tickToString(perf));
        } else {
            traceInfo(worker, "Failed to retrieve serial-number using 'show inventory' "+tickToString(perf));
        }
        return serial;
    }


    /*
     **************************************************************************
     * connectDevice
     **************************************************************************
     */

    @Override
    public void connectDevice(NedWorker worker) throws Exception {
        logInfo(worker, "BEGIN CONNECT-DEVICE");
        connectorConnectDevice(worker);
        logInfo(worker, "DONE CONNECT-DEVICE");
    }


    /*
     **************************************************************************
     * setupInstance
     **************************************************************************
     */

    /**
     * Setup NED instance
     * @param
     * @throws Exception
     */
    protected void setupInstance(NedWorker worker, PlatformInfo platformInfo) throws Exception {
        final long start = tick(0);
        logInfo(worker, "BEGIN SETUP");

        if (this.writeTimeout < this.readTimeout) {
            traceInfo(worker, "WARNING: write-timeout too low, reset to read-timeout value");
            this.writeTimeout = this.readTimeout; // API CHANGE helper
        }

        this.iosmodel = platformInfo.model;
        this.iosversion = platformInfo.version;
        this.iosserial = platformInfo.serial;

        traceInfo(worker, "DEVICE:"
                  +" model="+iosmodel
                  +" version="+iosversion
                  +" serial="+iosserial);

        // device-profile
        traceInfo(worker, "device-profile = " + deviceProfile);

        // Create utility classes used by the NED
        metaData = new MetaDataModify(this);
        secrets = new NedSecretCliExt(this);
        secrets.setDebug(devTraceLevel >= TRACE_DEBUG);
        mCrypto = new MaapiCrypto(maapi);

        // NedCommand default auto-prompts:
        String[][] defaultAutoPrompts = new String[][] {
            { "([!]{20}|[C]{20}|[.]{20})", "<timeout>" },
            { "\\[OK\\]", null },
            { "\\[Done\\]", null },
            { "timeout is \\d+ seconds:", null },  // ping
            { "Suggested steps to resolve this:", null }, // admin install
            { "Info: .*", null }, // (admin) install
            { "Warning: .*", null }, // (admin) install
            { "detected the 'warning' condition '.+?'", null },
            { ":\\s*$", "<prompt>" },
            { "\\][\\?]?\\s*$", "<prompt>" }
        };
        nedCommand = new NedCommand(this, "cisco-ios-xr-stats", "cisco-ios-xr", PROMPT, CONFIG_PROMPT,
                                    " Invalid input detected at ", defaultAutoPrompts);

        //
        // Only setup liveStats and SFTP for connected devices
        //
        if (session != null) {

            // Setup custom show handler
            nedLiveStats.setupCustomShowHandler(new ShowHandler(this, session, NEDLIVESTATS_PROMPT));

            // Make NedLiveStats aware of the ietf-interface and ietf-ip modules.
            nedLiveStats.installParserInfo("if:interfaces-state/interface",
                                           "{'show':'show interfaces',"+
                                           "'template':'if:interfaces-state_interface.gili',"+
                                           "'show-entry':{'cmd':'show interfaces %s',"+
                                           "'template':'if:interfaces-state_interface.gili',"+
                                           "'trim-top-node':true,'run-after-show':false}}");

            nedLiveStats.installParserInfo("if:interfaces-state/if:interface/ip:ipv4/ip:address",
                               "{'show':{'cmd':'show run interface %s | include ipv4 address','arg':['../../name']},"+
                               "'template':'if:interfaces-state_interface_ip:ipv4_address.gili'}");

            nedLiveStats.installParserInfo("if:interfaces-state/if:interface/ip:ipv6/ip:address",
                               "{'show':{'cmd':'show run interface %s | include ipv6 address','arg':['../../name']},"+
                               "'template':'if:interfaces-state_interface_ip:ipv6_address.gili'}");

            // SFTP download, create directory if not there
            if ("sftp-transfer".equals(readDeviceMethod) && readDeviceFile != null) {
                String path = getMatch(readDeviceFile, "(\\S+)/\\S+");
                if (path != null) {
                    traceInfo(worker, "sftp-transfer checking "+path+" directory");
                    String reply = print_line_exec(worker, "dir "+path);
                    if (reply.contains("No such file or directory")) {
                        traceInfo(worker, "SFTP creating download directory: "+path);
                        reply = nedCommand.runCommand(worker, "mkdir " + path + " | prompts ENTER");
                        if (!reply.contains("Created dir " + path)) {
                            traceInfo(worker, "sftp-transfer ERROR in creating 'read file' directory: "+ reply);
                            traceInfo(worker, "Disabling sftp-transfer");
                            readDeviceMethod = "show running-config";
                        }
                    }
                }
            }
        }

        // Set tailfned api service-policy-list from ned-setting
        if (isNetsim()) {
            StringBuilder sb = new StringBuilder();
            if (apiSpList) {
                traceInfo(worker, "Configuring tailfned api service-policy-list from ned-setting");
                sb.append("tailfned api service-policy-list\n");
            }
            if (apiClassMapMatchAGList) {
                traceInfo(worker, "Configuring tailfned api class-map-match-access-group-list from ned-setting");
                sb.append("tailfned api class-map-match-access-group-list\n");
            }
            if (sb.length() > 0) {
                nedCommand.commitConfig(worker, sb.toString());
            }
        }

        logInfo(worker, "DONE SETUP "+tickToString(start));
    }


    /**
     * Get data from devices device platform
     * @param
     * @return Value or "unknown
     */
    protected String getPlatformData(int th, String leaf) throws IOException, ConfException {

        // First try devices device platform
        String p = "/ncs:devices/device{"+this.device_id+"}/platform/" + leaf;
        try {
            if (maapi.exists(th, p)) {
                return ConfValue.getStringByValue(p, maapi.getElem(th, p));
            }
        } catch (MaapiException ignore) {
            // Ignore Exception
        }

        // Second try config cached-show version
        if (includeCachedShowVersion) {
            p = confPath + "cached-show/version/" + leaf;
            try {
                if (maapi.exists(th, p)) {
                    return ConfValue.getStringByValue(p, maapi.getElem(th, p));
                }
            } catch (MaapiException ignore) {
                // Ignore Exception
            }
        }

        return UNKNOWN;
    }


    /**
     * NedLiveStatsShowHandler
     * @param
     * @throws Exception
     */
    private class ShowHandler extends NedLiveStatsShowHandler {
        private NedComCliBase owner;
        private CliSession session;
        private Pattern[] prompts;

        public ShowHandler(NedComCliBase owner, CliSession session, Pattern[] prompts)
            throws NedLiveStatsException {
            super(owner, session, prompts);
            this.owner = owner;
            this.session = session;
            this.prompts = prompts;
        }

        public String execute(NedWorker worker, String cmd) throws Exception {

            traceInfo(worker, "ShowHandler: "+stringQuote(cmd));

            // '!noop' used for dummy show-entry
            if (cmd.startsWith("!")) {
                return "";
            }

            // ned-setting cisco-iosxr developer simulate-command *
            String simulated = simulateCommand(worker, cmd);
            if (simulated != null) {
                traceInfo(worker, "ShowHandler: Simulated output for '"+cmd+"'");
                return simulated;
            }

            // NETSIM show command massage
            if (this.owner != null && this.owner.isNetsim()) {
                // Split interface name
                Pattern p = Pattern.compile("show run interface ([A-Za-z]+)([0-9]+\\S*)");
                Matcher m = p.matcher(cmd);
                if (m.find()) {
                    cmd = cmd.replace(m.group(1)+m.group(2), m.group(1)+" "+m.group(2));
                }

                // Insert "" around the include|exclude <regex>
                String[] args = cmd.split(" [|] (include|exclude) ");
                for (int i = 1; i < args.length; i++) {
                    cmd = cmd.replace(args[i], "\""+args[i]+"\"");
                }
            }

            session.println(cmd);
            session.expect(Pattern.quote(cmd), worker);
            NedExpectResult res = session.expect(prompts, worker);

            return res.getText();
        }
    }


    /*
     **************************************************************************
     * show
     **************************************************************************
     */

    /**
     * Retrieve running config from device
     * @param
     * @throws Exception
     */
    public void show(NedWorker worker, String toptag) throws Exception {
        final long start = tick(0);
        if (session != null && trace) {
            session.setTracer(worker);
        }

        // Only respond to the first toptag
        if (!"interface".equals(toptag)) {
            worker.showCliResponse("");
            return;
        }

        logInfo(worker, "BEGIN SHOW");

        // Get config from device
        lastGetConfig = null;
        String res = getConfig(worker, true);

        // cisco-iosxr extended-parser
        try {
            if (this.turboParserEnable) {
                traceInfo(worker, "Parsing config using turbo-mode");
                if (parseAndLoadXMLConfigStream(maapi, worker, schema, res)) {
                    res = ""; // Turbo-parser succeeded, clear config to bypass CLI
                }
            } else if (this.robustParserMode) {
                traceInfo(worker, "Parsing config using robust-mode");
                res = filterConfig(res, schema, maapi, worker, null, false).toString();
            }
        } catch (Exception e) {
            logError(worker, "extended-parser "+nedSettings.getString(NedSettings.EXTENDED_PARSER)
                     +" exception ERROR: ", e);
            this.turboParserEnable = false;
            this.robustParserMode = false;
        }

        logInfo(worker, "DONE SHOW "+tickToString(start));
        worker.showCliResponse(res);
    }


    /**
     * Get configuration from device
     * @param
     * @return Configuration
     * @throws Exception
     */
    private String getConfig(NedWorker worker, boolean convert) throws Exception {
        long t = nedReportProgress(worker, "reading config...", 0);
        try {
            // Reset timeout and get current time
            final long start = setReadTimeout(worker);

            //
            // Get config from device
            //
            String res = "";

            // showOffline
            if (offlineData != null) {
                logInfo(worker, "BEGIN reading config (showOffline)");
                res = "\n" + insertCarriageReturn(offlineData);
                traceInfo(worker, res);
            }

            // devices device <dev> live-status exec any sync-from-file <file>
            else if (convert && syncFile != null) {
                logInfo(worker, "BEGIN reading config (file = "+syncFile+")");
                res = print_line_exec(worker, "file show " + syncFile);
                if (res.contains("Error: failed to open file")) {
                    throw new NedException("failed to sync from file " + syncFile);
                }
            }

            // sftp-transfer
            else if (!adminLogin && "sftp-transfer".equals(readDeviceMethod)) {
                logInfo(worker, "BEGIN reading config (sftp-transfer)");
                if (remoteConnection != null) {
                    throw new NedException("sftp-transfer is not supported with proxy mode");
                }
                if (readDeviceFile == null) {
                    throw new NedException("No SFTP file name configured");
                }
                res = sftpGetConfig(worker);
            }

            // show running-config
            else if (!adminLogin) {
                String cmd = readDeviceMethod;
                if (inConfig) {
                    cmd = "do " + cmd;
                }
                logInfo(worker, "BEGIN reading config ("+cmd+") [in-config="+inConfig+"]");
                res = print_line_exec(worker, cmd);
            }
            setReadTimeout(worker);

            //
            // Trim config
            //
            res = trimConfig(res);

            //
            // Prepend admin config
            //
            if (isDevice() && session != null && readAdminShowRun) {
                res = getAdminConfig(worker, true) + res;
            }

            //
            // Transform config
            //
            res = modifyInput(worker, convert, false, res);

            //
            // Done
            //
            logInfo(worker, "DONE reading config ("+res.length()+" bytes) "+tickToString(start));
            nedReportProgress(worker, "reading config ok", t);
            if (syncFile != null || offlineData != null) {
                traceVerbose(worker, "\nSHOW_AFTER_FILE:\n"+res);
            } else {
                traceVerbose(worker, "SHOW_AFTER=\n"+res);
            }
            return res;

        } catch (Exception e) {
            nedReportProgress(worker, "reading config error", t);
            throw e;

        } finally {
            this.syncFile = null;
        }
    }


    /**
     * Insert missing carriage return, used by offline data
     * @param
     * @return
     */
    private String insertCarriageReturn(String res) {
        String[] lines = res.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int n = 0; n < lines.length; n++) {
            if (lines[n].endsWith("\r")) {
                sb.append(lines[n]+"\n");
            } else {
                sb.append(lines[n]+"\r\n");
            }
        }
        return sb.toString();
    }


    /**
     * Trim non-config from show running-config
     * @param
     * @return
     */
    private String trimConfig(String res) {

        if (res.trim().isEmpty()) {
            return res;
        }

        // Strip everything before:
        int i = res.indexOf("Building configuration...");
        int nl;
        if (i >= 0) {
            nl = res.indexOf('\n', i);
            if (nl > 0) {
                res = res.substring(nl+1);
            }
        }
        i = res.indexOf("!! Last configuration change");
        if (i >= 0) {
            nl = res.indexOf('\n', i);
            if (nl > 0) {
                res = res.substring(nl+1);
            }
        }
        i = res.indexOf("No entries found.");
        if (i >= 0) {
            nl = res.indexOf('\n', i);
            if (nl > 0) {
                res = res.substring(nl+1);
            }
        }

        // Strip everything after 'end'
        i = res.lastIndexOf("\nend");
        if (i >= 0) {
            res = res.substring(0,i);
        }

        // Strip timestamp
        res = res.trim() + "\r\n";
        if (res.startsWith("Mon ") || res.startsWith("Tue ") || res.startsWith("Wed ") || res.startsWith("Thu ")
            || res.startsWith("Fri ") || res.startsWith("Sat ") || res.startsWith("Sun ")) {
            nl = res.indexOf('\n');
            if (nl > 0) {
               res = res.substring(nl+1);
            }
        }

        return res;
    }


    /**
     * Get and transform admin config
     * @param
     * @return
     * @throws Exception
     */
    private String getAdminConfig(NedWorker worker, boolean enterAdmin)
        throws NedException, IOException, SSHSessionException {

        traceInfo(worker, "Reading admin config");

        if (inConfig) {
            throw new NedException("getAdminConfig() :: Internal ERROR: called in config mode");
        }

        // Enter admin mode
        if (enterAdmin) {
            try {
                enterAdmin(worker);
            } catch (Exception e) {
                return "WARNING: Failed to enter admin mode\n"
                    +"Set ned-setting cisco-iosxr read admin-show-running-config false\n"
                    +"OR fix the authentication error when entering admin mode\n";
            }
        }

        // Show running-config in admin mode
        String res = "";
        try {
            res = print_line_exec(worker, "show running-config");
            if (isExecError(res)) {
                throw new NedException("Internal ERROR: failed to run 'show running-config' in admin mode");
            }
        } finally {
            if (enterAdmin) {
                exitAdmin(worker);
            }
        }

        // Trim beginning and end, may result in empty buf
        res = trimConfig(res);
        if (res.trim().isEmpty()) {
            traceVerbose(worker, "SHOW_ADMIN: empty");
            return "";
        }

        // Trim and wrap in admin mode context
        String[] lines = res.split("\n");
        StringBuilder sb = new StringBuilder();
        sb.append("\nadmin\n");
        int next;
        String toptag = "";
        for (int n = 0; n < lines.length; n = next) {
            String line = lines[n];
            String trimmed = line.trim();
            next = n + 1;

            // Trim comments: '!! ' or '! '
            if (line.startsWith("!! ") || line.startsWith("! ")) {
                continue;
            }

            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = trimmed; // remove \r
            }
            String input = null;

            //
            // interface * / ipv4 address
            //
            if (toptag.startsWith("interface ") && trimmed.startsWith("ipv4 address ")) {
                input = line.replaceAll("ipv4 address ([0-9.]+)/(\\d+)", "ipv4 address $1 /$2");
            }

            // aaa authentication groups group * / users
            else if (toptag.startsWith("aaa authentication groups group ") && trimmed.startsWith("users ")) {
                input = line.replace("\"", ""); // Strip quotes around names
            }

            // unused
            else if (line.startsWith("TRIM-THIS-MODE-CONFIG")) {
                // Trim unsupported config to reduce trace spam
                while (next + 1 < lines.length &&
                       (lines[next].charAt(0) == ' ' || lines[next].trim().equals("!"))) {
                    next++;
                }
                continue;
            }

            // Add to admin dump
            if (input == null) {
                sb.append(" "+lines[n]+"\n");
            } else if (!input.isEmpty()) {
                sb.append(" "+input+"\n");
            }
        }

        // Return admin config
        sb.append("exit-admin-config\n");
        res = sb.toString();
        traceVerbose(worker, "SHOW_ADMIN:\n"+res);
        return res;
    }


    /**
     * Connect with SFTP
     * @param
     * @return
     * @throws Exception
     */
    private Connection sftpConnect(NedWorker worker) throws NedException {

        // Connect
        traceInfo(worker, "SFTP connecting to " + ip.getHostAddress()+":"+port);
        Connection sftpConn = new Connection(ip.getHostAddress(), port);
        try {
            sftpConn.connect(null, 0, connectTimeout);
        } catch (Exception e) {
            throw new NedException("SFTP connect failed (check device SSH config or disable sftp in ned-settings) :: "
                                + e.getMessage());
        }

        // Authenticate
        try {
            sftpConn.authenticateWithPassword(ruser, pass);
        } catch (Exception e) {
            throw new NedException("SFTP " + e.getMessage());
        }
        if (!sftpConn.isAuthenticationComplete()) {
            throw new NedException("SFTP authentication incomplete");
        }

        traceInfo(worker, "SFTP logged in");
        return sftpConn;
    }


    /**
     * Get running-config from device using SFTP
     * @param
     * @return
     * @throws Exception
     */
    private String sftpGetConfig(NedWorker worker) throws Exception {

        // Copy over running-config to file
        traceInfo(worker, "SFTP copying running-config to file: " + readDeviceFile);
        String cmd = "copy running-config " + readDeviceFile + " | prompts ENTER yes";
        String reply = nedCommand.runCommand(worker, cmd);
        if (reply.startsWith(CMD_ERROR)) {
            throw new NedException("sftp-transfer ERROR: "+reply.replace(CMD_ERROR, ""));
        }
        if (reply.contains("No such file or directory")) {
            throw new NedException("sftp-transfer ERROR copying running-config to file, check 'read file' ned-setting");
        }

        // Connect using SSH
        Connection sftpConn = sftpConnect(worker);

        // Get running-config file using Ganymed SFTP API
        lastTimeout = setReadTimeout(worker);
        traceInfo(worker, "SFTP fetching running-config copy: " + readDeviceFile);
        SFTPv3Client sftp = new SFTPv3Client(sftpConn);
        String res = "";
        try {
            byte[] buffer = new byte[SFTP_MAX_SIZE];
            int i = 0;
            long offset = 0;
            StringBuilder sb = new StringBuilder();
            SFTPv3FileHandle h = sftp.openFileRO(readDeviceFile);
            while ((i = sftp.read(h, offset, buffer, 0, buffer.length)) > 0) {
                sb.append(new String(buffer).substring(0, i));
                offset += i;
                if (i < buffer.length) {
                    break;
                }
                lastTimeout = resetReadTimeout(worker, lastTimeout);
            }
            res = sb.toString();
            traceInfo(worker, "SFTP got "+res.length()+" bytes");

        } finally {
            sftp.close();
            sftpConn.close();
        }

        // Delete the temporary running-config copy (ignore errors)
        traceInfo(worker, "SFTP deleting running-config copy: " + readDeviceFile);
        print_line_exec(worker, "delete /noprompt " + readDeviceFile);

        return res.replace("\n", "\r\n"); // to match terminal output
    }


    /**
     * Modify input
     * @param
     * @return
     * @throws Exception
     */
    private String modifyInput(NedWorker worker, boolean convert, boolean partial, String res)
        throws ConfException, IOException, NedException {
        final long start = tick(0);
        final boolean isDevice = isDevice() || syncFile != null || offlineData != null;

        logInfo(worker, "BEGIN in-transforming"+(isDevice?"":" (NETSIM)"));

        //
        // Inject config
        //
        res = injectInput(worker, res);

        //
        // Assemble banner into a single quoted string with start and end marker
        //
        if (isDevice) {
            traceVerbose(worker, "in-transforming - banners");
            Pattern p = Pattern.compile("\nbanner (\\S+) (\\S)(.*?)\\2[\\S ]*?\r", Pattern.DOTALL);
            Matcher m = p.matcher(res);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String name = m.group(1);
                String marker = passwordQuote(m.group(2));
                String message = stringQuote(m.group(3));
                traceVerbose(worker, "transformed <= quoted banner "+name);
                m.appendReplacement(sb, Matcher.quoteReplacement("\nbanner "+name+" "+marker+" "+message+" "+marker));
            }
            m.appendTail(sb);
            res = sb.toString();
        }

        //
        // NETSIM and DEVICE
        //
        traceVerbose(worker, "in-transforming - quoting descriptions");
        int i;
        int n;
        String match;
        String toptag = "";
        String[] lines = res.split("\n");
        StringBuilder sbin = new StringBuilder();
        for (n = 0; n < lines.length; n++) {
            String line = lines[n];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = trimmed; // remove \r
            }
            String input = null;

            //
            // description
            //
            if ((i = line.indexOf(" description ")) >= 0) {
                if (toptag.startsWith("l2vpn")
                    || toptag.startsWith("router static")
                    || (i > 1 && line.charAt(i-1) == '#')) {
                    // Ignore '# description' entries in e.g. route-policy or sets
                    sbin.append(line+"\n");
                } else {
                    // Quote ' description ' strings
                    String desc = stringQuote(line.substring(i+13).trim());
                    sbin.append(line.substring(0,i+13) + desc + "\n");
                }
                continue;
            }

            //
            // alias [exec|config] *
            //
            if (toptag.startsWith("alias ")
                && (match = getMatch(trimmed, "alias (?:exec \\S+|config \\S+|\\S+) (.+)")) != null) {
                input = line.replace(match, stringQuote(match));
            }

            //
            // * route-policy
            //
            else if (isDevice
                     && (match = getMatch(line, "route-policy (\\S+(?:\\(.+?\\)))")) != null
                     && match.contains(" ")) {
                input = line.replace(match, "\""+match+"\"");
            }

            // Transform lines[n] -> XXX
            if (input != null && !input.equals(lines[n])) {
                if (input.isEmpty()) {
                    traceVerbose(worker, "transformed <= stripped '"+trimmed+"'");
                    continue;
                }
                traceVerbose(worker, "transformed <= '"+trimmed+"' to '"+input.trim()+"'");
                sbin.append(input+"\n");
            } else {
                sbin.append(line+"\n");
            }
        }
        res = sbin.toString();


        //
        // NETSIM - leave early
        //
        if (!isDevice) {
            logInfo(worker, "DONE in-transforming (NETSIM) "+tickToString(start));
            return res;
        }


        //
        // REAL DEVICES BELOW:
        //

        //
        // LINE-BY-LINE TRANSFORMATIONS
        //
        traceVerbose(worker, "in-transforming - line-by-line");
        lines = res.split("\n");
        final String[] sets = {
            "extcommunity-set rt",
            "extcommunity-set soo",
            "extcommunity-set opaque",
            "rd-set",
            "tag-set",
            "prefix-set",
            "as-path-set",
            "community-set",
            "large-community-set"
        };
        sbin = new StringBuilder();
        String trimmed = "";
        String[] group;
        String prevline = "";
        for (n = 0; n < lines.length; prevline = lines[n], n++) {
            String line = lines[n];
            trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // Update toptag
            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = trimmed; // remove \r
            }
            String input = null;

            //
            // tailf:cli-range-list-syntax
            //   class-map * / match vlan *
            //   class-map * / match traffic-class *
            //
            if (toptag.startsWith("class-map ")
                && (trimmed.startsWith("match vlan ") || trimmed.startsWith("match traffic-class"))) {
                input = line.replaceAll("([0-9])( )([0-9])", "$1,$3");
            }

            //
            // tailf:cli-range-list-syntax
            //   class-map * / match dscp *
            //
            else if (toptag.startsWith("class-map ") && trimmed.startsWith("match dscp ")
                     && (match = getMatch(trimmed, "match dscp(?: ipv[46])? (.+)")) != null) {
                input = line.replace(match, match.trim().replace(" ",","));
            }

            //
            // tailf:cli-range-list-syntax
            //   class-map * / match ipv4|6 icmp-code|type
            //
            else if (toptag.startsWith("class-map ") && trimmed.startsWith("match ipv")
                     && (match = getMatch(trimmed, "match ipv(?:4|6) icmp[-](?:code|type) (.+)")) != null) {
                input = line.replace(match, match.trim().replace(" ",","));
            }

            //
            // interface * / encapsulation dot1ad|dot1ad
            //
            else if (toptag.startsWith("interface ")
                     && getMatch(trimmed, "encapsulation(?: ambiguous)? dot1(?:ad|q) (.*)") != null) {
                input = line.replace(" , ", ",");
            }

            //
            // interface * / ipv4 address
            //
            else if (toptag.startsWith("interface ") && trimmed.startsWith("ipv4 address ")) {
                input = line.replaceAll("ipv4 address ([0-9.]+)/(\\d+)", "ipv4 address $1 /$2");
            }

            //
            // snmp-server host *
            //
            else if (toptag.startsWith("snmp-server host ")
                     && (match = getMatch(trimmed, "snmp-server host \\S+ (?:traps|informs)(?: encrypted| clear)? (\\S+)")) != null) {
                input = line.replace(match, passwordQuote(match));
            }

            //
            // snmp-server community *
            //
            else if (line.startsWith("snmp-server community ")
                     && (match = getMatch(trimmed, "snmp-server community(?: clear| encrypted)? (\\S+)")) != null) {
                input = line.replace(match, passwordQuote(match));
            }

            //
            // snmp-server contact|location
            //
            else if (toptag.startsWith("snmp-server ")
                     && (match = getMatch(trimmed, "snmp-server (?:contact|location) (.+)")) != null) {
                input = line.replace(match, stringQuote(match));
            }

            // Note: pce was changed to pcep in XR 6.5.x
            // segment-routing / traffic-eng / on-demand color * / dynamic / pcep
            // segment-routing / traffic-eng / policy * / candidate-paths / preference * / dynamic / pcep
            else if ("segment-routing".equals(toptag) && "pce".equals(trimmed)) {
                input = line.replace("pce", "pcep");
            }

            //
            // router igmp / interface * / static-group * inc-mask * * inc-mask *
            //
            else if (toptag.startsWith("router igmp") && trimmed.startsWith("static-group ")) {
                input = line.replace(" count ", " source-count ");
                input = input.replace(" inc-mask ", " source-inc-mask ");
                input = input.replaceFirst(" source-inc-mask ", " inc-mask ");
                input = input.replaceFirst(" source-count ", " count ");
            }

            // evpn / evi * / bgp / route-target * stitching-deprecated [deprecated in 6.5.2]
            else if (toptag.startsWith("evpn")) {
                input = line.replace("stitching-deprecated", "stitching");
            }

            // XR bug patch: can't read it's own config in:
            //   lmp / gmpls optical-uni / controller *
            //   mpls ldp / nsr
            //   mpls traffic-eng / attribute-set
            //   router igmp / version
            //
            else if ("lmp".equals(toptag) && "  !\r".equals(line)) {
                input = "  exit\r";
            } else if ("mpls ldp".equals(toptag) && " !\r".equals(line)) {
                input = " exit\r";
            } else if ("mpls traffic-eng".equals(toptag) && " !\r".equals(line)) {
                input = " exit\r";
            } else if ("router igmp".equals(toptag) && "  !\r".equals(line)) {
                input = "  exit\r";
            } else if (toptag.startsWith("router isis") && "  !\r".equals(line)) {
                input = "  exit\r"; // router isis * / interface * / address-family * /
            }

            //
            // !<comment>
            //
            else if (readStripComments
                     && (trimmed.startsWith("!") && trimmed.length() > 1) || line.startsWith("! ")) {
                input = "";
            }

            //
            // route-policy
            //
            else if (toptag.startsWith("route-policy ")) {
                sbin.append(line+"\n");
                // New line list API - prepend line numbers to lines
                if (apiEditRoutePolicy) {
                    String name = toptag.replace("\"", "");
                    ConfPath cp = new ConfPath(operPath+"/edit-list{\""+name+"\"}");
                    String[] lineno = null;
                    if (cdbOper.exists(cp)) {
                        String val = ConfValue.getStringByValue(cp, cdbOper.getElem(cp.append("/lineno")));
                        lineno = val.split(" ");
                    }
                    int indx = 0;
                    int num = 0;
                    for (n = n + 1; n < lines.length; n++) {
                        if (lines[n].trim().equals("end-policy")) {
                            break;
                        }
                        if (lineno != null && indx < lineno.length) {
                            num = Integer.parseInt(lineno[indx++]);
                        } else {
                            num += 10;
                        }
                        sbin.append(Integer.toString(num)+" "+lines[n].trim()+"\n");
                    }
                }
                // Single buffer API - make contents into a single quoted string
                else {
                    StringBuilder policy = new StringBuilder();
                    for (n = n + 1; n < lines.length; n++) {
                        if (lines[n].trim().equals("end-policy")) {
                            break;
                        }
                        policy.append(lines[n] + "\n");
                    }
                    if (policy.length() > 0) {
                        traceVerbose(worker, "transformed <= quoted '"+toptag+"'");
                        sbin.append(stringQuote(policy.toString())+"\n");
                    }
                }
            }

            //
            // group - make contents into a single quoted string
            //
            else if (toptag.startsWith("group ")) {
                sbin.append(line+"\n");
                StringBuilder content = new StringBuilder();
                for (n = n + 1; n < lines.length; n++) {
                    if (lines[n].trim().equals("end-group")) {
                        break;
                    }
                    content.append(lines[n] + "\n");
                }
                if (content.length() > 0) {
                    traceVerbose(worker, "transformed <= quoted '"+toptag+"'");
                    String quoted = stringQuote(content.toString());
                    quoted = quoted.replace("\\\\", "\\\\\\\\"); // Preserve backslash
                    sbin.append(quoted+"\n");
                }
            }

            //
            // mpls traffic-eng / auto-tunnel backup
            //
            else if ("mpls traffic-eng".equals(toptag)
                     && !"mpls traffic-eng".equals(prevline)
                     && " auto-tunnel backup\r".equals(line)) {
                // XR bugpatch: can't read it's own config
                traceVerbose(worker, "transformed <= injecting 'mpls traffic-eng' for XR bad ordering");
                sbin.append("mpls traffic-eng\n");
            }

            //
            // service-policy input-list *
            // service-policy output-list *
            //
            else if (apiSpList && trimmed.startsWith("service-policy input ")) {
                input = line.replace(" service-policy input ", " service-policy input-list ");
            }
            else if (apiSpList && trimmed.startsWith("service-policy output ")) {
                input = line.replace(" service-policy output ", " service-policy output-list ");
            }

            //
            // policy-map * / class * / random-detect discard-class *
            //
            else if (toptag.startsWith("policy-map ")
                     && trimmed.startsWith("random-detect discard-class ")
                     && trimmed.contains(",")
                     && (group = getMatches(trimmed, "random-detect discard-class (\\S+)( .*)")) != null) {
                // XR concats discard-class entries with a comma between, i.e: x,y,z
                String[] vals = group[1].split(",");
                traceVerbose(worker, "transformed <= splitting '"+trimmed+"' in "+vals.length+" entries");
                for (int v = 0; v < vals.length; v++) {
                    sbin.append("random-detect discard-class "+vals[v]+group[2]+"\n");
                }
                continue;
            }

            //
            // sets - strip commas
            //
            for (int s = 0; s < sets.length; s++) {
                if (line.startsWith(sets[s]+" ") || "policy-global".equals(trimmed)) {
                    traceVerbose(worker, "transformed <= stripped commas from set: "+line);
                    sbin.append(line+"\n");
                    for (n = n + 1; n < lines.length; n++) {
                        if (lines[n].trim().equals("end-set")
                            || lines[n].trim().equals("end-global")) {
                            break;
                        }
                        sbin.append(lines[n].replace(",","")+"\n");
                    }
                    break;
                    // fall through for appending end-set/global line
                }
            }

            //
            // Transform lines[n] -> XXX
            //
            if (n >= lines.length) {
                break;  // Note: 'n' may have been updated above, i.e. 'line' no longer valid
            }
            if (input != null && !input.equals(lines[n])) {
                if (input.isEmpty()) {
                    traceVerbose(worker, "transformed <= stripped '"+trimmed+"'");
                    continue;
                }
                traceVerbose(worker, "transformed <= '"+trimmed+"' to '"+input.trim()+"'");
                sbin.append(input+"\n");
            } else {
                sbin.append(lines[n]+"\n");
            }
        }
        res = sbin.toString();


        //
        // ned-setting cisco-iosxr read show-running-strict-mode
        //
        lines = res.split("\n");
        sbin = new StringBuilder();
        if (showRunningStrictMode) {
            traceInfo(worker, "in-transforming - strict mode");
            for (n = 0; n < lines.length; n++) {
                trimmed = lines[n].trim();
                if (lines[n].startsWith("!") && "!".equals(trimmed)) {
                    sbin.append(lines[n].replace("!", "xyzroot 0")+"\n");
                } else if ("!".equals(trimmed)) {
                    sbin.append(lines[n].replace("!", "exit")+"\n");
                } else {
                    sbin.append(lines[n]+"\n");
                }
            }
        }

        // Mode-sensitive fixes:
        else {
            traceVerbose(worker, "in-transforming - injecting 'xyzroot 0' top-root markers");
            for (n = 0; n < lines.length; n++) {
                if (lines[n].startsWith("interface ")) {
                    sbin.append("xyzroot 0\n");
                    for (; n < lines.length; n++) {
                        if (lines[n].equals("!\r")) {
                            sbin.append("exit\n");
                            break;
                        }
                        sbin.append(lines[n]+"\n");
                        if (lines[n].equals("exit\r")) {
                            break;
                        }
                    }
                    continue;
                }
                if (lines[n].startsWith("vrf ")) {
                    sbin.append("xyzroot 0\n");
                }
                sbin.append(lines[n]+"\n");
            }
        }
        res = sbin.toString();

        // Done
        logInfo(worker, "DONE in-transforming "+tickToString(start));
        return res;
    }


    /**
     * Inject config in input
     * @param
     * @return modified buffer
     */
    private String injectInput(NedWorker worker, String res) {

        StringBuffer first = new StringBuffer();
        if (apiEditRoutePolicy) {
            traceInfo(worker, "transformed <= inserted 'tailfned api edit-route-policy'");
            first.append("tailfned api edit-route-policy\n");
        }
        if (apiSpList) {
            traceInfo(worker, "transformed <= inserted 'tailfned api service-policy-list'");
            first.append("tailfned api service-policy-list\n");
        }
        if (apiClassMapMatchAGList) {
            traceInfo(worker, "transformed <= inserted 'tailfned api class-map-match-access-group-list");
            first.append("tailfned api class-map-match-access-group-list\n");
        }

        StringBuffer last = new StringBuffer();
        if (includeCachedShowVersion) {
            // Add cached-show info to config
            last.append("cached-show version version " + iosversion + "\n");
            last.append("cached-show version model " + iosmodel.replace(" ", "-") + "\n");
            last.append("cached-show version serial-number " + iosserial + "\n");
        }

        res = "\n" + first.toString() + res + last.toString();

        //
        // read/replace-config ned-setting - inject/replace in running-config
        //
        if (!replaceConfig.isEmpty()) {
            traceInfo(worker, "in-transforming - replace-config ned-setting");
            for (int n = 0; n < replaceConfig.size(); n++) {
                String[] entry = replaceConfig.get(n);
                String regexp = entry[1];
                String replacement = entry[2];
                try {
                    Pattern p = Pattern.compile(regexp+"(?:[\r])?", Pattern.DOTALL);
                    Matcher m = p.matcher(res);
                    StringBuffer sb = new StringBuffer();
                    while (m.find()) {
                        traceInfo(worker, "transformed <= replaced "+stringQuote(m.group(0))
                                  +" with " + matcherToString(m, replacement));
                        m.appendReplacement(sb, replacement);
                    }
                    m.appendTail(sb);
                    res = sb.toString();
                } catch (Exception e) {
                    logError(worker, "ERROR in read/replace-config '"+entry[0]+"' regexp="
                             +stringQuote(regexp)+" replacement="+stringQuote(replacement), e);
                }
            }
        }

        return res;
    }


    /*
     **************************************************************************
     * showOffline
     **************************************************************************
     */

    /**
     * Parse and input given config
     * @param
     * @throws Exception
     */
    // @Override
    public void showOffline(NedWorker worker, String toptag, String data) throws Exception {
        try {
            logInfo(worker, "BEGIN SHOW-OFFLINE");
            this.offlineData = data;
            show(worker, toptag);
            logInfo(worker, "DONE SHOW-OFFLINE");
        } finally {
            this.offlineData = null;
        }
    }


    /*
     **************************************************************************
     * showPartial
     **************************************************************************
     */

    /**
     * Handler called when "commit no-overwrite" is used in NSO.
     * Dumps config from one or many specified locations in the
     * tree instead of dumping everything.
     *
     * @param worker  - The NED worker
     * @param cp      - Paths to dump
     * @throws Exception
     *
     * commit no-overwrite
     * devices partial-sync-from [ <xpath> <xpath>]
     */
    public void showPartial(NedWorker worker, String[] cp) throws Exception {
        final long start = tick(0);
        if (trace) {
            session.setTracer(worker);
        }
        lastGetConfig = null;

        logInfo(worker, "BEGIN SHOW PARTIAL");
        traceVerbose(worker, Arrays.toString(cp));


        if ("filter-mode".equals(nedSettings.getString("read/partial-show-method"))) {
            showPartialInternal(schema,
                                maapi,
                                turboParserEnable,
                                worker,
                                cp);
            logInfo(worker, "DONE SHOW PARTIAL (FILTERED) "+tickToString(start));
            return;
        }

        StringBuilder results = new StringBuilder();
        //
        // NETSIM - execute a 'show running' and dump the result
        //
        if (isNetsim()) {
            ArrayList<String> cmdPaths = new ArrayList<>(Arrays.asList(cp));
            for (String cmdPath : cmdPaths) {
                String show = "show running-config " + cmdPath.replace("\\", "");
                String dump = print_line_exec(worker, show);
                if (dump.contains("% No entries found.")) {
                    traceInfo(worker, "showPartial() WARNING: '"+cmdPath+"' not found");
                } else {
                    results.append(dump);
                }
                setReadTimeout(worker);
            }
        }

        //
        // Real XR device
        //
        else {
            ArrayList<String> cmdPaths = new ArrayList<>();
            ArrayList<String> pathsToDump = new ArrayList<>();

            // Scan protectedPaths and trim matches with too deep show
            for (int i = 0; i < cp.length; i++) {
                boolean isProtected = false;
                String path = cp[i];
                // Trim quotes around route-policy name
                String match = getMatch(path, "^route-policy \\\"(.*)\\\"$");
                if (match != null && match.contains(" ")) {
                    path = path.replace("\""+match+"\"", match);
                }
                // Scan paths and trim too deep shows
                for (Pattern pattern : protectedPaths) {
                    Matcher matcher = pattern.matcher(path);
                    if (matcher.matches()) {
                        String group = matcher.group(1).trim();
                        String trimmed = path.substring(0, path.indexOf(group));
                        traceVerbose(worker, "partial: trimmed '"+path+"' to '"+trimmed+"'");
                        cmdPaths.add(trimmed);
                        isProtected = true;
                        break;
                    }
                }
                if (!isProtected) {
                    cmdPaths.add(path);
                }
            }

            // Sort the path list such that shortest comes first
            class PathComp implements Comparator<String> {
                public int compare(String o1, String o2) {
                    int x = o1.length();
                    int y = o2.length();
                    if (x < y) {
                        return -1;
                    } else if (x == y) {
                        return 0;
                    } else {
                        return 1;
                    }
                }
            }
            Collections.sort(cmdPaths, new PathComp());

            // Filter out any overlapping paths
            for (String cmdPath : cmdPaths) {
                boolean isUniquePath = true;
                for (String p : pathsToDump) {
                    if (partialPathIn(cmdPath, p)) {
                        isUniquePath = false;
                        break;
                    }
                }
                if (isUniquePath) {
                    pathsToDump.add(cmdPath); // New path to dump
                } else {
                    traceVerbose(worker, "partial: stripped '"+cmdPath+"'");
                }
            }

            // Call all partial show commands
            for (String cmdPath : pathsToDump) {
                String dump = doShowPartial(worker, cmdPath);
                if (dump == null) {
                    throw new NedException("showPartial() :: failed to show '"+cmdPath+"'");
                }
                if (!dump.isEmpty()) {
                    // Trim show date stamp
                    Pattern p = Pattern.compile("\r(?:Tue|Mon|Wed|Thu|Fri|Sat|Sun) .*UTC");
                    Matcher m = p.matcher(dump);
                    if (m.find()) {
                        dump = dump.replace(m.group(0), "");
                    }
                    // Append output
                    results.append(dump);
                    setReadTimeout(worker);
                }
            }
        }

        //
        // Transform config
        //
        String config = results.toString();
        if (!config.trim().isEmpty()) {
            config = modifyInput(worker, true, true, config); // convert=true & partial=true
        }
        if (turboParserEnable && parseAndLoadXMLConfigStream(maapi, worker, schema, config)) {
            config = "";
        }

        logInfo(worker, "DONE SHOW PARTIAL "+tickToString(start));
        worker.showCliResponse(config);
    }

    // @Override
    public void showPartial(NedWorker w, ConfPath[] paths)
        throws Exception {
        showPartialInternal(schema, maapi, turboParserEnable, w, paths);
    }


    /*
     **************************************************************************
     * getDeviceConfiguration
     **************************************************************************
     */

    /**
     * Get device configuration
     * @param
     * @return
     * @throws Exception
     */
    @Override
    protected String getDeviceConfiguration(NedWorker worker) throws Exception {
        String config = getConfig(worker, true);
        config = modifyInput(worker, true, true, config); // convert=true & partial=true
        return config;
    }


    /**
     * Show partial config
     * @param
     * @return
     * @throws Exception
     */
    private String doShowPartial(NedWorker worker, String cmdPath)
        throws NedException, IOException, SSHSessionException {
        final String show = "show running-config " + cmdPath.replace("\\", "");

        String dump;
        if (cmdPath.trim().equals("admin")) {
            dump = getAdminConfig(worker, true);
        } else {
            dump = print_line_exec(worker, show);
        }

        if (dump.contains("% Invalid input detected")) {
            traceInfo(worker, "showPartial() '"+show+"' ERROR: "+dump);
            return "";
        }
        if (dump.contains("% No such configuration item")) {
            return "";
        }

        return dump;
    }


    /**
     * Partial path
     * @param
     * @return
     */
    private boolean partialPathIn(String longp, String shortp) {
        String[] pl = longp.split(" \\\\ ");
        String[] ps = shortp.split(" \\\\ ");
        for (int i = 0; i < ps.length; i++) {
            if (!ps[i].trim().equals(pl[i].trim())) {
                return false;
            }
        }
        return true;
    }


    /*
     **************************************************************************
     * getTransId
     **************************************************************************
     */

    /**
     * Calculate transaction-id
     * @param
     * @throws Exception
     */
    public void getTransId(NedWorker worker) throws Exception {
        final long start = tick(0);
        if (trace) {
            session.setTracer(worker);
        }

        // cisco-iosxr read transaction-id-method commit-list (default)
        String res = "";
        if (!adminLogin && "commit-list".equals(transActionIdMethod)) {
            logInfo(worker, "BEGIN GET-TRANS-ID (commit-list)");
            long t = nedReportProgress(worker, "reading commit id...", 0);
            try {
                res = getCommitId(worker);
                nedReportProgress(worker, "reading commit id ok", t);
            } catch (Exception e) {
                traceInfo(worker, "NOTICE: Fallback to config-hash transaction-id: "+e.getMessage());
                nedReportProgress(worker, "reading commit id error", t);
            }
        }

        // cisco-iosxr read transaction-id-method config-hash
        if (res.isEmpty()) {

            // Use last cached transformed config from applyConfig() secret code
            if (lastGetConfig != null) {
                logInfo(worker, "BEGIN GET-TRANS-ID (config-hash secrets)");
                res = lastGetConfig;
                lastGetConfig = null;
            }

            // Use running-config for string data
            else {
                logInfo(worker, "BEGIN GET-TRANS-ID (config-hash)");
                res = getConfig(worker, false);
                setReadTimeout(worker);
            }

            traceVerbose(worker, "TRANS-ID-BUF=\n+++ begin\n"+res+"\n+++ end");

            // Calculate checksum of running-config
            res = calculateMd5Sum(res);
        }

        // Get admin commit id
        if (isDevice() && readAdminShowRun) {
            final long t = nedReportProgress(worker, "reading admin id...", 0);
            try {
                res += ("+" + getAdminTransId(worker, t));
                nedReportProgress(worker, "reading admin id ok", t);
            } catch (Exception e) {
                nedReportProgress(worker, "reading admin id error", t);
            }
        }

        logInfo(worker, "DONE GET-TRANS-ID ("+res+") "+tickToString(start));
        worker.getTransIdResponse(res);
    }


    /**
     * Get commit id to use as transaction id
     * @param
     * @throws Exception
     */
    private String getCommitId(NedWorker worker)
        throws NedException, IOException, SSHSessionException {

        // First try showing only 1 commit id
        String res = print_line_exec(worker, "show configuration commit list 1");
        if (res.contains("The commit database is empty")) {
            throw new NedException("empty commit database");
        }
        if ((res = getMatch(res, "\n\\d+\\s+(\\d+) ")) != null) {
            return res;
        }

        // Second try showing all commit ids
        res = print_line_exec(worker, "show configuration commit list");
        if (res.contains("The commit database is empty")) {
            throw new NedException("empty commit database");
        }
        if ((res = getMatch(res, "\n(\\d+) ")) != null) {
            return res;
        }

        // Failure
        throw new NedException("failed to show last commit list id");
    }


    /**
     * Get admin transaction id
     * @param
     * @throws Exception
     */
    private String getAdminTransId(NedWorker worker, long t)
        throws NoSuchAlgorithmException, IOException, SSHSessionException, NedException {

        // Enter admin mode
        try {
            enterAdmin(worker);
        } catch (Exception e) {
            nedReportProgress(worker, "entering admin mode denied", t);
            return "admin";
        }

        try {
            String res;

            // Get last commit id and use for transaction id
            if ("commit-list".equals(transActionIdMethod)) {
                traceInfo(worker, "Getting last admin commit id");
                try {
                    // First try showing only 1 commit id
                    res = print_line_exec(worker, "show configuration commit list 1");
                    if (res.contains("The commit database is empty")) {
                        throw new NedException("empty commit database");
                    }
                    if ((res = getMatch(res, "\n\\d+\\s+(\\d+) ")) != null) {
                        return res;
                    }

                    // Second try showing all commit ids
                    res = print_line_exec(worker, "show configuration commit list");
                    if (res.contains("The commit database is empty")) {
                        throw new NedException("empty commit database");
                    }
                    if ((res = getMatch(res, "\n(\\d+) ")) != null) {
                        return res;
                    }
                } catch (Exception e) {
                    traceInfo(worker, "NOTICE: Fallback to config-hash admin transaction-id: "+e.getMessage());
                }
            }

            // cisco-iosxr read transaction-id-method config-hash
            res = getAdminConfig(worker, false);
            return calculateMd5Sum(res);

        } finally {
            exitAdmin(worker);
        }
    }


    /*
     **************************************************************************
     * prepareDry
     **************************************************************************
     */

    /**
     * Display config for commit dry-run
     * @param
     * @throws Exception
     */
    @Override
    public void prepareDry(NedWorker worker, String data) throws Exception {
        final long start = tick(0);
        if (trace && session != null) {
            session.setTracer(worker);
        }
        if (nsoRunningVersion < 0x7000000) {
            traceInfo(worker, "\n"+data);
        }

        String log = "BEGIN PREPARE-DRY (model="+iosmodel+" version="+iosversion+")";
        if (session == null) {
            log += " [offline]";
        }

        // ShowRaw used in debugging, to see cli commands before modification
        if (showRaw || data.contains("tailfned raw-run\n")) {
            logInfo(worker, log + " (raw)");
            showRaw = false;
            logInfo(worker, "DONE PREPARE-DRY (raw)"+tickToString(start));
            worker.prepareDryResponse(data);
            return;
        }

        logInfo(worker, log);

        // Clear global config data
        this.delayedCommit = new StringBuilder();

        // Modify data buffer
        final int fromTh = worker.getFromTransactionId();
        final int toTh = worker.getToTransactionId();
        try {
            StringBuilder sb = new StringBuilder();
            if (session == null && logVerbose) {
                sb.append("! Generated offline\n");
            }

            // Trigger custom extensions
            data = parseCLIDiff(worker, data);
            maapiAttach(worker, fromTh, toTh);

            // Trim meta-data if not logVerbose
            String[] lines = modifyOutput(worker, data, fromTh, toTh, "PREPARE-DRY");
            for (int n = 0; n < lines.length; n++) {
                String line = lines[n];
                if (line == null) {
                    continue;
                }
                if (!logVerbose && line.trim().startsWith(META_DATA)) {
                    continue;
                }
                sb.append(line+"\n");
            }

            // Show and trigger custom extensions on delayedCommit
            if (delayedCommit.length() > 0) {
                traceInfo(worker, "Handling delayedCommit cli:secret extensions");
                parseCLIDiffWithExtensions(worker, delayedCommit.toString(), true,
                                           Arrays.asList(new String[]{"cli:secret"}));
                sb.append("commit\n");
                sb.append(delayedCommit);
            }

            logInfo(worker, "DONE PREPARE-DRY"+tickToString(start));
            worker.prepareDryResponse(sb.toString());

        } finally {
            maapiDetach(worker, fromTh, toTh);
        }
    }


    /*
     **************************************************************************
     * applyConfig
     **************************************************************************
     *
     * NSO PHASES:
     *          prepare (send data to device)
     *           /   \
     *          v     v
     *       abort | commit (send confirmed commit)
     *               /   \
     *              v     v
     *          revert | persist (send confirming commit)
     */

    /**
     * Apply config
     * @param
     * @throws Exception
     */
    @Override
    public void applyConfig(NedWorker worker, int cmd, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {
        final long start = tick(0);
        if (trace) {
            session.setTracer(worker);
        }
        logInfo(worker, "BEGIN APPLY-CONFIG");

        // Apply the config
        doApplyConfig(worker, data);

        logInfo(worker, "DONE APPLY-CONFIG "+tickToString(start));
    }


    /**
     * Apply config on device
     * @param
     * @throws Exceptions
     */
    private void doApplyConfig(NedWorker worker, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {

        if (applyDelay > 0) {
            sleep(worker, (long)applyDelay * 1000, true);
        }

        // commit show-error
        if (iosversion.startsWith("4") || isNetsim() || adminLogin) {
            supportCommitShowError = false;
            commitCommand = commitCommand.replace(" show-error", "");
        }
        traceInfo(worker, "commit show-error = " + supportCommitShowError);

        // Clear global config data
        lastGetConfig = null;
        numCommit = 0;
        numAdminCommit = 0;
        delayedCommit = new StringBuilder();

        try {
            // Trigger custom extensions
            data = parseCLIDiff(worker, data);

            // Send and strip admin config
            String orgdata = data;
            data = commitAdminConfig(worker, data);

            // Send standard config
            if (!data.trim().isEmpty()) {
                if (adminLogin) {
                    throw new NedException("admin mode login, can't commit non-admin config");
                }
                sendConfig(worker, data, true);
                // In config mode
            }

            // Trigger customer extensions on delayedCommit config
            if (delayedCommit.length() > 0) {
                traceInfo(worker, "Handling delayedCommit cli:secret extensions");
                parseCLIDiffWithExtensions(worker, delayedCommit.toString(), true,
                                           Arrays.asList(new String[]{"cli:secret"}));
            }

        } catch (NedException|IOException|SSHSessionException|ApplyException e) {
            String reply = e.getMessage();
            if (!session.serverSideClosed()) {
                if (reply.contains("Failed to commit") && reply.contains("show configuration failed")) {
                    reply = print_line_exec(worker, "show configuration failed");
                }
                session.print("abort\n");
            }
            inConfig = false;
            throw new ApplyException(reply, false, false);
        }
    }


    /**
     * Commit admin config
     * @param
     * @return
     * @throws Exception
     */
    private String commitAdminConfig(NedWorker worker, String data)
        throws NedException, IOException, SSHSessionException, ApplyException {
        if (isNetsim()) {
            return data;
        }

        // Extract admin config and update data
        data = "\n" + data;
        int start = data.indexOf("\nadmin\n");
        if (start < 0) {
            return data;
        }
        int end = data.indexOf("\n exit-admin-config\n!");
        if (end <= start) {
            return data;
        }
        String adminData = data.substring(start + 7, end + 1);
        data = data.substring(end + 21);

        if (!readAdminShowRun) {
            throw new NedException("commiting admin config not supported with ned-setting "
                                   +"'read admin-show-running-config = false'");
        }

        traceInfo(worker, "Applying "+adminData.length()+" bytes of admin config");

        // Enter exec admin mode
        enterAdmin(worker);

        // Enter config mode and send admin config
        sendConfig(worker, adminData, false);

        // Commit admin config
        print_line_wait_oper(worker, "commit", writeTimeout);
        numAdminCommit++;

        // Exit admin config mode
        exitConfig(worker, "admin");

        // Exit admin mode
        exitAdmin(worker);

        // Return remaining config to apply
        return data;
    }


    /**
     * Send config to device
     * @param
     * @throws Exception
     */
    private void sendConfig(NedWorker worker, String data, boolean useSftp)
        throws NedException, IOException, SSHSessionException, ApplyException {

        //
        // Modify data
        //
        int fromTh = worker.getFromTransactionId();
        int toTh = worker.getToTransactionId();
        String[] lines = null;
        try {
            maapiAttach(worker, fromTh, toTh);
            lines = modifyOutput(worker, data, fromTh, toTh, "APPLY-CONFIG");
        } catch (Exception e) {
            maapiDetach(worker, fromTh, toTh);
            throw e;
        }

        //
        // Send data
        //
        long start = nedReportProgress(worker, "sending config...", 0);
        try {
            // Refresh read timeout
            lastTimeout = setReadTimeout(worker);

            // Enter config mode
            enterConfig(worker);

            // Check if should disable SFTP
            if (useSftp) {
                // Internal commit (injected by customer?), can't use SFTP to apply config
                if (data.contains("\ncommit")) {
                    traceInfo(worker, "Disabling SFTP transfer due to embedded commit command");
                    useSftp = false;
                }
                // XR bug
                if (data.contains("no tacacs-server host ")) {
                    traceInfo(worker, "Disabling SFTP transfer due to XR bug with 'tacacs-server host' config");
                    useSftp = false;
                }
            }

            // Send config
            if (useSftp && lines.length >= writeSftpThreshold) {
                // Use SFTP to upload file and then load to candidate
                sftpUploadConfig(worker, linesToString(lines));
            } else {
                // Send config using CLI
                doSendConfig(worker, lines);
            }

            // Done
            nedReportProgress(worker, "sending config ok", start);

        } catch (Exception e) {
            nedReportProgress(worker, "sending config error", start);
            throw e;

        } finally {
            maapiDetach(worker, fromTh, toTh);
        }
    }


    /**
     * @param
     * @throws Exception
     */
    private void doSendConfig(NedWorker worker, String[] lines)
        throws NedException, IOException, SSHSessionException, ApplyException {
        final long startSending = tick(0);

        logInfo(worker, "BEGIN sending "+lines.length+" line(s)");
        String line = "";
        ModeStack modeStack = new ModeStack();
        try {
            // Send chunk of chunkSize
            int length = lines.length;
            for (int n = 0; n < lines.length;) {

                // Copy in up to chunkSize config commands in chunk
                StringBuilder sb = new StringBuilder();
                int num = 0;
                final int start = n;
                for (; n < lines.length; n++) {
                    if (num == chunkSize) {
                        break;
                    }
                    line = lines[n];
                    if (line == null || line.isEmpty()) {
                        length--;
                        continue;
                    }

                    String trimmed = line.trim();
                    if ("!".equals(trimmed)) {
                        length--;
                        continue;
                    }

                    // meta-data
                    if (trimmed.startsWith(META_DATA)) {
                        length--;
                        // :: secret
                        if (n + 1 < lines.length && trimmed.contains(" :: secret")) {
                            lines[n + 1] = decryptSecret(worker, lines[n + 1]);
                        }
                        continue;
                    }

                    // XR aa ae oe character twerk [RT30152]
                    if (trimmed.startsWith("description ")) {
                        line = trimmed;
                    }

                    // Append line
                    sb.append(line + "\n");
                    num++;
                }
                if (num == 0) {
                    break;
                }

                // Send chunk of 'num' line(s) to the device
                String chunk = sb.toString();
                traceVerbose(worker, "SENDING line(s) "+(start+1)+"-"+(start+num)+"/"+length);
                session.print(chunk);

                // Check device reply, one line at the time
                for (int i = start; i < n; i++) {
                    line = lines[i];
                    if (line == null || line.isEmpty()) {
                        continue;
                    }
                    modeStack.update(line);
                    String trimmed = line.trim();
                    if (trimmed.startsWith(META_DATA) || "!".equals(trimmed)) {
                        continue;
                    }

                    // Update timeout
                    lastTimeout = resetReadTimeout(worker, lastTimeout);

                    // Check device echo and possible input error
                    noprint_line_wait(worker, line);
                }
            }

            // Make sure we have exited from all sub-modes
            moveToTopConfig(worker);

            // Debug code:
            if ("prepare".equals(failphase)) {
                failphase = "";
                throw new NedException("PREPARE :: debug exception in prepare");
            }

            logInfo(worker, "DONE sending "+lines.length+" line(s) "+tickToString(startSending));

        } catch (Exception e) {
            throw new NedException(e.getMessage()+modeStack.toString(), e);
        }
    }


    /**
     * Maapi.decrypt secret(s) in a config line
     * @param
     * @return
     */
    private String decryptSecret(NedWorker worker, String line) {
        Pattern p = Pattern.compile("( \\$[48]\\$[^\\s]*)"); // " $4$<key>" || " $8<key>"
        Matcher m = p.matcher(line);
        while (m.find()) {
            String password = line.substring(m.start() + 1, m.end());
            try {
                traceDebug2(worker, "decryptSecret: "+stringQuote(password));
                String decrypted = mCrypto.decrypt(password);
                traceVerbose(worker, "transformed => Maapi.decrypted secret: "+password);
                line = line.substring(0, m.start()+1)
                    + decrypted
                    + line.substring(m.end(), line.length());
            } catch (Exception e) {
                // Ignore exceptions, since can't tell if $8 is NSO or IOS encrypted
                traceDebug2(worker, "decryptSecret Exception: "+e.getMessage());
                return line;
            }
            m = p.matcher(line);
        }
        return line;
    }


    /**
     * Modify output data
     * @param
     * @return
     * @throws NedException
     */
    private String[] modifyOutput(NedWorker worker, String data, int fromTh, int toTh, String function)
        throws NedException {
        long start = nedReportProgress(worker, "modifying output...", 0);
        try {

            //
            // Edit data
            //
            if (apiEditRoutePolicy) {
                data = editData(worker, data, toTh);
            }

            //
            // Scan meta-data and modify data
            //
            traceInfo(worker, function + " out-transforming - meta-data");
            data = metaData.modifyData(worker, data, fromTh, toTh, delayedCommit);

            //
            // Modify sets
            //
            if (isDevice()) {
                traceVerbose(worker, function + " out-transforming - preparing sets");
                data = modifyOutputSets(worker, data, toTh);
            }

            //
            // Reorder output config
            //
            traceVerbose(worker, function + " out-transforming - reordering config");
            data = reorderOutput(worker, data);

            //
            // ned-settings cisco-iosxr auto CSCtk60033-patch - delete class-maps used in policy-maps last
            //
            if (autoCSCtk60033Patch && data.contains("\npolicy-map ")) {
                traceVerbose(worker, function + " out-transforming - CSCtk60033 patch");
                data = outputPatchCSCtk60033(worker, data, fromTh);
            }

            //
            // ned-settings cisco-iosxr auto aaa-tacacs-patch - delete aaa group server tacacs last
            //
            if (autoAaaTacacsPatch && data.contains("aaa authentication login default")
                && data.contains("\nno aaa group server tacacs")) {
                traceVerbose(worker, function + " out-transforming - aaa-tacacs patch");
                data = outputPatchAAATacacs(worker, data);
            }

            //
            // LINE-BY-LINE - applyConfig
            //
            traceInfo(worker, function + " out-transforming - lines");
            String meta = "";
            String toptag = "";
            String[] group;
            String[] lines = data.split("\n");
            StringBuilder sb = new StringBuilder();
            for (int n = 0; n < lines.length; n++) {
                String line = lines[n];
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                String output = null;
                boolean traceChange = true;
                final String cmdtrim = trimmed.startsWith("no ") ? trimmed.substring(3) : trimmed;
                String nextline = (n + 1 < lines.length) ? lines[n+1] : "";

                // ! meta-data
                if (trimmed.startsWith(META_DATA)) {
                    if (!line.equals(nextline)) {
                        // Ignore/strip duplicate meta-data annotation
                        meta = meta + line + "\n";
                        sb.append(line+"\n");
                    }
                    continue;
                }

                // Update toptag
                if (Character.isLetter(line.charAt(0))) {
                    toptag = trimmed;
                }

                //
                // NETSIM and DEVICE
                //

                //
                // policy-map * / class * / police rate
                // NSO bug, track #15958
                //
                if (toptag.startsWith("policy-map ") && "police rate".equals(trimmed)) {
                    output = "  no police";
                }

                //
                // cached-show
                //
                else if (cmdtrim.startsWith("cached-show ")) {
                    output = "!" + line;
                }

                //
                // service-policy input-list *
                // service-policy output-list *
                //
                else if (apiSpList && cmdtrim.startsWith("service-policy input-list ")) {
                    output = line.replace(" service-policy input-list ", " service-policy input ");
                } else if (apiSpList && cmdtrim.startsWith("service-policy output-list ")) {
                    output = line.replace(" service-policy output-list ", " service-policy output ");
                }

                // xyzexit [debug trick]
                if (line.startsWith("xyzexit ")) {
                    for (int e = 0; e < Integer.parseInt(line.substring(8)); e++) {
                        sb.append("exit\n");
                    }
                    continue;
                }

                //
                // NETSIM
                //
                if (isNetsim()) {
                    int i;

                    // description patch for netsim, quote text and escape "
                    if (trimmed.startsWith("description ") && (i = line.indexOf("description ")) >= 0) {
                        String desc = line.substring(i+12).trim(); // Strip initial white spaces, added by NCS
                        if (desc.charAt(0) != '"') {
                            desc = desc.replaceAll("\\\"", "\\\\\\\""); // Convert " to \"
                            output = line.substring(0,i+12) + "\"" + desc + "\""; // Quote string, add ""
                        }
                    }

                    // no alias Java 'tailf:cli-no-value-on-delete' due to NCS bug
                    else if (line.startsWith("no alias ") &&
                             (group = getMatches(line, "(no alias(?: exec| config)? \\S+ ).*")) != null) {
                        output = group[1];
                    }

                    // alias patch
                    else if (line.startsWith("alias ") &&
                             (group = getMatches(line, "(alias(?: exec| config)? \\S+ )(.*)")) != null) {
                        String alias = group[2].replaceAll("\\\"", "\\\\\\\""); // Convert " to \"
                        output = group[1] + "\"" + alias + "\""; // Quote string, add ""
                    }

                    // Fall through for adding line (transformed or not)
                }


                //
                // DEVICE
                //
                else {
                    String match;

                    //
                    // route-policy
                    //
                    if (trimmed.contains("route-policy ") && !cmdtrim.startsWith("description ")) {
                        lines[n] = line.replace("\"", ""); // Strip quotes around name
                        // Note: Fall through without 'else if' to check additional transforms
                    }

                    //
                    // tailfned api
                    //
                    if (cmdtrim.startsWith("tailfned api")) {
                        output = "!" + line;
                    }


                    //
                    // cisco-iosxr auto acl-delete-patch true with:
                    //  no ipv4 access-list *
                    //  no ipv6 access-list *
                    //
                    else if (autoAclDeletePatch
                             && (line.startsWith("no ipv4 access-list ") || line.startsWith("no ipv6 access-list "))
                             && (match = getMatch(line, " access-list (\\S+)")) != null
                             && getMatch(data, "\n no match access-group (ipv4|ipv6) "+match+"\n") != null) {
                        traceInfo(worker, "transformed => applied acl-delete-patch, applying '"+trimmed+"' in separate last commit");
                        delayedCommit.append(line+"\n");
                        continue;
                    }

                    //
                    // interface * / ipv4 address x.y.z.w /prefix
                    //
                    else if (line.matches("^\\s*(no )?ipv4 address \\S+ /(\\d+).*$")) {
                        output = line.replaceFirst(" (\\S+) /(\\d+)", " $1/$2");
                    }

                    //
                    // class-map * / match vlan [inner] *
                    // class-map * / match traffic-class *
                    // class-map * / match dscp *
                    // class-map * / match ipv4|ipv6 icmp-*
                    //
                    else if (toptag.startsWith("class-map ")
                             && (cmdtrim.startsWith("match vlan ")
                                 || cmdtrim.startsWith("match traffic-class ")
                                 || cmdtrim.startsWith("match ipv4 icmp-")
                                 || cmdtrim.startsWith("match ipv6 icmp-")
                                 || cmdtrim.startsWith("match dscp "))) {
                        output = line.replace(",", " ");
                    }

                    //
                    // snmp-server location|contact *
                    //
                    else if (cmdtrim.startsWith("snmp-server ")
                             && (group = getMatches(line, "(snmp-server (?:location|contact) )[ ]*(.+)")) != null) {
                        output = group[1] + textDequote(group[2]);
                    }

                    //
                    // interface tunnel-te* / path-selection
                    //
                    else if (iosversion.startsWith("5")
                             && toptag.startsWith("interface tunnel-te")
                             && " path-selection".equals(line)) {
                        for (n = n + 1; n < lines.length; n++) {
                            if (lines[n].startsWith("  no ")) {
                                sb.append(lines[n].replace("  no ", " no path-selection ")+"\n");
                            } else if (lines[n].startsWith("  ")) {
                                sb.append(" path-selection "+lines[n].trim()+"\n");
                            } else {
                                break;
                            }
                        }
                        continue; // flush ' exit'
                    }

                    //
                    // router igmp / interface * / static-group * inc-mask * * inc-mask *
                    //
                    else if (toptag.startsWith("router igmp") && trimmed.startsWith("static-group ")) {
                        output = line.replace(" source-", " ");
                    }

                    //
                    // mpls ldp / label / accept / from * for
                    //
                    else if ("mpls ldp".equals(toptag)
                             && line.startsWith("   from ") && line.contains(" for ")) {
                        output = line.replaceFirst("from (\\S+) for (\\S+)", "for $2 from $1");
                    }

                    //
                    // aaa attribute format * / format-string
                    // dhcp ipv4 / interface * information option format-type ? format-string
                    //
                    else if (((toptag.startsWith("aaa attribute format ") && cmdtrim.startsWith("format-string "))
                              || (toptag.startsWith("dhcp ipv") && line.contains(" format-string ")))
                             && (match = getMatch(trimmed, "format-string(?: length \\d+)? (\\S+)")) != null
                             && !match.startsWith("\"")) {
                        output = line.replace(match, "\""+match+"\"");
                    }

                    // Note: pce was changed to pcep in XR 6.5.x
                    // segment-routing / traffic-eng / on-demand color * / dynamic / pcep
                    // segment-routing / traffic-eng / policy * / candidate-paths / preference * / dynamic / pcep
                    //
                    else if ("segment-routing".equals(toptag) && "pcep".equals(trimmed)) {
                        output = line.replace("pcep", "pce");
                    }

                    //
                    // route-policy * / xxx
                    // group * / xxx
                    //
                    else if ((line.startsWith("route-policy ") && !apiEditRoutePolicy)
                             || line.startsWith("group ")) {
                        // Dequote and split single quoted string, example:
                        // route-policy <NAME>
                        //   "if (xxx) then \r\n statement(s) \r\n endif\r\n"
                        //  end-policy
                        traceChange = false;
                        if (line.startsWith("group ")
                            && maapiExists(worker,fromTh,confPath+"group{"+line.replace("group ","").trim()+"}/line")) {
                            sb.append("no "+line+"\n"); // group does not reset old contents
                        }
                        sb.append(lines[n++]+"\n");
                        if (lines[n].trim().startsWith("no ")) {
                            continue; // Ignore delete, entries are all reset
                        } else if (lines[n].trim().startsWith("\"")) {
                            traceVerbose(worker, "transformed => dequoted "+trimmed);
                            String value = stringDequote(lines[n++].trim());
                            String[] values = value.split("\n");
                            for (int v = 0; v < values.length; v++) {
                                sb.append(values[v].replace("\r", "")+"\n");
                            }
                            // note: end-policy will be added in next loop
                        }
                    }

                    // Fall through for adding line (transformed or not)
                }

                //
                // Transform lines[n] -> XXX
                //
                if (output != null && !output.equals(lines[n])) {
                    if (output.isEmpty()) {
                        if (traceChange) {
                            traceVerbose(worker, "transformed => stripped '"+trimmed+"'");
                        }
                    } else {
                        if (traceChange) {
                            traceVerbose(worker, "transformed => '"+trimmed+"' to '"+output.trim()+"'");
                        }
                        sb.append(output+"\n");
                    }
                }

                // Append to sb
                else if (lines[n] != null && !lines[n].isEmpty()) {
                    sb.append(lines[n]+"\n");
                }

                meta = "";
            }
            data = "\n" + sb.toString();

            //
            // Inject command(s)
            //
            if (!injectCommand.isEmpty()) {
                traceInfo(worker, function + " out-transforming - injecting commands");
                for (int n = 0; n < injectCommand.size(); n++) {
                    String[] entry = injectCommand.get(n);
                    data = injectOutputData(worker, data, entry, "=>");
                }
            }

            //
            // Modify banner
            //
            lines = data.trim().split("\n");
            if (isNetsim()) {
                return lines;
            }
            for (int n = 0; n < lines.length; n++) {
                String line = lines[n];
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                //
                // banner <type> <marker> "<message>" <marker>
                //
                if (trimmed.startsWith("banner ")) {
                    Pattern p = Pattern.compile("banner (\\S+) (\\S+) (.*) (\\S+)");
                    Matcher m = p.matcher(line);
                    if (m.find()) {
                        String marker = m.group(2);
                        if (marker.charAt(0) == '"') {
                            marker = passwordDequote(marker);
                        }
                        String message = textDequote(m.group(3));
                        message = message.replace("\r", "");  // device adds \r itself
                        traceVerbose(worker, "transformed => dequoted banner "+m.group(1));
                        lines[n] = "banner "+m.group(1)+" "+marker+message+marker;
                    }
                }

            }


            //
            // Done
            //
            nedReportProgress(worker, "modifying output ok", start);
            traceDebug2(worker, function+"_AFTER:\n"+linesToString(lines));
            if (delayedCommit.length() > 0) {
                traceInfo(worker, function+"_DELAYED_COMMIT:\n"+delayedCommit.toString());
            }

            return lines;

        } catch (Exception e) {
            nedReportProgress(worker, "modifying output error", start);
            throw e;
        }
    }


    /**
     * Edit data
     * @param
     * @return
     * @throws NedException
     */
    private String editData(NedWorker worker, String data, int toTh) throws NedException {

        String[] lines = data.split("\n");
        int n = 0;
        try {
            StringBuilder sb = new StringBuilder();
            for (; n < lines.length; n++) {
                String line = lines[n];
                String trimmed = line.trim();
                String cmd = trimmed.startsWith("no ") ? trimmed.substring(3) : trimmed;

                // route-policy *
                if (line.startsWith("route-policy ")) {
                    sb.append(line+"\n");
                    // Read to-transaction and create line number oper cache
                    String name = getMatch(cmd, "route-policy\\s+(.+)");
                    String path = confPath+"route-policy-edit/route-policy{"+name+"}";
                    String lineno = editListToStringBuilder(worker, path, toTh, sb);
                    if (!lineno.isEmpty()) {
                        name = line.replace("\"", "");
                        ConfPath cp = new ConfPath(operPath+"/edit-list{\""+name+"\"}");
                        if (!cdbOper.exists(cp)) {
                            cdbOper.create(cp);
                        }
                        cdbOper.setElem(new ConfBuf(lineno.trim()), cp.append("/lineno"));
                    }
                    // Trim line changes from NSO
                    for (; n < lines.length; n++) {
                        if (lines[n].trim().equals("end-policy")) {
                            sb.append(lines[n].trim()+"\n");
                            break;
                        }
                    }
                    continue;
                }

                // no route-policy *
                else if (line.startsWith("no route-policy ")) {
                    // Delete line number oper cache
                    String name = cmd.replace("\"", "");
                    ConfPath cp = new ConfPath(operPath+"/edit-list{\""+name+"\"}");
                    if (cdbOper.exists(cp)) {
                        cdbOper.delete(cp);
                    }
                }

                // Append line
                sb.append(line+"\n");
            }
            data = "\n" + sb.toString();
        } catch (Exception e) {
            throw new NedException("editData '"+lines[n]+"' ERROR: ", e);
        }
        return data;
    }


    /**
     * Modify output sets
     * @param
     * @return
     * @throws NedException
     */
    private String modifyOutputSets(NedWorker worker, String data, int toTh) throws NedException {

        int n = 0;
        String[] lines = data.split("\n");
        try {
            StringBuilder sb = new StringBuilder();
            for (; n < lines.length; n++) {
                String line = lines[n];
                sb.append(line+"\n");

                // Get set config path and list name
                String cpath = setGetPath(line);
                if (cpath == null) {
                    continue;
                }
                String path;
                if ("policy-global".equals(cpath)) {
                    path = confPath + cpath;
                } else {
                    String name = getMatch(line, ".* (\\S+)");
                    path = confPath+cpath.replace(" ", "/")+"{"+name+"}";
                }

                // Read to-transaction to (re-)send all entries, add comma and trim ios-regex
                int num = setListToStringBuilder(worker, path, toTh, sb);
                if (num > 0) {
                    traceInfo(worker, "transformed => restored "+num+" line(s) in "+line.trim());
                }

                // Trim line changes from NSO
                for (; n < lines.length; n++) {
                    if (lines[n].trim().equals("end-set") || lines[n].trim().equals("end-global")) {
                        sb.append(lines[n].trim()+"\n");
                        break;
                    }
                }
            }
            data = "\n" + sb.toString();
        } catch (Exception e) {
            throw new NedException("modifyOutputSets '"+lines[n]+"' ERROR: ", e);
        }
        return data;
    }


    /**
     *
     * @param
     * @return
     */
    private String setGetPath(String line) {
        String [] sets = {
            "rd-set ",
            "tag-set ",
            "prefix-set ",
            "as-path-set ",
            "community-set ",
            "large-community-set ",
            "extcommunity-set rt ",
            "extcommunity-set soo ",
            "extcommunity-set opaque ",
            "policy-global"
        };
        for (int i = 0; i < sets.length; i++) {
            if (line.startsWith(sets[i])) {
                return sets[i].trim();
            }
        }
        return null;
    }


    /**
     * Wait for echo and verify response of previously sent line(s)
     * @param
     * @return
     * @throws Exceptions
     */
    private void noprint_line_wait(NedWorker worker, String line)
        throws NedException, IOException, SSHSessionException {

        // (1) - Expect the echo of the line(s) we sent
        for (String wait: line.trim().split("\n")) {
            traceDebug3(worker, "noprint_line_wait() - waiting for echo: "+stringQuote(wait));
            session.expect(new String[] { Pattern.quote(wait) }, worker);
            traceDebug3(worker, "noprint_line_wait() - got echo: "+stringQuote(wait));
        }

        // (2) - Wait for the prompt
        String match = "#";
        try {
            traceDebug3(worker, "noprint_line_wait() - waiting for prompt");
            NedExpectResult res;
            try {
                res = session.expect(NOPRINT_LINE_WAIT_PATTERN, worker);
            } catch (Exception e) {
                if (session.serverSideClosed()) {
                    throw new NedException("\n% Server side closed");
                }
                throw e;
            }
            match = expectGetMatch(res);
            traceDebug3(worker, "noprint_line_wait() - prompt matched("+res.getHit()+"): "+stringQuote(match));
            switch (res.getHit()) {
            case 0:  // "Uncommitted changes found, commit them"
                session.print("no\n"); // Send a 'no'
                throw new NedException("\n% Exited from config mode");
            case 1:  // config prompt
                break;
            default: // exec prompt
                throw new NedException("\n% Exited from config mode");
            }

            // (3) - Look for errors shown on screen after command was sent
            String reply = res.getText();
            if (isCliError(worker, reply)) {
                throw new NedException(reply);
            }

        } catch (Exception e) {
            throw new NedException("command: \n"+match+" "+line+" "+e.getMessage());
        }
    }


    /**
     * Check if device reply is an error
     * @param
     * @return
     */
    private boolean isCliError(NedWorker worker, String reply) {

        reply = reply.trim();
        if (reply.isEmpty()) {
            return false;
        }
        traceDebug(worker, "Checking device reply="+stringQuote(reply));

        // Ignore dynamic warnings [case sensitive]
        for (int n = 0; n < dynamicWarning.size(); n++) {
            if (findString(dynamicWarning.get(n), reply) >= 0) {
                traceInfo(worker, "ignoring dynamic warning: '"+reply+"'");
                return false;
            }
        }

        // Check device error keywords:
        reply = reply.toLowerCase();
        for (int n = 0; n < staticErrors.length; n++) {
            if (reply.contains(staticErrors[n])) {
                traceInfo(worker, "");
                return true;
            }
        }

        // Not an error
        return false;
    }


    /**
     *
     * @param
     * @throws Exceptions
     */
    private void print_line_wait_oper(NedWorker worker, String line, int timeout)
        throws SSHSessionException, IOException, NedException {
        resetTimeout(worker, timeout, 0);
        print_line_wait_oper0(worker, line, timeout);
        setReadTimeout(worker);
    }


    /**
     *
     * @param
     * @throws Exception
     */
    private void print_line_wait_oper(NedWorker worker, String line)
        throws IOException, SSHSessionException, NedException {
        print_line_wait_oper0(worker, line, 0);
    }


    /**
     *
     * @param
     * @throws Exceptions
     */
    private void print_line_wait_oper0(NedWorker worker, String line, int timeout)
        throws IOException, SSHSessionException, NedException {

        Pattern[] operPrompt = new Pattern[] {
            Pattern.compile("if your config is large. Confirm\\?[ ]?\\[y/n\\]\\[confirm\\]"),
            Pattern.compile("Do you wish to proceed with this commit anyway\\?[ ]?\\[no\\]"),
            Pattern.compile(PROMPT),
            Pattern.compile(CONFIG_PROMPT)
        };

        for (int retry = writeOobExclusiveRetries; retry >= 0; retry--) {

            // Send line to device and wait for echo
            traceVerbose(worker, "SENDING_OPER: '"+line+"'");
            session.print(line+"\n");
            session.expect(new String[] { Pattern.quote(line) }, worker);

            // Wait for (confirmation prompt)
            NedExpectResult res;
            if (timeout != 0) {
                res = session.expect(operPrompt, false, timeout, worker);
            } else {
                res = session.expect(operPrompt, worker);
            }

            // "if your config is large. Confirm?"
            if (res.getHit() == 0) {
                session.print("y");
                // Note: not echoing 'y'
                res = session.expect(operPrompt, worker);
            }

            // "Do you wish to proceed with this commit anyway?"
            else if (res.getHit() == 1) {

                // Answering 'no':
                if ("no".equals(commitOverrideChanges)) {
                    print_line_exec(worker, "no");
                    String msg0 = print_line_exec(worker, "show configuration history last 3");
                    String msg1 = print_line_exec(worker, "show configuration commit changes last 1");
                    throw new NedException(res.getText()+msg0+msg1);
                }

                // Answering 'yes':
                session.println("yes");
                session.expect(new String[] { "yes" }, worker);
                if (timeout != 0) {
                    res = session.expect(operPrompt, false, timeout, worker);
                } else {
                    res = session.expect(operPrompt, worker);
                }
                // Show the last commit changes in the trace for debugging
                print_line_exec(worker, "show configuration history last 5");
            }

            // Check device reply for error
            String reply = res.getText();
            if (!isCliError(worker, reply)) {
                // Command succeeded, exit loop
                break;
            }

            //
            // Command failed:
            //

            if (!line.trim().startsWith("commit")) {
                // abort | rollback
                if (reply.contains("Please use the command 'show configuration failed rollback")) {
                    // rollback failed
                    String msg = print_line_exec(worker, "show configuration failed rollback");
                    if (!msg.contains("No such configuration")) {
                        throw new NedException("\n'"+line+"' command failed:\n"+msg);
                    }
                }
                throw new NedException(reply);
            }

            //
            // commit [confirmed]
            //

            // Commit retries:
            if (retry > 0 && !reply.contains("Aborted:")) {
                int num = 1 + writeOobExclusiveRetries - retry;

                // Device is rebooting and not ready yet
                if (reply.contains("'try again' condition 'Try the operation again'")) {
                    sleep(worker, 1000 * (long)num, true);
                    continue;
                }

                // Check if we can retry commit:
                if (!reply.contains("Failed to commit ")
                    || reply.contains("Resource temporarily unavaila")
                    || reply.contains("Resource busy")
                    || reply.contains("This configuration has not been verified")
                    || reply.contains("took too long to respond to a startup request")
                    || reply.contains("you may try the 'commit' command again")) {
                    // Retry commit
                    sleep(worker, 1000, true);
                    traceInfo(worker, "Commit retry #"+num);
                    continue;
                }
            }

            // Return detailed error from 'show configuration failed [rollback]'
            if (reply.contains("issue") && reply.contains("show configuration failed")) {
                String msg = print_line_exec(worker, "show configuration failed");
                if (!msg.contains("No such configuration")) {
                    throw new NedException("\n'"+line+"' command failed:\n"+msg);
                }
            }

            // Throw error with output from commit command itself
            throw new NedException(reply);
        }
    }


    /**
     * Enter admin mode
     * @param
     * @throws Exceptions
     */
    protected void enterAdmin(NedWorker worker) throws IOException, SSHSessionException, NedException {

        if (adminLogin) {
            return; // Permanently in admin mode
        }

        traceInfo(worker, "Entering admin mode");
        session.print("admin\n");
        session.expect(new String[] { "admin" }, worker);

        while (true) {
            traceVerbose(worker, "Waiting for reply (admin)");
            NedExpectResult res = session.expect(new String[] {
                    "Invalid input detected.*", // 0
                    "This command is not authorized.*",
                    "Authentication failed.*",
                    "Command authorization failed.*",
                    "Incomplete command.*",

                    "\\A.*[Aa]dmin [Uu]sername:", // 5
                    "\\A.*[Pp]assword:",
                    "cisco connected from.*",
                    "\\A.*[a-zA-Z0-9][^\\# ]+#[ ]*$"
                }, worker);
            switch (res.getHit()) {
            case 5:
                traceVerbose(worker, "Sending admin name");
                if (connAdminName == null) {
                    throw new NedException("Failed to enter admin mode, missing 'connection admin name' ned-setting");
                }
                session.println(connAdminName);
                session.expect(new String[] { Pattern.quote(connAdminName) }, worker);
                break;
            case 6:
                String password = connAdminPassword;
                if (password == null) {
                    throw new NedException("Failed to enter admin mode, "
                                           +"missing 'connection admin password' ned-setting");
                }
                password = maapiDecrypt(connAdminPassword);
                traceVerbose(worker, "Sending admin password");
                session.setTracer(null);
                session.println(password);
                if (trace) {
                    session.setTracer(worker);
                }
                break;
            case 7:
                // Ignore 'cisco connected from '
                break;
            case 8:
                traceVerbose(worker, "Entered admin mode");
                return;
            default:
                throw new NedException("Failed to enter admin mode: "+res.getText());
            }
        }
    }


    /**
     * Exit admin mode
     * @param
     * @throws Exceptions
     */
    protected void exitAdmin(NedWorker worker) throws IOException, SSHSessionException {
        if (adminLogin) {
            return;
        }

        traceVerbose(worker, "Exiting admin mode");
        print_line_exec(worker, "exit");
        traceVerbose(worker, "Exited admin mode");

        // Restore terminal length due to XR bug [IOSXR-922 / RT40398]
        traceInfo(worker, "Restoring 0 terminal length after exiting admin mode due to CSCtk60033 XR bug");
        print_line_exec(worker, "terminal length 0");
    }


    /**
     * Enter config mode
     * @param
     * @throws Exceptions
     */
    protected void enterConfig(NedWorker worker) throws IOException, SSHSessionException, NedException {

        traceVerbose(worker, "Entering config mode");

        // 0 = (admin-config) | (config)
        // 1 = running configuration is inconsistent with persistent configuration
        // 2 = exec mode
        String line = "config " + configMethod;
        for (int retries = writeOobExclusiveRetries; retries >= 0; retries--) {
            session.print(line+"\n");
            NedExpectResult res = session.expect(ENTER_CONFIG_PATTERN, worker);
            if (res.getHit() == 0) {
                break;
            } else if (res.getHit() == 1 || retries == 0) {
                throw new NedException(line+": "+res.getText());
            }
            sleep(worker, 1000, true);
        }
        traceVerbose(worker, "Entered config mode");
        inConfig = true;
    }


    /**
     * Exit config mode
     * @param
     * @throws IOException, SSHSessionException, NedException
     */
    protected void exitConfig(NedWorker worker, String reason) throws IOException, SSHSessionException, NedException {

        traceVerbose(worker, "Exiting config mode ("+reason+")");
        if (!inConfig) {
            throw new NedException("Internal error, tried to exit non-config mode");
        }

        // Move to top config mode
        moveToTopConfig(worker);

        // Exit config mode
        traceVerbose(worker, "Sending end");
        session.print("end\n");
        session.expect(new String[] { "end" }, worker);

        NedExpectResult res = session.expect(EXIT_CONFIG_PATTERN, worker);
        switch (res.getHit()) {
        case 0:  // "Uncommitted changes found, commit them"
            session.print("no\n"); // Send a 'no'
            break;
        case 1:  // "You are exiting after a 'commit confirm'
            session.print("yes\n");
            break;
        case 2:  // Invalid input detected at
            throw new NedException("failed to exit config mode: "+res.getText());
        default: // exec mode
            break;
        }

        inConfig = false;
        traceVerbose(worker, "Exited config mode");
    }


    /**
     * Move to top config mode
     * @param
     * @throws NedException
     */
    private void moveToTopConfig(NedWorker worker) throws NedException {
        try {
            traceVerbose(worker, "Moving to top config mode");

            // (1) Send ENTER to check out current mode
            traceVerbose(worker, "Sending newline");
            session.print("\n");
            NedExpectResult res = session.expect(MOVE_TO_TOP_PATTERN);
            if (res.getHit() == 0) {
                return;
            }

            // (2) Send root to move to top mode
            traceVerbose(worker, "Sending 'root' to exit to top mode");
            session.print("root\n");
            session.expect(new String[] { "root" }, worker);
            // Note: root command prints 'Invalid input detected at' at top-mode
            res = session.expect(MOVE_TO_TOP_PATTERN);
            if (res.getHit() == 0) {
                return;
            }

            // (3) Root did not work, use exit command(s)
            for (int i = 0; i < 30; i++) {
                traceVerbose(worker, "Sending exit");
                session.print("exit\n");
                session.expect(new String[] { "exit" }, worker);
                res = session.expect(MOVE_TO_TOP_PATTERN, worker);
                switch (res.getHit()) {
                case 0: // (admin-config) | (config)
                    return;
                case 1: // Invalid input detected at
                    throw new NedException(res.getText());
                case 2: // config sub-mode
                    break;
                default:
                    throw new NedException("in exec mode");
                }
            }

            // (4) Failed
            throw new NedException("exit not accepted?");

        } catch (Exception e) {
            throw new NedException("failed to move to top mode: "+e.getMessage(), e);
        }
    }


    /**
     * Reorder output config
     * @param
     * @return
     */
    private String reorderOutput(NedWorker worker, String data) throws NedException {

        //
        // Pass 1 - string buffer swapping
        //
        StringBuilder sb = new StringBuilder();
        StringBuilder last = new StringBuilder();
        String[] lines = data.split("\n");
        String toptag = "";
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            String trimmed = lines[n].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String nexttrim = (n + 1 < lines.length) ? lines[n+1].trim() : "";
            boolean swap = false;

            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = trimmed;
            }

            // no group *
            if (line.startsWith("no group ")) {
                // Note: Needed twice for some obscure reason
                last.append(line+"\n");
                last.append(line+"\n");
                continue;
            }

            // router isis * / set-overload-bit
            if (toptag.startsWith("router isis")
                && trimmed.startsWith("set-overload-bit")
                && nexttrim.startsWith("no set-overload-bit")) {
                swap = true;
            }

            // Add line, with optional swap before
            if (swap) {
                traceInfo(worker, "DIFFPATCH: swapped '"+trimmed+"' and '"+nexttrim+"'");
                line = lines[n+1];
                lines[n+1] = lines[n];
            }

            // Append line
            sb.append(line+"\n");
        }
        data = sb.toString() + last.toString();

        //
        // Pass 2 - call nedDiff to reorder
        //
        data = nedDiff.reorder(worker, data);

        return "\n" + data;
    }


    /**
     * Apply patch for CSCtk60033 XR bug (must delete class-maps in subsequent commit)
     * @param
     * @return
     */
    private String outputPatchCSCtk60033(NedWorker worker, String data, int fromTh) {
        StringBuilder sb = new StringBuilder("\n");
        String[] lines = data.split("\n");
        boolean patched = false;
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            if (line.isEmpty()) {
                continue;
            }

            // no class-map */
            if (line.startsWith("no class-map ")) {

                // Create 'policy-map * / no class' line (note: may include 'type xxx')
                String classdel = line.replace("match-any ", "").replace("match-all ", "");
                classdel = classdel.replace("no class-map ", " no class ");

                // If deleted class-map also is deleted in policy-map, delete it in 2nd commit
                if (data.contains("\n"+classdel+"\n")) {

                    // Delay the delete of the class-map
                    delayedCommit.append(line+"\n");
                    patched = true;

                    // Delete of policy-map must be in the same commit as delete of class-map (if referenced in class)
                    String toptag = "";
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].isEmpty()) {
                            continue;
                        }
                        if (isTopExit(lines[i])) {
                            toptag = "";
                        } else if (Character.isLetter(lines[i].charAt(0))) {
                            toptag = lines[i];
                        }
                        if (toptag.startsWith("policy-map ") && lines[i].equals(classdel)) {
                            for (int p = n + 1; p < lines.length; p++) {
                                if (lines[p].equals("no "+toptag.trim())) {
                                    delayedCommit.append(lines[p]+"\n");
                                    lines[p] = "";
                                    i = lines.length;
                                    break;
                                }
                            }
                        }
                    }
                    continue;
                }
            }

            // Default, add line to first commit
            sb.append(line+"\n");
        }

        if (patched) {
            traceInfo(worker, "transformed => applied CSCtk60033 PATCH, deleting class-maps in separate commit");

            // Also add all policy-map deletes in delayed commit
            if (autoCSCtk60033Patch2) {
                traceInfo(worker, "transformed => applied CSCtk60033-2 PATCH, deleting policy-maps in separate commit");
                lines = sb.toString().split("\n");
                sb = new StringBuilder("\n");
                for (int n = 0; n < lines.length; n++) {
                    String line = lines[n];
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (line.startsWith("no policy-map ")) {
                        delayedCommit.append(line+"\n");
                    } else {
                        sb.append(line+"\n");
                    }
                }
            }

            // Finally, we need to default auto-deleted policy-map's to avoid commit failure in 1st commit
            if (isDevice()) {
                lines = delayedCommit.toString().split("\n");
                for (int n = 0; n < lines.length; n++) {
                    if (lines[n].startsWith("no policy-map")
                        && policyMapIsEmpty(worker, lines[n].substring(3), fromTh, sb)) {
                        traceVerbose(worker, "transformed => applied CSCtk60033 PATCH, injecting default "
                                     +lines[n].substring(3));
                        sb.append("default "+lines[n].substring(3)+"\n");
                    }
                }
            }

            traceInfo(worker, "\n"+delayedCommit.toString());
        }

        return sb.toString();
    }


    /**
     * Check if policy-map is empty in CDB for CSCtk60033 patch
     * @param
     * @return
     */
    private boolean policyMapIsEmpty(NedWorker worker, String polmap, int th, StringBuilder sb) {

        // Set CDB config path
        String path;
        String name;
        if (polmap.contains("policy-map type control ")
            && (name = getMatch(polmap, "policy-map type control (.+)")) != null) {
            path = confPath + "policy-map-event-control/policy-map{"+name+"}";
        } else if (polmap.contains("policy-map type ")
                   && (name = getMatch(polmap, "policy-map type \\S+ (.+)")) != null) {
            path = confPath + "policy-map{"+name+"}";
        } else {
            path = confPath + polmap.trim().replaceFirst(" ", "{") + "}";
        }
        traceVerbose(worker, "POL-MAP: '"+polmap+"' path = "+path);

        // Read policy-map config
        String config = maapiGetConfig(worker, th, path);
        if (config == null) {
            return false;
        }
        String[] from = config.split("\n");
        String[] to = sb.toString().split("\n");

        // Loop through changes and remove deleted config in policy-map
        for (int t = 0; t < to.length; t++) {
            if (!to[t].startsWith(polmap)) {
                continue;
            }
            // policy-map *
            for (t = t + 1; t < to.length; t++) {
                if (to[t].equals(" end-policy-map")) {
                    break;
                }
                if (to[t].startsWith(" no class ")) {
                    String cm = to[t].replaceFirst(" no", "");
                    for (int f = 1; f < from.length; f++) {
                        if (from[f].startsWith(cm)) {
                            from[f] = "";
                            for (f = f + 1; f < from.length; f++) {
                                if (from[f].equals(" !")) {
                                    from[f] = "";
                                    f = from.length; // break out of outer loop
                                    break;
                                }
                                from[f] = "";
                            }
                        }
                    }
                }
            }
        }

        // Trim description and empty class-default
        for (int f = 1; f < from.length - 1; f++) {
            if (from[f].isEmpty()) {
                continue;
            } else if (from[f].startsWith(" description ")) {
                from[f] = "";
            } else if (from[f].equals(" class default") && from[f+1].equals(" !")) {
                from[f] = "";
                from[f+1] = "";
            }
        }
        config = linesToString(from);
        traceVerbose(worker, "FROM REMAINING="+config);

        // Check if empty, looks like this:
        // policy-map <name>
        //  class class-default
        //  !
        //  end-policy-map
        // !
        from = config.trim().split("\n");
        if (from.length <= 5) {
            traceVerbose(worker, polmap+" :: is empty");
            return true;
        }

        traceVerbose(worker, polmap+" :: is non-empty:\n"+config);
        return false;
    }


    /**
     * Apply patch for AAA tacacs server bug (must delete aaa group server tacacs+ last)
     * @param
     * @return
     */
    private String outputPatchAAATacacs(NedWorker worker, String data) {
        StringBuilder sb = new StringBuilder("\n");
        String[] lines = data.split("\n");
        boolean patched = false;
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            if (line.startsWith("no tacacs-server host ")
                || line.startsWith("no vrf ")
                || line.startsWith("no aaa group server tacacs")) {
                delayedCommit.append(line+"\n");
                patched = true;
                continue;
            }
            sb.append(line+"\n");
        }
        if (patched) {
            traceInfo(worker, "transformed => applied aaa-tacacs PATCH, deleting aaa group server tacacs++ last");
        }
        return sb.toString();
    }


    /**
     * Edit list to string builder
     * @param
     * @return
     * @throws NedException
     */
    private String editListToStringBuilder(NedWorker worker, String path, int th, StringBuilder sb)
        throws NedException {

        String name = path.replace(confPath, "");
        traceVerbose(worker, "EDIT: path = "+name);

        try {
            // Verify list exists
            if (!maapi.exists(th, path)) {
                traceVerbose(worker, "EDIT: '"+name+"' does not exist");
                return "";
            }

            // Read number of instances
            int num = maapi.getNumberOfInstances(th, path + "/line");
            traceVerbose(worker, "'"+name+"' getNumberOfInstances() = "+num);
            if (num <= 0) {
                traceInfo(worker, "EDIT: '"+name+"' is empty");
                return "";
            }

            // Bulk-read all lines
            MaapiCursor cr = maapi.newCursor(th, path + "/line");
            List<ConfObject[]> list = maapi.getObjects(cr, 2, num);

            // Add all the lines
            traceVerbose(worker, "EDIT: '"+name+"' = "+list.size()+" line(s)");
            StringBuilder lineno = new StringBuilder();
            for (int n = 0; n < list.size(); n++) {
                ConfObject[] objs = list.get(n);
                lineno.append(" " + objs[0].toString().trim());
                sb.append(objs[1].toString().trim()+"\n");
            }

            // Return line number 'list'
            return lineno.toString();

        } catch (Exception e) {
            throw new NedException("EDIT: editListToStringBuilder ERROR : "+e.getMessage(), e);
        }
    }


    /**
     * Set list to string builder
     * @param
     * @return
     * @throws NedException
     */
    private int setListToStringBuilder(NedWorker worker, String path, int th, StringBuilder sb)
        throws NedException {

        String name = path.replace(confPath, "");
        traceVerbose(worker, "SET: path = "+name);

        try {
            // Verify list exists
            if (!maapi.exists(th, path)) {
                traceVerbose(worker, "SET: '"+name+"' does not exist");
                return 0;
            }

            // Read number of instances
            int num = maapi.getNumberOfInstances(th, path + "/set");
            traceVerbose(worker, "'"+name+"' getNumberOfInstances() = "+num);
            if (num <= 0) {
                traceInfo(worker, "SET: '"+name+"' is empty");
                return 0;
            }

            // Bulk-read all lines
            MaapiCursor cr = maapi.newCursor(th, path + "/set");
            List<ConfObject[]> list = maapi.getObjects(cr, 2, num);

            // Add all the lines
            traceVerbose(worker, "SET: '"+name+"' = "+list.size()+" line(s)");
            int s = 0;
            for (int n = 0; n < list.size(); n++) {
                ConfObject[] objs = list.get(n);
                String value = objs[0].toString().trim();
                if (value.contains("ios-regex \"")) {
                    value = value.replaceAll("ios-regex \\\"(.*)\\\"", "ios-regex $1");
                }
                if (s + 1 < list.size()) {
                    sb.append(" "+value+",\n");
                } else {
                    sb.append(" "+value+"\n");
                }
                s++;
            }
            return s;

        } catch (Exception e) {
            throw new NedException("SET: setListToStringBuilder ERROR : "+e.getMessage(), e);
        }
    }


    /**
     * Inject data
     * @param
     * @return
     * @throws NedException
     */
    private String injectOutputData(NedWorker worker, String data, String[] entry, String dir) throws NedException {
        Pattern pattern = Pattern.compile(entry[1]+"(?:[\r])?[\n]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(data);
        int offset = 0;
        String[] groups = null;
        String insert;

        // before-first
        if (entry[3].equals("before-first")) {
            if (matcher.find()) {
                insert = fillInjectLine(worker, entry[2] + "\n", entry[3], fillGroups(matcher), dir);
                data = data.substring(0, matcher.start(0))
                    + insert
                    + data.substring(matcher.start(0));
            }
        }

        // before-each
        else if (entry[3].equals("before-each")) {
            while (matcher.find()) {
                insert = fillInjectLine(worker, entry[2] + "\n", entry[3], fillGroups(matcher), dir);
                data = data.substring(0, matcher.start(0) + offset)
                    + insert
                    + data.substring(matcher.start(0) + offset);
                offset = offset + insert.length();
            }
        }

        // after-last
        else if (entry[3].equals("after-last")) {
            int end = -1;
            while (matcher.find()) {
                end = matcher.end(0);
                groups = fillGroups(matcher);
            }
            if (end != -1) {
                insert = fillInjectLine(worker, entry[2] + "\n", entry[3], groups, dir);
                data = data.substring(0, end)
                    + insert + "\n"
                    + data.substring(end);
            }
        }

        // after-each
        else if (entry[3].equals("after-each")) {
            while (matcher.find()) {
                insert = fillInjectLine(worker, entry[2] + "\n", entry[3], fillGroups(matcher), dir) + "\n";
                data = data.substring(0, matcher.end(0) + offset)
                    + insert
                    + data.substring(matcher.end(0) + offset);
                offset = offset + insert.length();
            }
        }
        return data;
    }


    /**
     * Upload config to device using SFTP
     * @param
     * @throws Exceptions
     */
    private void sftpUploadConfig(NedWorker worker, String data)
        throws NedException, IOException, SSHSessionException, NedException {
        final long start = tick(0);

        logInfo(worker, "BEGIN sending (SFTP)");

        if (remoteConnection != null) {
            throw new NedException("SFTP apply ERROR: Using sftp to apply config is not supported via proxy");
        }

        if (writeDeviceFile == null || writeDeviceFile.isEmpty()) {
            throw new NedException("SFTP apply ERROR: no file name configured");
        }

        // Delete previous commit file (ignore errors)
        print_line_exec(worker, "do delete /noprompt "+writeDeviceFile);

        // Modify data
        data = stripLineAll(worker, data, META_DATA, "=>", true);
        data = stripLineAll(worker, data, "!", "=>", false);

        traceVerbose(worker, "SFTP_APPLY=\n"+stringQuote(data));

        // Transfer file
        SFTPv3Client sftp = null;
        Connection sftpConn = null;
        long offset = 0;
        try {
            int i = 0;
            int max = SFTP_MAX_SIZE;
            sftpConn = sftpConnect(worker);
            sftp = new SFTPv3Client(sftpConn);
            byte[] buffer = data.getBytes("UTF-8");
            SFTPv3FileHandle h = sftp.createFile(writeDeviceFile);
            traceInfo(worker, "SFTP transfering file: "+writeDeviceFile+" ("+data.length()+" bytes)");
            do {
                int writeSize = buffer.length - i < max ? Math.min(max, buffer.length - i) : max;
                sftp.write(h, offset, buffer, i, writeSize);
                i += writeSize;
                offset = i;
            } while (offset < buffer.length);
            sftp.closeFile(h);
        } catch (Exception e) {
            throw new NedException("SFTP apply ERROR : " + e.getMessage());
        } finally {
            if (sftp != null) {
                sftp.close();
            }
            if (sftpConn != null) {
                sftpConn.close();
            }
        }
        traceVerbose(worker, "SFTP transfer finished ("+offset+" bytes)");

        // Load config to candidate
        traceVerbose(worker, "Loading config to candidate");
        String res = print_line_exec(worker, "load "+writeDeviceFile);

        // Check for errors
        if (res.contains("Couldn't open file")) {
            throw new NedException("SFTP load: "+res);
        }
        if (res.contains("Syntax/Authorization errors in one or more commands")) {
            res = print_line_exec(worker, "show configuration failed load");
            throw new NedException("SFTP apply: "+res);
        }

        logInfo(worker, "DONE sending (SFTP) "+tickToString(start));
    }


    /*
     **************************************************************************
     * commit
     **************************************************************************
     */

    /**
     * Commit config
     * @param
     * @throws Exception
     */
    @Override
    public void commit(NedWorker worker, int timeout) throws Exception {
        final long start = tick(0);
        if (trace) {
            session.setTracer(worker);
        }
        logInfo(worker, "BEGIN COMMIT");

        // Commit
        doCommit(worker, true);

        // Commit response
        logInfo(worker, "DONE COMMIT "+tickToString(start));
        worker.commitResponse();
    }


    /**
     * Do the commit
     * @param
     * @throws Exception
     */
    private void doCommit(NedWorker worker, boolean allowTrial) throws Exception {
        boolean pendingTrial = false;
        long start = nedReportProgress(worker, "committing config...", 0);
        try {

            // Must be in config mode or nothing to commit
            if (!inConfig) {
                nedReportProgress(worker, "committing config error (exec mode)", start);
                return;
            }

            //
            // Commit
            //
            traceInfo(worker, "Committing ("+commitMethod+") [num-commit "+numCommit+" "
                      +numAdminCommit+"a delayed="+delayedCommit.length()+"]");

            // Optional trial commit
            if (allowTrial && "confirmed".equals(commitMethod)) {
                String cmd = commitCommand + " confirmed " + commitConfirmedTimeout;
                if (cmd.contains(" show-error")) {
                    // show-error must be last in line
                    cmd = cmd.replace(" show-error", "") + " show-error";
                }
                print_line_wait_oper(worker, cmd, writeTimeout);
                pendingTrial = true;

                // Optional delay before confirming commit
                if (commitConfirmedDelay > 0) {
                    sleep(worker, commitConfirmedDelay, true);
                }
            }

            if ("before commit".equals(failphase)) {
                failphase = "";
                throw new NedException("COMMIT :: simulated Exception before commit");
            }

            // (confirm) commit
            print_line_wait_oper(worker, commitCommand, writeTimeout);
            pendingTrial = false;
            numCommit++;

            if ("after commit".equals(failphase)) {
                failphase = "";
                throw new NedException("COMMIT :: simulated Exception after commit");
            }

            // Send and commit delayedCommit
            if (delayedCommit.length() > 0) {
                String data = delayedCommit.toString();
                delayedCommit = new StringBuilder();

                traceInfo(worker, "Sending and committing "+data.length()+" bytes of delayed config:\n"+data);
                doSendConfig(worker, data.split("\n"));

                if ("before delayed".equals(failphase)) {
                    failphase = "";
                    throw new NedException("COMMIT :: simulated Exception before delayed commit");
                }

                print_line_wait_oper(worker, commitCommand, writeTimeout);
                numCommit++;

                if ("after delayed".equals(failphase)) {
                    failphase = "";
                    throw new NedException("COMMIT :: simulated Exception after delayed commit");
                }
            }

            // Exit config mode
            exitConfig(worker, "commit");

            // Cache secrets
            if (secrets.needUpdate()) {
                lastGetConfig = getConfig(worker, false);
                secrets.cache(worker, lastGetConfig);
            }

            // Done
            nedReportProgress(worker, "committing config ok", start);

        } catch (Exception e) {
            nedReportProgress(worker, "committing config error", start);
            if (!session.serverSideClosed() && inConfig) {
                inConfig = false;
                session.print("abort\n");
                if (pendingTrial) {
                    abortPendingCommit(worker);
                }
            }
            throw e;
        }
    }


    /**
     * Abort pending commit
     * @param
     */
    private void abortPendingCommit(NedWorker worker) {
        traceInfo(worker, "Abort triggered rollback of trial commit");
        setWriteTimeout(worker);
        try {
            traceInfo(worker, "Waiting for echo: 'abort'");
            session.expect("abort", worker);

            String echo = "Rolling back unconfirmed trial commit immediately";
            if (isNetsim()) {
                echo = "configuration rolled back";
            }
            Pattern[] prompt = new Pattern[] {
                Pattern.compile(echo),
                Pattern.compile(PROMPT)
            };
            while (true) {
                traceInfo(worker, "Waiting for device prompt or +'"+echo+"'");
                NedExpectResult res = session.expect(prompt, true, writeTimeout, worker);
                if (res.getHit() == 0) {
                    continue;
                }
                break;
            }
        } catch (Exception e) {
            logError(worker, "pending commit abort ERROR", e);
        }
    }


    /*
**************************************************************************
* persist
**************************************************************************
*/

    /**
     * Persist (save) config on device
     * @param
     * @throws Exception
     */
    @Override
    public void persist(NedWorker worker) throws Exception {
        // No-op, XR saves config in commit
        worker.persistResponse();
    }


    /*
**************************************************************************
* abort
**************************************************************************
*/

    /**
     * apply failed, rollback config
     * @param
     * @throws Exception
     */
    public void abort(NedWorker worker, String data) throws Exception {
        final long start = tick(0);
        if (trace) {
            session.setTracer(worker);
        }
        logInfo(worker, "BEGIN ABORT ("+commitMethod+") [in-config="
                +inConfig+"] [num-commit "+numCommit+" "+numAdminCommit+"a]");

        doRollback(worker);

        logInfo(worker, "DONE ABORT (rollbacked "+(numCommit+numAdminCommit)+" commit(s)) "+tickToString(start));
        worker.abortResponse();
    }


    /**
     * Rollback
     * @param
     * @throws Exception
     */
    private void doRollback(NedWorker worker) throws Exception {
        long start = nedReportProgress(worker, "rollbacking config...", 0);
        try {
            // If still in config mode, abort to drop the current commit
            if (inConfig) {
                traceInfo(worker, "Aborted uncommitted config");
                print_line_wait_oper(worker, "abort");
                inConfig = false;
            }

            // Rollback admin config commited in this session's prepare phase
            if (numAdminCommit > 0) {
                try {
                    traceInfo(worker, "Rollbacking last "+numAdminCommit+" admin commit(s)");
                    enterAdmin(worker);
                    String res = print_line_exec(worker, "rollback configuration last "+numAdminCommit, writeTimeout);
                    if (isExecError(res)) {
                        enterConfig(worker);
                        print_line_wait_oper(worker, "rollback configuration", writeTimeout);
                        exitConfig(worker, "rollback admin");
                    }
                    exitAdmin(worker);
                } finally {
                    numAdminCommit = 0;
                }
            }

            // If we have commited in this session, rollback
            if (numCommit > 0) {
                try {
                    traceInfo(worker, "Rollbacking last "+numCommit+" commit(s)");
                    if (isNetsim()) {
                        enterConfig(worker);
                        print_line_wait_oper(worker, "rollback configuration "+(numCommit-1), writeTimeout);
                        doCommit(worker, false);
                    } else {
                        print_line_wait_oper(worker, "rollback configuration last "+numCommit, writeTimeout);
                    }
                } finally {
                    numCommit = 0;
                }
            }

            // Done
            nedReportProgress(worker, "rollbacking config ok", start);

        } catch (Exception e) {
            nedReportProgress(worker, "rollbacking config error", start);
            throw e;
        }
    }


    /*
     **************************************************************************
     * revert
     **************************************************************************
     */

    /**
     * Revert
     * @param
     * @throws Exception
     */
    @Override
    public void revert(NedWorker worker, String data) throws Exception {
        final long start = tick(0);

        if (trace) {
            session.setTracer(worker);
            // NSO does not trace 'data' in REVERT like it does in PREPARE
            traceInfo(worker, "\n"+data);
        }
        logInfo(worker, "BEGIN REVERT ("+commitMethod+") [in-config="
                +inConfig+"] [num-commit "+numCommit+" "+numAdminCommit+"a]");

        if ("rollback".equals(revertMethod)
            && (inConfig || numCommit > 0 || numAdminCommit > 0)) {
            doRollback(worker);
        } else {
            doApplyConfig(worker, data);
            doCommit(worker, false);
        }

        logInfo(worker, "DONE REVERT (rollbacked "+(numCommit+numAdminCommit)+" commit(s)) "+tickToString(start));
        worker.revertResponse();
    }


    /*
     **************************************************************************
     * command
     **************************************************************************
     */

    /**
     * Run command(s) on device.
     * From ncs_cli: devices device <dev> live-status exec any "command"
     * @param
     * @throws Exception
     */
    @Override
    public void command(NedWorker worker, String cmdName, ConfXMLParam[] p) throws Exception {
        if (trace) {
            session.setTracer(worker);
        }

        // Prepare command
        String cmd = nedCommand.prepare(worker, cmdName, p);

        // internal - show outformat raw
        String reply;
        if ("show outformat raw".equals(cmd)) {
            reply = "\nNext dry-run will show raw (unmodified) format.\n";
            showRaw = true;
        }

        // internal - fail <phase>
        else if (cmd.startsWith("fail ")) {
            failphase = cmd.substring(5).trim();
            reply = "\nfailphase set to: '"+failphase+"'\n";
        }

        // internal - apply-delay <seconds>
        else if (cmd.startsWith("apply-delay ")) {
            applyDelay = Integer.parseInt(cmd.substring(12).trim());
            reply = "\napply-delay set to: '"+applyDelay+"' seconds\n";
        }

        // internal - show ned-settings
        else if ("show ned-settings".equals(cmd)) {
            reply = "\n"+nedSettings.dumpAll();
        }

        // internal - secrets resync
        else if ("secrets resync".equals(cmd)) {
            secrets.setResync(true);
            getConfig(worker, false);
            secrets.setResync(false);
            reply = "\nRe-synced all cached secrets.\n";
        }

        // internal - secrets delete
        else if ("secrets delete".equals(cmd)) {
            try {
                secrets.delete(worker);
            } catch (Exception ignore) {
                // Ignore Exception
            }
            reply = "\nDeleted cached secrets.\n";
        }

        // internal - sync-from-file <FILE>
        else if (isNetsim() && cmd.startsWith("sync-from-file ")) {
            syncFile = cmd.trim().substring(15).trim();
            reply = "\nNext sync-from will use file = " + syncFile + "\n";
        }

        // Device command
        else {
            nedCommand.execute(worker, cmd);
            return;
        }

        // Internal command reply
        logInfo(worker, "COMMAND - internal: "+stringQuote(cmd));
        traceInfo(worker, reply);
        worker.commandResponse(new ConfXMLParam[]
            { new ConfXMLParamValue("cisco-ios-xr-stats", "result", new ConfBuf(reply))});
    }


    /**
     * Exit live-status action prompting by sending CTRL-C until normal prompt.
     * @param
     * @throws Exception
     */
    protected boolean exitPrompting(NedWorker worker) throws IOException, SSHSessionException, NedException {

        traceInfo(worker, "Exiting from command prompt question");

        Pattern[] cmdPrompt = new Pattern[] {
            // Prompt patterns:
            Pattern.compile(PROMPT),
            Pattern.compile("\\A\\S*#"),
            Pattern.compile("The operation can no longer be aborted"),
            Pattern.compile("Enter 'abort' followed by RETURN to abort the operation"),

            // Question patterns:
            Pattern.compile(":\\s*$"),
            Pattern.compile("\\]\\s*$")
        };

        String lastText = "xyzXYZxyzXYZ";
        for (int n = 0; n < 100; n++) {
            traceVerbose(worker, "Sending CTRL-C");
            session.print("\u0003");
            traceVerbose(worker, "Waiting for non-question");
            NedExpectResult res = session.expect(cmdPrompt, true, readTimeout, worker);
            if (res.getHit() <= 1) {
                return true;
            } else if (res.getHit() == 2) {
                session.println("sync"); // confirm (sync/async)?
                return false;
            } else if (res.getHit() == 3) {
                session.println("abort"); // (admin) install
                return false;
            }
            if (lastText.equals(res.getText())) {
                traceVerbose(worker, "Aborting CTRL-C due to repetitive reply from device");
                session.println("");
                return false;
            }
            lastText = res.getText();
        }

        throw new NedException("exitPrompting :: Internal ERROR: failed to exit, report with raw trace");
    }


    /*
**************************************************************************
* cleanup
**************************************************************************
*/

    /**
     * Cleanup NED, called by close
     * @throws Exception
     */
    @Override
    protected void cleanup() throws Exception {
        logInfo(null, "BEGIN CLEANUP");
        if (session != null) {
            // Logout from telnet terminal server
            if (adminLogin && proto.equals("telnet") && "serial".equals(remoteConnection)) {
                try {
                    logInfo(null, "Sending exit command");
                    session.println("exit");
                    session.expect("exit");
                    session.expect("[Uu]sername[:][ ]?");
                    session.print("\u00ff\u00f4");
                    session.close();
                    sleepMs(3000);
                } catch (Exception ignore) {
                    // Ignore Exception
                }
            }

            // Drop config mode
            else if (inConfig && !session.serverSideClosed()) {
                session.print("abort\n");
                inConfig = false;
            }
        }
        logInfo(null, "DONE CLEANUP");
    }


    /*
     **************************************************************************
     * keepAlive
     **************************************************************************
     */

    /**
     * This method is invoked periodically to keep an connection
     * alive. If false is returned the connection will be closed using the
     * close() method invocation.
     *
     * @param worker
     */
    public boolean keepAlive(NedWorker worker) {
        final long start = tick(0);
        if (trace) {
            session.setTracer(worker);
        }
        logInfo(worker, "BEGIN KEEP-ALIVE");
        boolean alive = true;
        try {
            if (session.serverSideClosed()) {
                reconnectDevice(worker);
            } else {
                traceVerbose(worker, "Sending newline");
                session.println("");
                traceVerbose(worker, "Waiting for prompt");
                session.expect(new String[] { PROMPT, CONFIG_PROMPT }, worker);
            }
        } catch (Exception e) {
            alive = false;
            logError(worker, "KEEP_ALIVE ERROR: "+e.getMessage(), e);
        }
        logInfo(worker, "DONE KEEP-ALIVE = "+alive+" "+tickToString(start));
        return alive;
    }


    /**
     * Reconnect to device using connector
     * @throws Exception
     */
    protected void reconnectDevice(NedWorker worker) throws NedException {
        traceInfo(worker, "Server side closed, reconnecting");
        try {
            connectorReconnectDevice(worker);
        } catch (Exception e) {
            throw new NedException("Failed to reconnect :: "+e.getMessage(), e);
        }
    }


    /*
     **************************************************************************
     * NedSecrets
     **************************************************************************
     */

    /**
     * Used by NedSecrets to check whether a secret is cleartext or encrypted.
     * Method must be implemented by all NED's which use NedSecrets.
     * @param secret - The secret
     * @return True if secret is cleartext, else false
     */
    @Override
    public boolean isClearText(String secret) {
        String trimmed = secret.trim();

        // Maapi.encrypt patch - reverse logic to avoid diff on NETSIM
        if (isNetsim()) {
            if (secret.startsWith("clear $8$")) {
                return true; // Return true to allow NedSecrets to cache it
            }
            if (secret.startsWith("clear ")) {
                return false; // Return false to allow NedSecrets to replace it
            }
        }

        // encrypted
        if (secret.matches("[0-9a-f]{2}(:([0-9a-f]){2})+")) {
            return false;   // aa:11 .. :22:bb
        }
        if (trimmed.contains(" encrypted")) {
            return false;  // XXX encrypted
        }
        if (secret.startsWith("password ")) {
            return false;  // password XXX
        }
        if (secret.startsWith("password6 ")) {
            return false;  // password6 XXX
        }
        if (secret.startsWith("$1$")) {
            return false;  // $1$..
        }
        if (trimmed.endsWith(" 7")) {
            return false;   // XXX 7
        }

        // cleartext
        if (trimmed.indexOf(' ') < 0) {
            return true;   // XXX
        }
        if (trimmed.charAt(0) == '0') {
            return true;   // 0 XXX
        }
        if (secret.startsWith("clear ")) {
            return true;  // clear XXX
        }
        if (trimmed.endsWith(" 0")) {
            return true;   // XXX 0
        }

        // Default to encrypted
        return false;      // 5 XXX, 6 XXX, 7 XXX
    }


    /*
     **************************************************************************
     * Common utility methods
     **************************************************************************
     */

    /**
     * Report progress with Verbosity NORMAL
     */
    private long nedReportProgress(NedWorker worker, String msg, long lastTime) {
        return reportProgress(worker, Verbosity.NORMAL, msg, lastTime);
    }

    /**
     * Set user session
     * @throws Exception
     */
    private void setUserSession(NedWorker worker) throws Exception {
        try {
            int usid = maapi.getMyUserSession();
            traceInfo(worker, "Maapi user session is "+usid);
        } catch (Exception ignore) {
            traceInfo(worker, "Maapi user session set to 1");
            maapi.setUserSession(1);
        }
    }


    /**
     * Trace debug message (NOTE: same as traceVerbose)
     * @param
     *
     */
    public void traceDebug(NedWorker worker, String info) {
        if (devTraceLevel >= TRACE_DEBUG) {
            traceInfo(worker, info);
        }
    }


    /**
     * Trace debug2 message
     * @param
     *
     */
    public void traceDebug2(NedWorker worker, String info) {
        if (devTraceLevel >= TRACE_DEBUG2) {
            traceInfo(worker, info);
        }
    }


    /**
     * Trace debug3 message
     * @param
     *
     */
    public void traceDebug3(NedWorker worker, String info) {
        if (devTraceLevel >= TRACE_DEBUG3) {
            traceInfo(worker, info);
        }
    }


    /**
     * Attach to Maapi
     * @param
     *
     */
    private void maapiAttach(NedWorker worker, int fromTh, int toTh) throws NedException {
        try {
            maapi.attach(fromTh, 0);
            maapi.attach(toTh, 0);
            traceDebug2(worker, "Maapi.Attached: from="+fromTh+" to="+toTh);
        } catch (Exception e) {
            throw new NedException("Internal ERROR: maapiAttach()", e);
        }
    }


    /**
     * Detach from Maapi
     * @param
     *
     */
    private void maapiDetach(NedWorker worker, int fromTh, int toTh) throws NedException {
        try {
            if (fromTh > 0) {
                maapi.detach(fromTh);
            }
            if (toTh > 0) {
                maapi.detach(toTh);
            }
            traceDebug2(worker, "Maapi.Detached: from="+fromTh+" to="+toTh);
        } catch (Exception e) {
            throw new NedException("Internal ERROR: maapiDetach(): "+e.getMessage(), e);
        }
    }


    /**
     * Maapi decrypt a password
     * @param
     * @return
     */
    private String maapiDecrypt(String secret) {
        try {
            if (this.mCrypto == null) {
                this.mCrypto = new MaapiCrypto(maapi);
            }
            return mCrypto.decrypt(secret);
        } catch (MaapiException e) {
            // Ignore Exception
        }
        return secret;
    }


    /**
     *
     * @param
     * @return
     * @throws NedException
     */
    protected boolean maapiExists(NedWorker worker, int th, String path) throws NedException {
        try {
            if (maapi.exists(th, path)) {
                traceVerbose(worker, "maapiExists("+path+") = true");
                return true;
            }
        } catch (Exception e) {
            throw new NedException("maapiExists("+path+") ERROR : " + e.getMessage());
        }
        traceVerbose(worker, "maapiExists("+path+") = false");
        return false;
    }


    /**
     * Read config from CDB and output as CLI format
     * @param
     * @return
     */
    protected String maapiGetConfig(NedWorker worker, int th, String path) {

        StringBuilder sb = new StringBuilder();
        try {
            if (!maapi.exists(th, path)) {
                return null;
            }

            MaapiInputStream in = maapi.saveConfig(th,
                                                   EnumSet.of(MaapiConfigFlag.MAAPI_CONFIG_C_IOS,
                                                              MaapiConfigFlag.CISCO_IOS_FORMAT),
                                                   path);
            if (in == null) {
                traceInfo(worker, "maapiGetConfig ERROR: failed to get "+ path);
                return null;
            }

            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer, 0, buffer.length)) > 0) {
                sb.append(new String(buffer).substring(0, bytesRead));
                if (bytesRead < buffer.length) {
                    break;
                }
            }
        } catch (Exception e) {
            traceInfo(worker, "maapiGetConfig ERROR: read exception "+ e.getMessage());
            return null;
        }

        String[] lines = sb.toString().split("\n");
        if (lines.length < 5) {
            return null; // output does not contain 'devices device <device-id>\n config\n' + ' !\n!\n'
        }

        sb = new StringBuilder();
        for (int n = 2; n < lines.length - 2; n++) {
            String line = lines[n].substring(2);
            if (line.trim().startsWith(PFX) || line.trim().startsWith("no "+PFX)) {
                line = line.replaceFirst(PFX, "");
            }
            sb.append(line+"\n");
        }

        String data = sb.toString();
        traceVerbose(worker, "MAAPI_GET_AFTER=\n"+data);
        return data;
    }


    /**
     * Bulk read an entire list using maapi.getObjects()
     * @param
     * @return ArrayList with String[] containing all entries and all leaves for the entire list
     *         Note: Unset leaves are indicated by the null String, e.g. [i] = null
     *         Note2: For 'type empty' the name of the leaf is returned (excluding prefix)
     *         Warning: The list may not contain embedded lists.
     * @throws NedException
     */
    protected ArrayList<String[]> maapiGetObjects(NedWorker worker, int th, String path, int numLeaves)
        throws NedException {
        final long start = tick(0);
        try {
            ArrayList<String[]> list = new ArrayList<>();

            // Verify list exists
            if (!maapi.exists(th, path)) {
                traceVerbose(worker, "'" + path + "' not found");
                return list;
            }

            // Read number of instances
            int num = maapi.getNumberOfInstances(th, path);
            if (num <= 0) {
                traceInfo(worker, "'" + path + "' is empty (" + num + ")");
                return list;
            }
            traceVerbose(worker, "'" + path + "' getNumberOfInstances() = " + num);

            // Bulk-read all rules
            MaapiCursor cr = maapi.newCursor(th, path);
            List<ConfObject[]> objList = maapi.getObjects(cr, numLeaves, num);

            // Add all the entries in an ArrayList
            for (int n = 0; n < objList.size(); n++) {
                ConfObject[] objs = objList.get(n);
                String[] entry = new String[numLeaves];
                // Add all the leaves in a String[] array
                for (int l = 0; l < numLeaves; l++) {
                    entry[l] = objs[l].toString();
                    if ("J_NOEXISTS".equals(entry[l])) {
                        entry[l] = null;
                    } else if (entry[l].startsWith(PFX)) {
                        entry[l] = entry[l].replaceFirst(PFX, "");
                    }
                    traceVerbose(worker, "LIST["+n+","+l+"] = "+entry[l]);
                }
                list.add(entry);
            }

            traceInfo(worker, "'" + path + "' read " + numLeaves + " leaves in "
                      +objList.size()+" entries " + String.format("[%d ms]", tick(start)));
            return list;

        } catch (Exception e) {
            throw new NedException("Internal ERROR in maapiGetObjects(): " + e.getMessage(), e);
        }
    }


    /**
     * Read an entire list entry using maapi.getObject()
     * @param
     * @return String[] containing all the leaves for this list entry
     *         Note: Unset leaves are indicated by the null String, e.g. [i] = null
     *         Note2: For 'type empty' the name of the leaf is returned (excluding prefix)
     *         Warning: The list entry may not contain embedded lists.
     * @throws NedException
     */
    protected String[] maapiGetObject(NedWorker worker, int th, String path, int numLeaves)
        throws NedException {
        final long start = tick(0);
        try {
            // Verify list exists
            if (!maapi.exists(th, path)) {
                traceVerbose(worker, "'" + path + "' not found");
                return new String[0];
            }

            // Read the list entry and add all the leaves in a String[] array
            ConfObject[] obj = maapi.getObject(th, path);
            String[] entry = new String[numLeaves];
            for (int l = 0; l < numLeaves; l++) {
                entry[l] = obj[l].toString();
                if ("J_NOEXISTS".equals(entry[l])) {
                    entry[l] = null;
                } else if (entry[l].startsWith(PFX)) {
                    entry[l] = entry[l].replaceFirst(PFX, "");
                }
                traceVerbose(worker, "ENTRY["+l+"] = "+entry[l]);
            }

            traceInfo(worker, "'" + path + "' read " + numLeaves + " leaves "
                      + String.format("[%d ms]", tick(start)));
            return entry;

        } catch (Exception e) {
            throw new NedException("Internal ERROR in maapiGetObjects(): " + e.getMessage(), e);
        }
    }


    /**
     * Get String leaf using Maapi
     * @param
     * @return
     */
    protected String maapiGetLeafString(NedWorker worker, int th, String path) {
        // Trim to absolute path
        int up;
        while ((up = path.indexOf("/../")) > 0) {
            int slash = path.lastIndexOf('/', up-1);
            path = path.substring(0, slash) + path.substring(up + 3);
        }
        // Get leaf
        try {
            if (maapi.exists(th, path)) {
                return ConfValue.getStringByValue(path, maapi.getElem(th, path));
            }
        } catch (Exception e) {
            traceInfo(worker, "maapiGetLeafString("+path+") Exception: "+e.getMessage());
        }
        return null;
    }


    /**
     *
     * @param
     * @return
     * @throws IOException, SSHSessionException
     */
    private String print_line_exec(NedWorker worker, String line) throws IOException, SSHSessionException {

        // ned-setting cisco-iosxr developer simulate-command *
        String simulated = simulateCommand(worker, line);
        if (simulated != null) {
            return simulated;
        }

        // Send command and wait for echo
        session.print(line + "\n");
        session.expect(new String[] { Pattern.quote(line) }, worker);

        // Return command output
        traceDebug3(worker, "Waiting for prompt");
        return session.expect(PROMPT, worker);
    }


    /**
     *
     * @param
     * @return
     * @throws IOException, SSHSessionException
     */
    private String print_line_exec(NedWorker worker, String line, int timeout)
        throws IOException, SSHSessionException {

        resetTimeout(worker, timeout, 0);

        // ned-setting cisco-iosxr developer simulate-command *
        String simulated = simulateCommand(worker, line);
        if (simulated != null) {
            return simulated;
        }

        // Send command and wait for echo
        session.print(line + "\n");
        session.expect(new String[] { Pattern.quote(line) }, worker);

        // Return command output
        traceDebug3(worker, "Waiting for prompt");
        return session.expect(PROMPT, timeout, worker);
    }


    /**
     *
     * @param
     * @return
     */
    private boolean isExecError(String res) {
        return res.contains("Invalid input detected at")
            || res.contains("syntax error");
    }


    /**
     *
     * @param
     * @return
     */
    protected String simulateCommand(NedWorker worker, String line) {
        // ned-setting cisco-iosxr developer simulate-command *
        try {
            HashMap<String,String> map = new HashMap<>();
            String path = "developer/simulate-command{\""+line+"\"}/file";
            nedSettings.getMatching(map, path);
            if (map.size() > 0) {
                String filename = map.get(path);
                if (filename != null) {
                    String output = readFile(filename);
                    if (output != null) {
                        traceInfo(worker, "Simulating '"+line+"' output from '"+filename+"':\n"+output);
                        return output;
                    }
                }
            }
        } catch (Exception e) {
            traceInfo(worker, "failed to simulate command "+stringQuote(line)+": "+e.getMessage());
        }
        return null;
    }


    /**
     * Read file from disk
     * @param
     * @return
     * @throws IOException
     */
    private String readFile(String file) throws IOException {
        BufferedReader reader = new BufferedReader(new java.io.FileReader(file));
        String line = null;
        StringBuilder sb = new StringBuilder();
        try {
            while ((line = reader.readLine()) != null) {
                sb.append(line);
                sb.append("\r\n");
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }


    /**
     * Check if line is top exit command
     * @param
     * @return
     */
    protected boolean isTopExit(String line) {
        line = line.replace("\r", "");
        if ("exit".equals(line)) {
            return true;
        }
        return "!".equals(line);
    }



    /**
     *
     * @param
     * @return
     */
    private String fillInjectLine(NedWorker worker, String insert, String where, String[] groups, String dir) {
        int offset = 0;

        // Replace $i with group value from match.
        // Note: hard coded to only support up to $9
        for (int i = insert.indexOf('$'); i >= 0; i = insert.indexOf('$', i+offset)) {
            int num = (insert.charAt(i+1) - '0');
            insert = insert.substring(0,i) + groups[num] + insert.substring(i+2);
            offset = offset + groups[num].length() - 2;
        }

        traceInfo(worker, "transformed "+dir+" injected "+stringQuote(insert)+" "+where+" "+stringQuote(groups[0]));

        return insert;
    }


    /**
     *
     * @param
     * @return
     */
    private String stripLineAll(NedWorker worker, String res, String search, String dir, boolean trim) {
        StringBuilder buffer = new StringBuilder();
        String[] lines = res.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (trim) {
                line = line.trim();
            }
            if (line.startsWith(search)) {
                traceVerbose(worker, "transformed "+dir+" stripped '"+line.trim()+"'");
                continue;

            }
            buffer.append(lines[i]+"\n");
        }
        return buffer.toString();
    }


    /**
     *
     * @param
     * @return
     */
    private boolean isDevice() {
        return !"NETSIM".equals(iosmodel);
    }


    /**
     *
     * @param
     */
    private void sleep(NedWorker worker, long milliseconds, boolean log) {
        if (log) {
            traceInfo(worker, "Sleeping " + milliseconds + " milliseconds");
        }
        sleepMs(milliseconds);
        if (log) {
            traceInfo(worker, "Woke up from sleep");
        }
    }
    private void sleepMs(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    /**
     * Like NedString.stringDequote except that it preserves single backslash
     * @param
     * @return
     */
    private static String textDequote(String aText) {
        if (aText.startsWith("\"") && aText.endsWith("\"")) {
            aText = aText.substring(1,aText.length()-1); // Strip quotes around string
        }
        StringBuilder sb = new StringBuilder();
        StringCharacterIterator it = new StringCharacterIterator(aText);
        char c1 = it.current();
        while (c1 != CharacterIterator.DONE) {
            if (c1 == '\\') {
                char c2 = it.next();
                if (c2 == CharacterIterator.DONE) {
                    sb.append(c1);
                } else if (c2 == 'b') {
                    sb.append('\b');
                } else if (c2 == 'n') {
                    sb.append('\n');
                } else if (c2 == 'r') {
                    sb.append('\r');
                } else if (c2 == 'v') {
                    sb.append((char) 11); // \v
                } else if (c2 == 'f') {
                    sb.append('\f');
                } else if (c2 == 't') {
                    sb.append('\t');
                } else if (c2 == 'e') {
                    sb.append((char) 27); // \e
                } else if (c2 == '\\') {
                    sb.append('\\');
                } else if (c2 == '\"') {
                    sb.append('\"');
                } else {
                    sb.append(c1);
                    sb.append(c2);
                }
            } else {
                sb.append(c1);
            }
            c1 = it.next();
        }
        return sb.toString();
    }
}
