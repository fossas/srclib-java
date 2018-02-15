package com.sourcegraph.javagraph;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.ParseException;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.plugins.report.XmlReportParser;

import com.google.common.collect.ImmutableList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SbtProject {
	private static final Logger LOGGER = LoggerFactory.getLogger(SbtProject.class);

	public static Collection<SourceUnit> findAllSourceUnits() throws IOException {
		Collection<SourceUnit> units = new ArrayList<>();
		// TODO: parses all sorts of bogus test input in some projects -- hard code
		// top-level build.sbt instead.
		Collection<Path> sbtFiles = ScanUtil.findMatchingFiles("build.sbt");
		if (sbtFiles.size() > 0) {
			addDepsPluginToProject();
			units.addAll(processSourceUnit(sbtFiles.iterator().next()));
		}
		return units;
	}

	private static Collection<SourceUnit> processSourceUnit(Path path) throws IOException {
		// sbt startup is slow so we only invoke it once and parse output interactively.
		Process process = new ProcessBuilder(ImmutableList.of("/usr/bin/sbt", "-no-colors"))
				.directory(path.getParent().toFile()).start();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
				BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
			String selectedProject = null;
			Collection<String> projects = new ArrayList<>();

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

	private static Collection<SourceUnit> processProject(Path path, String project, BufferedReader reader,
			BufferedWriter writer) throws IOException {
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

		// Treat all Scala artifacts like Java artifacts so that the fetcher processes
		// them.
		unit.Type = "JavaArtifact";

		/*
		 *  NOTE (Alex Nuccio): we are using the "show ivyReport" command, which creates an XML file ivy-report that we can parse with the ivy lib. The issue is that sometimes the incorrect path of the report xml is 
		 *  output: https://github.com/jrudolph/sbt-dependency-graph/issues/21. For reference, try "show ivyReport" on https://github.com/anuccio1/sbt-test, and the wrong path is output. 
		 *  For this reason, if the file that is output doesn't exist, we will use the "dependencyList" command which outputs a flat dep list to stdout.
		 *  
		 *  We previously used "show libraryDependencies", however this wasn't outputting reliable deps
		 */
		Collection<String> ivyReportString = executeSbtCommand("show ivyReport", reader, writer);
		Pattern ivyReportXMLPattern = Pattern.compile("/[\\S\\s]+/resolution-cache/reports/[\\S\\s]+\\.xml$"); // match absolute path of ivy-report xml file
		for (String ivyStr: ivyReportString) {
			Matcher m = ivyReportXMLPattern.matcher(ivyStr);
			if (!m.matches()) continue;
			File currentIvyReportFile = new File(ivyStr);
			// If file wasn't created/path output isn't right, fall back to dependencyList
			if (!currentIvyReportFile.exists()) {
				unit.Dependencies.addAll(getFlatDepList(path, reader, writer));
				continue;
			}
			XmlReportParser ivyReport = new XmlReportParser();
			try {
				ivyReport.parse(currentIvyReportFile);
			} catch (ParseException e) {
				LOGGER.warn(String.format("Error parsing ivy report file for project <%s>: %s", project, e.toString()));
				continue;
			}
			ModuleRevisionId[] revs = ivyReport.getDependencyRevisionIds();
			for(ModuleRevisionId rev : revs) {
				RawDependency raw = new RawDependency(rev.getOrganisation(), rev.getName(), rev.getRevision(), "compile", null, PathUtil.relativizeCwd(path.toAbsolutePath()).toString());
				raw.type = "ivy";
				unit.Dependencies.add(raw);	
			}
		}
		// parse source files
		unit.Files = new ArrayList<>();
		for (String sourceFile : executeSbtCommand("show unmanagedSources", reader, writer)) {
			String prefix = "* ";
			// ignore trailer: "[success] Total time: 0 s, completed Oct 19, 2017 2:16:57
			// PM"
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
	 * Execute an sbt command and return all log messages at info level. Emit all
	 * error and warn messages to the logger.
	 */
	private static Collection<String> executeSbtCommand(String command, BufferedReader reader, BufferedWriter writer)
			throws IOException {
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

	/**
	 * We add the dependency-graph custom plugin here:
	 * https://github.com/jrudolph/sbt-dependency-graph Instead of adding globally
	 * (we don't know what version of SBT the user is using), we create a file
	 * adding the plugin, and add it to the `projects` folder
	 * 
	 * @throws IOException
	 */
	private static void addDepsPluginToProject() throws IOException {
		String addPluginLine = "addSbtPlugin(\"net.virtual-void\" % \"sbt-dependency-graph\" % \"0.9.0\")";
		String fossaDepsSBTFileName = "fossa-deps-srclib.sbt";
		String sbtProjectsDirectory = "project";

		File sbtProjectDir = PathUtil.CWD.resolve(sbtProjectsDirectory).toFile();
		if (!sbtProjectDir.exists()) {
			sbtProjectDir.mkdir();
		}
		File fossaSBTFile = PathUtil.CWD.resolve(sbtProjectsDirectory + "/" + fossaDepsSBTFileName).toFile();
		FileWriter fw = new FileWriter(fossaSBTFile.getAbsolutePath());
		BufferedWriter bw = new BufferedWriter(fw);
		bw.write(addPluginLine);
		bw.close();
	}
	
	/**
	 * calls dependencyList, which outputs a flat dep list to stdout.
	 * Splitting by ":" and checking size >=3 will skip any unnecessary log info output to stdout. 
	 * 
	 * From testing, it seems that the only non-dep info logged to console are "Updating <project name>..." and "Done updating." and "Updating ProjectRef(uri("file:/private/tmp/akka/"), "<project name>")...
	 * 
	 * @throws IOException
	 */
	private static List<RawDependency> getFlatDepList (Path path, BufferedReader reader, BufferedWriter writer) throws IOException {
		Collection<String> flatDepList = executeSbtCommand("dependencyList", reader, writer);
		List<RawDependency> allDeps = new LinkedList<>();
		for (String depLine: flatDepList) {
			// This is how we define a non dep output to stdout. letters followed by any space followed by more letters.
			if (depLine.matches("[\\w]+\\s[\\w]")) continue;
			String[] dep = depLine.split(":");
			if (dep.length < 3) {
				continue;
			}
			// setting this to compile scope
			RawDependency raw = new RawDependency(dep[0], dep[1], dep[2], "compile", null, PathUtil.relativizeCwd(path.toAbsolutePath()).toString());
			raw.type = "ivy";
			allDeps.add(raw);	
		}

		return allDeps;
	}
}
