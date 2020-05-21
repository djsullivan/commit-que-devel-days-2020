package com.tailf.packages.ned.iosxr;

import java.util.ArrayList;

import com.tailf.ned.NedWorker;
import com.tailf.ned.NedException;


/**
 * Utility class for modifying config data based on YANG model meta data provided by NCS.
 *
 * @author lbang
 * @version 2020-04-29
 */

@SuppressWarnings("deprecation")
public class MetaDataModify {

    // Constructor data:
    private IosxrNedCli owner;

    // Cached ned-settings:
    private boolean autoVrfForwardingRestore;


    /**
     * Constructor
     */
    MetaDataModify(IosxrNedCli owner) throws Exception {
        this.owner = owner;
        this.autoVrfForwardingRestore = owner.nedSettings.getBoolean("auto/vrf-forwarding-restore");
    }

    /*
     * Modify config data based on meta-data given by NCS.
     *
     * @param data - config data from applyConfig, before commit
     * @return Config data modified after parsing !meta-data tags
     */
    public String modifyData(NedWorker worker, String data, int fromTh, int toTh, StringBuilder dc)
        throws NedException {

        // Modify line(s)
        String[] lines = data.split("\n");
        StringBuilder sb = new StringBuilder();
        int lastif = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) {
                continue;
            }
            if (lines[i].startsWith("interface ")) {
                lastif = i;
            }

            // Normal config line -> add
            if (!lines[i].trim().startsWith("! meta-data :: /ncs:devices/device{")) {
                sb.append(lines[i] + "\n");
                continue;
            }

            // Find command index (reason: can be multiple meta-data tags per command)
            int cmd = getCmd(lines, i + 1);
            if (cmd == -1) {
                continue;
            }
            String trimmed = lines[cmd].trim();
            String pxSpace = lines[cmd].substring(0, lines[cmd].length() - trimmed.length());

            // Extract meta-data and meta-value(s), store in metas[] where:
            // metas[1] = meta path
            // metas[2] = meta tag name
            // metas[3] = first meta-value (each value separated by ' :: '
            String meta = lines[i].trim();
            String[] metas = meta.split(" :: ");
            String metaPath = metas[1];
            String metaTag = metas[2];


            // delete-syntax
            // =========================
            // Change delete syntax, three variants:
            // metas[3] = null -> strip delete line
            // metas[3] = <new delete line>
            // metas[3] = <regexp> metas[4] = <replacement>
            if ("delete-syntax".equals(metaTag)) {
                if (!trimmed.startsWith("no ") || owner.isNetsim()) {
                    continue;
                }
                if (metas.length > 4) {
                    lines[cmd] = lines[cmd].replaceFirst(metas[3], metas[4]);
                } else if (metas.length > 3) {
                    lines[cmd] = metas[3]; // Reset delete line
                } else {
                    lines[cmd] = ""; // Strip delete line
                }
            }

            // max-values
            // max-values-mode
            // ====================
            // Split config lines with multiple values into multiple lines with a maximum
            // number of values per line.
            // metas[3] = offset in values[] for first value
            // metas[4] = maximum number of values per line
            // metas[5] = value separator [OPTIONAL]
            // Example:
            // tailf:meta-data "max-values" {
            //  tailf:meta-value "4 :: 8";
            // }
            else if (metaTag.startsWith("max-values")) {
                // Do not split modes with separators if contents in submode
                if ("max-values-mode".equals(metaTag)
                    && cmd + 1 < lines.length
                    && !isTopExit(lines[cmd+1])) {
                    continue;
                }
                String sep = " ";
                if (metas.length > 5) {
                    sep = metas[5];
                }
                int offset = Integer.parseInt(metas[3]);
                if (trimmed.startsWith("no ")) {
                    offset++;
                }
                int start = nIndexOf(trimmed, " ", offset);
                if (start > 0) {
                    int maxValues = Integer.parseInt(metas[4]);
                    String[] val = trimmed.substring(start+1).trim().split(sep+"+");
                    if (val.length > maxValues) {
                        String lprefix = pxSpace + trimmed.substring(0, start).trim();
                        traceInfo(worker, "meta-data max-values :: transformed => split '"+trimmed
                                  +"' into max "+maxValues+" values, separator='"+sep+"'");
                        sb.append(duplicateToX2(lprefix, val, "", maxValues, sep));
                        lines = trimCmd(lines, i, cmd);
                    }
                }
            }

