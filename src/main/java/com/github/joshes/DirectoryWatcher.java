package com.github.joshes;

/*
 * Copyright (c) 2008, 2010, Oracle and/or its affiliates. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *   - Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *   - Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *
 *   - Neither the name of Oracle nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import com.sun.nio.file.SensitivityWatchEventModifier;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * Example to watch a directory (or tree) for changes to files.
 */

public class DirectoryWatcher {

    private final WatchService watcher;
    private final Map<WatchKey, Path> keys;
    private boolean trace = false;
    private final String exec;
    private final Pattern filterPattern;

    @SuppressWarnings("unchecked")
    static <T> WatchEvent<T> cast(WatchEvent<?> event) {
        return (WatchEvent<T>) event;
    }

    /**
     * Register the given directory with the WatchService
     */
    private void register(Path dir) throws IOException {
        if (null != filterPattern && !isWatchable(dir.toString())) return;
        WatchEvent.Kind[] watchedEvents = new WatchEvent.Kind[]{ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY};
        WatchKey key = dir.register(watcher, watchedEvents, SensitivityWatchEventModifier.HIGH);
        if (trace) {
            Path prev = keys.get(key);
            if (prev == null) {
                System.out.format("register: %s\n", dir);
            } else {
                if (!dir.equals(prev)) {
                    System.out.format("update: %s -> %s\n", prev, dir);
                }
            }
        }
        keys.put(key, dir);
    }

    /**
     * Register the given directory, and all its sub-directories, with the
     * WatchService.
     */
    private void registerAll(final Path start) throws IOException {
        // register directory and sub-directories
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                register(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Creates a WatchService and registers the given directory
     */
    DirectoryWatcher(Path dir, String filter, String exec, boolean debug) throws IOException {
        this.watcher = FileSystems.getDefault().newWatchService();
        this.keys = new HashMap<WatchKey, Path>();
        this.exec = exec;
        this.trace = debug;

        if (null != filter && !filter.isEmpty()) {
            this.filterPattern = Pattern.compile(filter);
        } else this.filterPattern = null;

        System.out.format("Scanning %s ...\n", dir);
        registerAll(dir);
        System.out.println("Done.");
    }

    boolean isWatchable(String path) {
        boolean matches = filterPattern.matcher(path).matches();
        if (trace) {
            System.out.println(String.format("%s matches: %s", path, matches));
        }
        return matches;
    }

    /**
     * Process all events for keys queued to the watcher
     */
    void processEvents() {
        for (; ; ) {

            // wait for key to be signalled
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException x) {
                return;
            }

            Path dir = keys.get(key);
            if (dir == null) {
                System.err.println("WatchKey not recognized!!");
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind kind = event.kind();

                // TBD - provide example of how OVERFLOW event is handled
                if (kind == OVERFLOW) {
                    continue;
                }

                // Context for directory entry event is the file name of entry
                WatchEvent<Path> ev = cast(event);
                Path name = ev.context();
                Path child = dir.resolve(name);

                boolean isDirectory = Files.isDirectory(child, NOFOLLOW_LINKS);

                // if directory is created, then register it and its sub-directories
                if (kind == ENTRY_CREATE) {
                    try {
                        if (isDirectory) {
                            registerAll(child);
                        }
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                }

                if (null != exec) {
                    String cmd = exec;
                    if (cmd.contains("%file%")) {
                        cmd = cmd.replaceAll("%file%", child.toString());
                    }
                    if (cmd.contains("%event%")) {
                        cmd = cmd.replaceAll("%event%", event.kind().name());
                    }
                    try {
                        if (trace) {
                            System.out.println("exec:" + cmd);
                        }
                        Process proc = Runtime.getRuntime().exec(cmd);
                        String line;
                        BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                        while ((line = in.readLine()) != null) {
                            System.out.println(line);
                        }
                        in.close();
                    } catch (IOException e) {
                        e.printStackTrace(); // TODO warn
                    }
                }
            }

            // reset key and remove from set if directory no longer accessible
            boolean valid = key.reset();
            if (!valid) {
                keys.remove(key);

                // all directories are inaccessible
                if (keys.isEmpty()) {
                    break;
                }
            }
        }
    }

    private static Options getOptions() {
        final Options opts = new Options();

        final Option watchOpt = OptionBuilder
                .withArgName("watch")
                .hasOptionalArg()
                .withDescription("Path to watch within (recursively)")
                .create("watch");

        final Option filterOpt = OptionBuilder
                .withArgName("filter")
                .hasOptionalArg()
                .withDescription("Regex filter to only watch specific directories")
                .create("filter");

        final Option callbackOpt = OptionBuilder
                .withArgName("callback")
                .hasArg()
                .withDescription("Callback to be executed - can contain callback vars (%file% | %event%)")
                .create("callback");

        final Option debugOpt = OptionBuilder
                .withArgName("debug")
                .hasOptionalArg()
                .withDescription("Enables debug logging")
                .create("debug");

        opts.addOption(watchOpt);
        opts.addOption(filterOpt);
        opts.addOption(callbackOpt);
        opts.addOption(debugOpt);
        return opts;
    }

    public static void main(String[] args) throws IOException, ParseException {
        final Options options = getOptions();
        if (args.length < 2) {
            new HelpFormatter().printHelp("DirectoryWatcher", options);
            System.exit(1);
        }

        final CommandLine line = new GnuParser().parse(options, args);
        final Path watchDir = Paths.get(line.getOptionValue("watch"));
        final String filter = line.getOptionValue("filter");
        final String callback = line.getOptionValue("callback");
        final boolean debug = Boolean.parseBoolean(line.getOptionValue("debug", "false"));

        // register directory and process its events
        new DirectoryWatcher(watchDir, filter, callback, debug).processEvents();
    }
}