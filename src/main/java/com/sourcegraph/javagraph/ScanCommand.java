package com.sourcegraph.javagraph;

import com.beust.jcommander.Parameter;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public class ScanCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScanCommand.class);

    @Parameter(names = {"--repo"}, description = "The URI of the repository that contains the directory tree being scanned")
    String repoURI;

    @Parameter(names = {"--subdir"}, description = "The path of the current directory (in which the scanner is run), relative to the root directory of the repository being scanned (this is typically the root, \".\", as it is most useful to scan the entire repository)")
    String subdir;

    public static final String JDK_TEST_REPO = "github.com/sgtest/java-jdk-sample";
    public static final String ANDROID_SDK_REPO = "android.googlesource.com/platform/frameworks/base";
    public static final String ANDROID_CORE_REPO = "android.googlesource.com/platform/libcore";
    public static final String ANDROID_SUPPORT_FRAMEWORK_REPO = "android.googlesource.com/platform/frameworks/support";

    /**
     * Main method
     */
    public void Execute() {

        try {
            if (repoURI == null) {
                repoURI = StringUtils.EMPTY;
            }
            if (subdir == null) {
                subdir = ".";
            }

            // Scan for source units.
            List<SourceUnit> units = new ArrayList<>();
            // Recursively find all Maven and Gradle projects.
            LOGGER.info("Collecting Maven source units");
            units.addAll(MavenProject.findAllSourceUnits());
            LOGGER.info("Collecting Gradle source units");
            units.addAll(GradleProject.findAllSourceUnits());
            LOGGER.info("Collecting Ant source units");
            units.addAll(AntProject.findAllSourceUnits());
            normalize(units);
            JSONUtil.writeJSON(units);
        } catch (Exception e) {
            LOGGER.error("Unexpected error occurred while collecting source units", e);
            System.exit(1);
        }
    }

    /**
     * Normalizes source units produces by scan command (sorts, relativizes file paths etc)
     *
     * @param units source units to normalize
     */
    @SuppressWarnings("unchecked")
    private static void normalize(Collection<SourceUnit> units) {

        Comparator<RawDependency> dependencyComparator = Comparator.comparing(dependency -> dependency.artifactID);
        dependencyComparator = dependencyComparator.
                thenComparing(dependency -> dependency.groupID).
                thenComparing(dependency -> dependency.version).
                thenComparing(dependency -> dependency.scope).
                thenComparing((o1, o2) -> {
                    if (o1.file == null) {
                        return o2.file == null ? 0 : -1;
                    } else if (o2.file == null) {
                        return 1;
                    }
                    return o1.file.compareTo(o2.file);
                });

        Comparator<String[]> sourcePathComparator = Comparator.comparing(sourcePathElement -> sourcePathElement[0]);
        sourcePathComparator = sourcePathComparator.
                thenComparing(sourcePathElement -> sourcePathElement[1]).
                thenComparing(sourcePathElement -> sourcePathElement[2]);

        for (SourceUnit unit : units) {
            unit.Dir = PathUtil.relativizeCwd(unit.Dir);
            unit.Dependencies = unit.Dependencies.stream()
                    .map(dependency -> {
                        if (dependency.file != null) {
                            dependency.file = PathUtil.relativizeCwd(dependency.file);
                        }
                        return dependency;
                    })
                    .filter(dependency -> !dependency.scope.toLowerCase().equals("test"))
                    .sorted(dependencyComparator)
                    .collect(Collectors.toList());
            List<String> internalFiles = new ArrayList<>();
            List<String> externalFiles = new ArrayList<>();
            splitInternalAndExternalFiles(unit.Files, internalFiles, externalFiles);
            unit.Files = internalFiles;
            if (!externalFiles.isEmpty()) {
                unit.Data.put("ExtraSourceFiles", externalFiles);
            }
            if (unit.Data.containsKey("POMFile")) {
                unit.Data.put("POMFile", PathUtil.relativizeCwd((String) unit.Data.get("POMFile")));
            }

            // Go through POM.
            // POM -> project -> dependencies -> dependency -> Array or Object
            if (unit.Data.containsKey("POM")) {
              try {
                JSONObject pomData = (JSONObject) unit.Data.get("POM");
                JSONObject project = pomData.optJSONObject("project");
                TreeSet<String> optionalDependenciesSet = new TreeSet<String>();
                Map<String, JSONArray> exclusionsMap = new TreeMap<String, JSONArray>();
                boolean canProceed = true;

                if (project == null) {
                  canProceed = false;
                  LOGGER.warn("There was a formatting issue with the POM data received. No project data could be found. Could not filter optional dependencies and add exclusions.");
                }

                if (canProceed && !project.has("dependencies")) {
                  canProceed = false;
                  LOGGER.debug("No dependencies in POM. Will not filter optional dependencies and add exclusions.");
                }

                if (canProceed && !project.getJSONObject("dependencies").has("dependency")) {
                  canProceed = false;
                  LOGGER.debug("No dependencies in POM. Will not filter optional dependencies and add exclusions.");
                }

                if (canProceed) {
                  LOGGER.debug("Filtering optional dependencies and adding exclusions.");
                  Object dependencies = project.getJSONObject("dependencies").get("dependency");
                  JSONArray dependenciesArray;

                  if (dependencies instanceof JSONArray) {
                    dependenciesArray = (JSONArray)dependencies;
                  } else if (dependencies instanceof JSONObject) {
                    dependenciesArray = new JSONArray();
                    dependenciesArray.put((JSONObject)dependencies);
                  } else {
                    dependenciesArray = new JSONArray();
                  }

                  // Gather optional dependencies
                  for (int i = 0; i < dependenciesArray.length(); i++) {
                    JSONObject dependency = dependenciesArray.getJSONObject(i);
                    if (dependency.has("optional") && dependency.getBoolean("optional")) {
                      optionalDependenciesSet.add(dependency.getString("groupId") + ":" + dependency.getString("artifactId"));
                    }
                  }

                  // Gather exclusions
                  for (int i = 0; i < dependenciesArray.length(); i++) {
                    JSONObject dependency = dependenciesArray.getJSONObject(i);
                    if (!dependency.has("exclusions")) {
                      continue;
                    }

                    if (!dependency.getJSONObject("exclusions").has("exclusion")) {
                      continue;
                    }

                    Object exclusion = dependency.getJSONObject("exclusions").get("exclusion");
                    JSONArray exclusionArray = null;
                    if (exclusion instanceof JSONArray) {
                      exclusionArray = (JSONArray)exclusion;
                    } else if (exclusion instanceof JSONObject) {
                      exclusionArray = new JSONArray();
                      exclusionArray.put((JSONObject)exclusion);
                    } else {
                      exclusionArray = new JSONArray();
                    }

                    exclusionsMap.put(
                      dependency.getString("groupId") + ":" + dependency.getString("artifactId"),
                      exclusionArray
                    );
                  }

                  unit.Dependencies = unit.Dependencies.stream()
                  .map(dep -> {
                    // Mark optional dependencies
                    if (optionalDependenciesSet.contains(dep.groupID + ":" + dep.artifactID)) {
                      dep.optional = true;
                    } else {
                      dep.optional = false;
                    }
                    return dep;
                  })
                  .map(dep -> {
                    // Add exclusions
                    if (exclusionsMap.containsKey(dep.groupID + ":" + dep.artifactID)) {
                      dep.exclusions = new ArrayList<RawExclusion>();
                      JSONArray exclusionsArray = exclusionsMap.get(dep.groupID + ":" + dep.artifactID);
                      for (int i = 0; i < exclusionsArray.length(); i++) {
                        JSONObject exclusion = exclusionsArray.getJSONObject(i);
                        dep.exclusions.add(new RawExclusion(exclusion.getString("groupId"), exclusion.getString("artifactId")));
                      }
                    }
                    return dep;
                  })
                  .collect(Collectors.toList());
                }
              } catch (Exception e) {
                LOGGER.error("Unknown exception happened when parsing POM data", e);
              }
            }

            if (unit.Data.containsKey(AntProject.BUILD_XML_PROPERTY)) {
                unit.Data.put(AntProject.BUILD_XML_PROPERTY,
                        PathUtil.relativizeCwd((String) unit.Data.get(AntProject.BUILD_XML_PROPERTY)));
            }

            if (unit.Data.containsKey("ClassPath")) {
                Collection<String> classPath = (Collection<String>) unit.Data.get("ClassPath");
                classPath = classPath.stream().
                        map(PathUtil::relativizeCwd).
                        collect(Collectors.toList());
                unit.Data.put("ClassPath", classPath);
            }
            if (unit.Data.containsKey("BootClassPath")) {
                Collection<String> classPath = (Collection<String>) unit.Data.get("BootClassPath");
                classPath = classPath.stream().
                        map(PathUtil::relativizeCwd).
                        sorted().
                        collect(Collectors.toList());
                unit.Data.put("BootClassPath", classPath);
            }
            if (unit.Data.containsKey("SourcePath")) {
                Collection<String[]> sourcePath = (Collection<String[]>) unit.Data.get("SourcePath");
                sourcePath = sourcePath.stream().
                        map(sourcePathElement -> {
                            sourcePathElement[2] = PathUtil.relativizeCwd(sourcePathElement[2]);
                            return sourcePathElement;
                        }).
                        sorted(sourcePathComparator).
                        collect(Collectors.toList());
                unit.Data.put("SourcePath", sourcePath);
            }
        }
    }

    /**
     * Splits files to two lists, one that will keep files inside of current working directory
     * (may be used as unit.Files) and the other that will keep files outside of current working directory.
     * Sorts both lists alphabetically after splitting
     *
     * @param files    list of files to split
     * @param internal list to keep files inside of current working directory
     * @param external list to keep files outside of current working directory
     */
    private static void splitInternalAndExternalFiles(Collection<String> files,
                                                      List<String> internal,
                                                      List<String> external) {

        if (files == null) {
            return;
        }
        for (String file : files) {
            Path p = PathUtil.CWD.resolve(file).toAbsolutePath();
            if (p.startsWith(PathUtil.CWD)) {
                internal.add(PathUtil.relativizeCwd(p));
            } else {
                external.add(PathUtil.normalize(file));
            }
        }
        internal.sort(String::compareTo);
        external.sort(String::compareTo);
    }
}
