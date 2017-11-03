package com.sourcegraph.javagraph;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SbtProject {
    private static final Logger LOGGER = LoggerFactory.getLogger(SbtProject.class);

    public static Collection<SourceUnit> findAllSourceUnits() throws IOException {
        Collection<SourceUnit> units = new ArrayList<>();
        // TODO: parses all sorts of bogus test input in some projects -- hard code top-level build.sbt instead.
        Collection<Path> sbtFiles = ScanUtil.findMatchingFiles("build.sbt");
        if (sbtFiles.size() > 0) { 
            // hardcode first traversed build.sbt (top-level)
            units.addAll(processSourceUnit(sbtFiles.iterator().next()));
        }
        return units;
    }

    private static Collection<SourceUnit> processSourceUnit(Path path) throws IOException {
        // sbt startup is slow so we only invoke it once and parse output interactively.
        Process process = new ProcessBuilder(ImmutableList.of(
                    "/usr/bin/sbt",
                    "-no-colors"))
                .directory(path.getParent().toFile())
                .start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            String selectedProject = null;
            Collection<String> projects = new ArrayList<>();

            // TODO: "set asciiGraphWidth := 10000" when sbt >= 1.0.0

            // consume garbage from beginning of run
            executeSbtCommand("", reader, writer);

            Iterator<String> lines = executeSbtCommand("projects", reader, writer).iterator();
            while (lines.hasNext()) {
                String line = lines.next();
                if (line.startsWith("In file:")) {
                    break;
                }
            }

            while (lines.hasNext()) {
                String line = lines.next();
                String selectedPrefix = "\t * ";
                String prefix = "\t   ";
                if (line.startsWith(selectedPrefix)) {
                    String project = line.substring(selectedPrefix.length());
                    LOGGER.debug("found selected project: " + project);
                    selectedProject = project;
                } else if (line.startsWith(prefix)) {
                    String project = line.substring(prefix.length());
                    LOGGER.debug("found project: " + project);
                    projects.add(project);
                }
            }

            if (projects.isEmpty() && selectedProject != null) {
                // Only consider the selected project if we are not a multi-module project.
                projects.add(selectedProject);
            }

            Collection<SourceUnit> sourceUnits = new ArrayList<>();
            for (String project : projects) {
                sourceUnits.addAll(processProject(path, project, reader, writer));
            }

            executeSbtCommand("exit", reader, writer);

            return sourceUnits;
        } finally {
            process.destroy();
        }
    }

    private static Collection<SourceUnit> processProject(Path path, String project, BufferedReader reader, BufferedWriter writer) throws IOException {
        executeSbtCommand("project " + project, reader, writer);

        Collection<String> subprojects = executeSbtCommand("show name", reader, writer);
        // project may include subprojects which we need to parse
        if (subprojects.size() > 1) {
            Collection<SourceUnit> sourceUnits = new ArrayList<>();
            for (String subproject : subprojects) {
                String prefix = "    ";
                if (!subproject.startsWith(prefix)) {
                    continue;
                }
                subproject = subproject.substring(prefix.length());
                if (project.equals(subproject)) {
                    continue;
                }
                LOGGER.debug("found subproject: {}", subproject);
                sourceUnits.addAll(processProject(path, subproject, reader, writer));
            }
            return sourceUnits;
        }

        // only a single project
        SourceUnit unit = new SourceUnit();

        unit.Name = subprojects.iterator().next();

        // Treat all Scala artifacts like Java artifacts so that the fetcher processes them.
        unit.Type = "JavaArtifact";

        // parse dependencies
        for (String dependency : executeSbtCommand("show libraryDependencies", reader, writer)) {
            String prefix = "* ";
            Preconditions.checkState(dependency.startsWith(prefix), "unexpected dependency prefix: %s", dependency);
            dependency = dependency.substring(prefix.length());
            if (dependency.length() == 0) {
                continue;
            }

            String[] dep = dependency.split(":");
            Preconditions.checkState(dep.length >= 3, "unexpected dependency format: %s", dependency);
            RawDependency raw = new RawDependency(/*groupID=*/ dep[0],
                    /*artifactID=*/ dep[1], /*version=*/ dep[2],
                    /*scope=*/ dep.length >= 4 ? dep[3] : null, /*file=*/ null,
                    PathUtil.relativizeCwd(path.toAbsolutePath()).toString());
            raw.type = "jar";
            unit.Dependencies.add(raw);
        }

        // parse source files
        unit.Files = new ArrayList<>();
        for (String sourceFile : executeSbtCommand("show unmanagedSources", reader, writer)) {
            String prefix = "* ";
            // ignore trailer: "[success] Total time: 0 s, completed Oct 19, 2017 2:16:57 PM"
            if (sourceFile.startsWith(prefix)) {
                unit.Files.add(sourceFile.substring(prefix.length()));
            }
        }

        unit.Dir = path.getParent().toString();

        Collection<SourceUnit> sourceUnits = new ArrayList<>();
        sourceUnits.add(unit);
        return sourceUnits;
    }

    /**
     * Execute an sbt command and return all log messages at info level.
     * Emit all error and warn messages to the logger.
     */
    private static Collection<String> executeSbtCommand(String command,
            BufferedReader reader, BufferedWriter writer) throws IOException {
        LOGGER.debug("sbt input: {}", command);
        writer.write(command);
        writer.newLine();
        // emit a sentinel so we know when to stop reading
        writer.write("eval \"EOF\"");
        writer.newLine();
        writer.flush();

        Collection<String> lines = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            LOGGER.debug("sbt output: {}", line);
            if (line.equals("[info] ans: String = EOF")) {
                break;
            }
            String prefix = "[error] ";
            if (line.startsWith(prefix)) {
                line = line.substring(prefix.length());
                LOGGER.error(line);
                continue;
            }
            prefix = "[warn] ";
            if (line.startsWith(prefix)) {
                line = line.substring(prefix.length());
                LOGGER.warn(line);
                continue;
            }
            prefix = "[info] ";
            if (!line.startsWith(prefix)) {
                continue;
            }
            line = line.substring(prefix.length());
            lines.add(line);
        }
        return lines;
    }
}
