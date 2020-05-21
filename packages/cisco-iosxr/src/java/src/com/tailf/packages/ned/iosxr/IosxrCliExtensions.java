package com.tailf.packages.ned.iosxr;

import static com.tailf.packages.ned.nedcom.NedString.stringQuote;

import java.util.List;
import java.util.ArrayList;
import com.tailf.ned.NedWorker;

import com.tailf.packages.ned.nedcom.NedComCliBase;
import com.tailf.packages.ned.nedcom.Schema;


public class IosxrCliExtensions implements NedComCliBase.ExtensionsHandler {

    protected IosxrNedCli owner;
    protected Schema schema;

    public IosxrCliExtensions(IosxrNedCli owner) {
        this.owner = owner;
        this.schema = owner.getCurrentSchema();
    }

    public void initialize() {
        // Nothing to initialize
    }


    /**
     * "if-delete-redeploy <argument>" - Delete and redeploy interface target value when argument leaf is modified
     * @param
     * @throws Exception
     */
    public void ifDeleteRedeploy(final NedWorker worker, Schema.CallbackMetaData metaData,
                           Schema.ParserContext parserContext, final int fromT, final int toT) throws Exception {
        String line = parserContext.getCurrentLine();
        traceVerbose(worker, "   line = "+line);

        if (parserContext.getState() == Schema.ParserState.MULTI_LINE) {
            if (owner.isTopExit(line)) {
                parserContext.endMultiLine();
            }
            return;
        }

        final String ifpath = parserContext.getNCSCurrentKP(owner.device_id);
        traceVerbose(worker, "   ifpath = "+shortpath(ifpath));

        if (!owner.maapi.exists(toT, ifpath) || !owner.maapi.exists(fromT, ifpath)) {
            return;  // Interface is deleted or created
        }

        final String path = ifpath + "/" + metaData.argument;
        String fromL2 = maapiGetLeafString(worker, fromT, path);
        String toL2 = maapiGetLeafString(worker, toT, path);
        if (fromL2.equals(toL2)) {
            return; // no L2|3 change
        }

        traceVerbose(worker, "   fromL2 = "+fromL2);
        traceVerbose(worker, "   toL2 = "+toL2);

        // Delete interface in first commit
        if (!toL2.isEmpty()) {
            line = line.replace(" "+toL2, "");
        }
        parserContext.injectBefore("no "+line+" "+fromL2);

        // Redeploy interface in delayed commit
        String redeploy = owner.maapiGetConfig(worker, toT, ifpath);
        traceInfo(worker, "transformed => delete and delayed redeploy: "+stringQuote(redeploy));
        owner.delayedCommit.append(redeploy);

        // Ignore all interface changes in first commit
        parserContext.startMultiLine(metaData);
    }


    /**
     * "list-modify-redeploy" - If list contents are modified, delete them and redeploy all
     * @param
     * @throws Exception
     */
    public void listModifyRedeploy(final NedWorker worker, Schema.CallbackMetaData metaData,
                                   Schema.ParserContext parserContext, final int fromT, final int toT)
        throws Exception {
        final String cmd = parserContext.getCurrentLine();
        traceVerbose(worker, "   cmd = "+cmd);

        if (parserContext.getState() != Schema.ParserState.MULTI_LINE) {
            final String path = parserContext.getCurrentKeyPath();
            traceVerbose(worker, "  path = "+path);
            final String ncsPath = ncspath(path);
            if (owner.maapi.exists(fromT, ncsPath) && owner.maapi.exists(toT, ncsPath)) {
                // A list change, collect entire list
                parserContext.startMultiLine(metaData);
                parserContext.muteCallback(path, metaData);
            }
            return;
        }

        // Collect entire list entry before checking it
        if (!owner.isTopExit(cmd)) {
            return;
        }

        // End multi-line mode and count additions and deletions
        final String path = ncspath(parserContext.getMultiLineKeyPath());
        traceVerbose(worker, "  multi-path = "+path);
        List<String> lines = parserContext.endMultiLine();
        int numDel = 0;
        for (String line : lines) {
            traceVerbose(worker, "   --- "+line);
            if (line.trim().startsWith("no ")) {
                numDel++;
            }
        }
        final int numAdd = lines.size() - numDel - 2;
        traceVerbose(worker, "   #add = "+numAdd);
        traceVerbose(worker, "   #del = "+numDel);
        if (numAdd == 0 || numDel == 0) {
            // Only adding or deleting -> ignore redeploy
            parserContext.injectImmediate(lines);
            return;
        }

        // Insert delete of old contents
        List<String> inject = deleteContents(owner.maapiGetConfig(worker, fromT, path));
        parserContext.injectImmediate(inject);

        // Redeploy list in delayed commit
        String redeploy = owner.maapiGetConfig(worker, toT, path);
        traceInfo(worker, "transformed => delete contents and delayed redeploy: "+stringQuote(redeploy));
        owner.delayedCommit.append(redeploy);
    }


    /*
     **************************************************************************
     * Common utility methods
     **************************************************************************
     */

    private List<String> deleteContents(String entry) {
        String[] lines = entry.trim().split("\n");
        List<String> delete = new ArrayList<>();
        delete.add(lines[0]);
        for (int n = 1; n < lines.length; n++) {
            if (owner.isTopExit(lines[n])) {
                delete.add(lines[n]);
                break;
            }
            if (lines[n].startsWith("  ") || lines[n].startsWith(" !") || lines[n].startsWith(" exit")) {
                continue;
            }
            delete.add(" no"+lines[n]);
        }
        return delete;
    }

    private String maapiGetLeafString(NedWorker worker, int th, String path) {
        String val = owner.maapiGetLeafString(worker, th, path);
        if (val == null) {
            return "";
        }
        return val;
    }

    private void traceInfo(NedWorker worker, String info) {
        owner.traceInfo(worker, info);
    }
    private void traceVerbose(NedWorker worker, String info) {
        owner.traceVerbose(worker, info);
    }

    private String ncspath(String path) {
        return schema.createNCSDeviceConfigKP(owner.device_id, path);
    }
    private String shortpath(String path) {
        return path.substring(path.indexOf("/config/")+8);
    }

}