            // string-add-quotes
            // =================
            // Add a " before and after specified string
            // metas[3] = regexp, where <STRING> is the string to look at.
            // example:
            // tailf:meta-data "string-add-quotes" {
            //  tailf:meta-value "syslog msg <STRING>";
            // }
            else if ("string-add-quotes".equals(metaTag)) {
                String regexp = metas[3].replace("<STRING>", "(.*)");
                String replacement = metas[3].replace("<STRING>", "\\\"$1\\\"");
                String newline = lines[cmd].replaceFirst(regexp, replacement);
                if (!lines[cmd].equals(newline)) {
                    lines[cmd] = newline;
                    traceMeta(worker, metaTag, "quoted '"+lines[cmd]+"'");
                }
            }

            // string-remove-quotes
            // ====================
            // metas[3] = regexp, where <STRING> is the string to look at.
            // example:
            // tailf:meta-data "string-remove-quotes" {
            //  tailf:meta-value "route-policy <STRING>";
            // }
            else if (metaTag.startsWith("string-remove-quotes")) {
                if (owner.isNetsim()) {
                    continue;
                }
                String regexp = metas[3].replace("<STRING>", "\\\"(.*)\\\"");
                String replacement = metas[3].replace("<STRING>", "$1");
                String newline = lines[cmd].replaceFirst(regexp, replacement);
                if (!lines[cmd].equals(newline)) {
                    lines[cmd] = newline;
                    traceInfo(worker, "meta-data string-remove-quotes :: transformed => unquoted '"+lines[cmd]+"'");
                }
            }

            // if-vrf-restore
            // ==============
            // Restore interface addresses if vrf is modified
            else if ("if-vrf-restore".equals(metaTag)) {
                if (owner.isNetsim() || !autoVrfForwardingRestore) {
                    continue;
                }
                String ifpath = metaPath.substring(0,metaPath.lastIndexOf('}')+1);

                // Trim all (subsequent) address changes in this transaction
                for (int j = cmd + 1; j < lines.length; j++) {
                    if (lines[j].equals("exit")) {
                        break;
                    }
                    if (lines[j].matches("^ (no )?ipv(4|6) address .*$")) {
                        lines[j] = "";
                    }
                    if (lines[j].matches("^ (no )?ipv6 enable$")) {
                        lines[j] = "";
                    }
                }

                // Delete addresses
                if (owner.maapiExists(worker, fromTh, ifpath+"/ipv4/address/ip")
                    || owner.maapiExists(worker, fromTh, ifpath+"/ipv4/ address-secondary-list/address")) {
                    sb.append(" no ipv4 address\n");
                }
                if (owner.maapiExists(worker, fromTh, ifpath+"/ipv6/address/prefix-list")) {
                    sb.append(" no ipv6 address\n");
                }
                if (owner.maapiExists(worker, fromTh, ifpath+"/ipv6/enable")) {
                    sb.append(" no ipv6 enable\n");
                }

                // Add the vrf line
                sb.append(lines[cmd]+"\n");
                lines[cmd] = "";

                // Add back all current interface addresses and (optionally) 'ipv6 enable'
                int num = maapiGetIfAddrs(worker, toTh, ifpath, sb);
                if (num > 0) {
                    traceInfo(worker, "meta-data if-vrf-restore :: transformed => "
                              +lines[lastif]+" vrf modified, restored "+num+" item(s)");
                }
            }

