/*
 * Copyright (C) 2011 Sony Ericsson Mobile Communications AB
 * Copyright (C) 2012 Sony Mobile Communications AB
 *
 * This file is part of ChkBugReport.
 *
 * ChkBugReport is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * ChkBugReport is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ChkBugReport.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.sonyericsson.chkbugreport.plugins.stacktrace;

import com.sonyericsson.chkbugreport.BugReport;
import com.sonyericsson.chkbugreport.Chapter;
import com.sonyericsson.chkbugreport.PSRecord;
import com.sonyericsson.chkbugreport.Plugin;
import com.sonyericsson.chkbugreport.ProcessRecord;
import com.sonyericsson.chkbugreport.Report;
import com.sonyericsson.chkbugreport.Section;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processes the current stacktrace and the stacktrace at the last ANR.
 *
 * If DB access is working (in other words the sqlite jdbc driver is in the classpath),
 * then the stacktraces will be imported also in the database.
 *
 * Here is an example query which lists the number of threads per process:
 *
 *   select count(*) as nr_threads,p.name,p.pid,p.group_id
 *     from stacktrace_processes p
 *     inner join stacktrace_threads t on p.id = t.process_id
 *     group by p.id, p.group_id
 *     order by nr_threads desc
 *
 */
public class StackTracePlugin extends Plugin {

    private static final String TAG = "[StackTracePlugin]";

    public static final int ID_NOW = 1;
    public static final int ID_ANR = 2;
    public static final int ID_OLD = 3;
    public static final int ID_SLOW = 0x100;

    private HashMap<Integer, Processes> mProcesses = new HashMap<Integer, Processes>();

    private Chapter mSlowChapters;

    @Override
    public int getPrio() {
        return 10;
    }

    @Override
    public void load(Report rep) {
        BugReport br = (BugReport)rep;

        // Reset state
        mProcesses.clear();
        mSlowChapters = null;

        // Load data
        run(br, ID_NOW, "VM TRACES JUST NOW", "VM traces just now");
        run(br, ID_ANR, "VM TRACES AT LAST ANR", "VM traces at last ANR");
        // backward compatibility
        run(br, ID_OLD, "VM TRACES", "VM traces");

        // List all "VM TRACES WHEN SLOW" sections
        int id = ID_SLOW;
        for (Section sec : br.getSections()) {
            if (sec.getShortName().equals("VM TRACES WHEN SLOW")) {
                String ss = sec.getName();
                Matcher m = Pattern.compile("\\((.*)\\)").matcher(ss);
                if (m.find()) {
                    String s = m.group(1);
                    run(br, id++, sec, s);
                }
            }
        }
    }

    private void run(BugReport br, int id, String sectionName, String chapterName) {
        Section sec = br.findSection(sectionName);
        if (sec == null) {
            br.printErr(3, TAG + "Cannot find section: " + sectionName + " (aborting plugin)");
            return;
        }
        run(br, id, sec, chapterName);
    }

    private void run(BugReport br, int id, Section sec, String chapterName) {
        // Scan stack traces
        StackTraceScanner scanner = new StackTraceScanner(this);
        Processes processes = scanner.scan(br, id, sec, chapterName);
        mProcesses.put(id, processes);

        // Also do some initial pre-processing, mainly to extract some useful info for other plugins
        for (Process process : processes) {
            // First make a list of all known threads
            Vector<PSRecord> chpsr = br.findChildPSRecords(process.getPid());
            // Suggest names and remove known children
            int cnt = process.getCount();
            for (int i = 0; i < cnt; i++) {
                StackTrace stack = process.get(i);
                String propSysTid = stack.getProperty("sysTid");
                if (propSysTid != null) {
                    try {
                        int sysTid = Integer.parseInt(propSysTid);
                        ProcessRecord pr = br.getProcessRecord(sysTid, true, false);
                        pr.suggestName(stack.getName(), 40);
                        // remove known child process records
                        PSRecord psr = br.getPSRecord(sysTid);
                        if (psr != null) {
                            chpsr.remove(psr);
                        }
                    } catch (NumberFormatException nfe) { }
                }
            }
            // Store unknown process records
            for (PSRecord psr : chpsr) {
                process.addUnknownThread(psr);
            }
        }

    }

    @Override
    public void generate(Report rep) {
        BugReport br = (BugReport)rep;

        if (mProcesses.size() == 0) return;

        // Analyze the processes (for possible errors)
        for (Processes processes : mProcesses.values()) {
            Analyzer analyzer = new Analyzer(this);
            analyzer.analyze(br, processes);
        }

        // Generate the output
        for (Processes processes : mProcesses.values()) {
            Generator gen = new Generator(this);
            gen.generate(br, processes);
        }
        if (mSlowChapters != null) {
            mSlowChapters.sort(new Comparator<Chapter>() {
                @Override
                public int compare(Chapter o1, Chapter o2) {
                    return o1.getName().compareTo(o2.getName());
                }
            });
        }

        // Import data into DB as well
        DBImporter importer = new DBImporter(this);
        importer.importIntoDB(br, mProcesses);
    }

    public void addSlowChapter(BugReport br, Chapter main) {
        if (mSlowChapters == null) {
            mSlowChapters = new Chapter(br, "VM traces when slow");
            br.addChapter(mSlowChapters);
        }
        mSlowChapters.addChapter(main);
    }

}