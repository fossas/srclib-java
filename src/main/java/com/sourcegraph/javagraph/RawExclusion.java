package com.sourcegraph.javagraph;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * A Raw, exclude.
 * @See: https://maven.apache.org/guides/introduction/introduction-to-optional-and-excludes-dependencies.html
 * @See: https://docs.gradle.org/current/dsl/org.gradle.api.artifacts.dsl.DependencyHandler.html
 * @See: https://ant.apache.org/ivy/history/latest-milestone/ivyfile/artifact-exclude.html
 */
public class RawExclusion {

    /**
     * Artifact group ID
     */
    String groupID;
    /**
     * Artifact ID
     */
    String artifactID;

    public RawExclusion(String groupID, String artifactID) {
        this.groupID = groupID;
        this.artifactID = artifactID;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        RawExclusion that = (RawExclusion) o;

        if (!StringUtils.equals(artifactID, that.artifactID)) {
            return false;
        }
        if (!StringUtils.equals(groupID, that.groupID)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = groupID != null ? groupID.hashCode() : 0;
        result = 31 * result + (artifactID != null ? artifactID.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "RawExclusion{" +
                "groupID='" + groupID + '\'' +
                ", artifactID='" + artifactID + '\'' +
                '}';
    }
}