            // metaTag not handled by this loop -> copy it over
            else {
                sb.append(lines[i] + "\n");
            }
        }

        // Done
        return "\n" + sb.toString();
    }


    /*
     * Write info in NED trace
     *
     * @param info - log string
     */
    private void traceInfo(NedWorker worker, String info) {
        owner.traceInfo(worker, info);
    }
    private void traceMeta(NedWorker worker, String metaTag, String info) {
        traceInfo(worker, "meta-data "+metaTag+" :: transformed => "+info);
    }

    /*
     * Write info in NED trace if verbose output
     *
     * @param info - log string
     */
    private void traceVerbose(NedWorker worker, String info) {
        owner.traceVerbose(worker, info);
    }


    /**
     *
     * @param
     * @return
     */
    private boolean isTopExit(String line) {
        if ("!".equals(line)) {
            return true;
        }
        return "exit".equals(line);
    }


    /**
     *
     * @param
     * @return
     */
    private String duplicateToX2(String lprefix, String[] val, String postfix, int x, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int n = 0; n < val.length; n = n + x) {
            StringBuilder line = new StringBuilder();
            for (int j = n; (j < n + x) && (j < val.length); j++) {
                if (j != n) {
                    line.append(sep);
                }
                line.append(val[j]);
            }
            sb.append(lprefix + " " + line.toString() + postfix + "\n");
        }
        return sb.toString();
    }


    /**
     *
     * @param
     * @return
     */
    private int nIndexOf(String text, String str, int num) {
        int i = 0;
        for (int n = 0; n < num - 1; n++) {
            i = text.indexOf(str, i);
            if (i < 0) {
                return -1;
            }
            i++;
        }
        return text.indexOf(str, i);
    }


    /**
     * Trim cmd and all meta-data tags that goes with it
     * @param
     * @return
     */
    private String[] trimCmd(String[] lines, int i, int cmd) {
        for (int n = i; n <= cmd; n++) {
            lines[n] = "";
        }
        return lines;
    }


    /**
     * Get first config line after meta-data(s)
     * @param
     * @return
     */
    private int getCmd(String[] lines, int i) {
        for (int cmd = i; cmd < lines.length; cmd++) {
            String trimmed = lines[cmd].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("! meta-data :: /ncs:devices/device{")) {
                continue;
            }
            return cmd;
        }
        return -1;
    }


    /**
     * Retrieve interfaces address(es) from interface, including options
     * @param
     * @return
     */
    private int maapiGetIfAddrs(NedWorker worker, int th, String ifpath, StringBuilder sb)
        throws NedException {

        int added = 0;
        try {
            // interface * / ipv4 address
            String address = owner.maapiGetLeafString(worker, th, ifpath+"/ipv4/address/ip");
            if (address != null) {
                String mask = owner.maapiGetLeafString(worker, th, ifpath+"/ipv4/address/mask");
                sb.append(" ipv4 address "+address+" "+mask);
                String tag = owner.maapiGetLeafString(worker, th, ifpath+"/ipv4/address/route-tag");
                if (tag != null) {
                    sb.append(" route-tag "+tag);
                }
                sb.append("\n");
                added++;
            }

            // interface * / ipv4 address * secondary
            ArrayList<String[]> list = owner.maapiGetObjects(worker, th,
                                                             ifpath+"/ipv4/address-secondary-list/address", 4);
            if (!list.isEmpty()) {
                for (String[] addr : list) {
                    sb.append(" ipv4 address "+addr[0]+" "+addr[2]+" secondary");
                    if (addr[3] != null) {
                        sb.append(" route-tag "+addr[3]);
                    }
                    sb.append("\n");
                    added++;
                }
            }

            // interface * / ipv6 enable
            if (owner.maapiExists(worker, th, ifpath+"/ipv6/enable")) {
                sb.append(" ipv6 enable\n");
                added++;
            }

            // interface * / ipv6 address *
            list = owner.maapiGetObjects(worker, th, ifpath+"/ipv6/address/prefix-list", 4);
            if (!list.isEmpty()) {
                for (String[] addr : list) {
                    sb.append(" ipv6 address "+addr[0]);
                    if (addr[1] != null) {
                        sb.append(" eiu-64");
                    }
                    if (addr[2] != null) {
                        sb.append(" link-local");
                    }
                    if (addr[3] != null) {
                        sb.append(" route-tag "+addr[3]);
                    }
                    sb.append("\n");
                    added++;
                }
            }
        } catch (Exception e) {
            throw new NedException("maapiGetIfAddrs ERROR: "+e.getMessage(), e);
        }

        return added;
    }

}
