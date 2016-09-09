package io.fossa.config;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.repository.Authentication;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FossaConfig {

    private List<String> profiles = new ArrayList<String>();
    private String gradleBuildFile = null;
    private List<MavenRepositoryConfiguration> mavenRepositories = new ArrayList<MavenRepositoryConfiguration>();
    private List<MavenServerConfiguration> mavenServers = new ArrayList<MavenServerConfiguration>();

    private static final Logger LOGGER = LoggerFactory.getLogger(FossaConfig.class);
    private static FossaConfig instance;

    public static final String FILENAME = ".fossaconfig";

    // File config
    // public static final String CONFIG_PROFILES = "profiles";

    // Env vars
    public static final String CONFIG_FILENAME = "FOSSA_CONFIG_FILE";
    public static final String CONFIG_GRADLE_BUILD_FILE = "FOSSA_GRADLE_BUILD_FILE";
    public static final String CONFIG_MAVEN_SETTINGS = "FOSSA_MAVEN_SETTINGS";
    public static final String CONFIG_MAVEN_PROFILES = "FOSSA_MAVEN_PROFILES";

    // Keys
    public static final String KEY_MAVEN_SETTINGS_SERVERS = "servers";
    public static final String KEY_MAVEN_SETTINGS_REPOSITORIES = "repositories";


    public FossaConfig(JSONObject config) {
        // extrapolateConfig(config);
        assignEnvVars(System.getenv());
    }

    // private void extrapolateConfig(JSONObject config) {
    //     if (config.has(CONFIG_PROFILES)) {
    //         JSONArray profiles = config.getJSONArray(CONFIG_PROFILES);
    //         for (int i = 0; i < profiles.length(); i++) {
    //             this.profiles.add(profiles.getString(i));
    //         }
    //     }
    // }

    private void assignEnvVars(Map<String, String> envvars) {
        if (envvars.containsKey(CONFIG_GRADLE_BUILD_FILE) && !StringUtils.isEmpty(envvars.get(CONFIG_GRADLE_BUILD_FILE))) {
            this.gradleBuildFile = envvars.get(CONFIG_GRADLE_BUILD_FILE);
        }

        if (envvars.containsKey(CONFIG_MAVEN_PROFILES) && !StringUtils.isEmpty(envvars.get(CONFIG_MAVEN_PROFILES))) {
            JSONArray result = new JSONArray(envvars.get(CONFIG_MAVEN_PROFILES));
            for (int i = 0; i < result.length(); i++) {
                this.profiles.add(result.getString(i));
            }
        }

        if (envvars.containsKey(CONFIG_MAVEN_SETTINGS) && !StringUtils.isEmpty(envvars.get(CONFIG_MAVEN_SETTINGS))) {
            try {
                JSONObject result = new JSONObject(envvars.get(CONFIG_MAVEN_SETTINGS));

                // 1. Find servers
                if (result.has(KEY_MAVEN_SETTINGS_SERVERS)) {
                    JSONArray serversArray = result.getJSONArray(KEY_MAVEN_SETTINGS_SERVERS);
                    for (int i = 0; i < serversArray.length(); i++) {
                        mavenServers.add(new MavenServerConfiguration(serversArray.getJSONObject(i)));
                    }
                }

                // 2. Find repositories
                if (result.has(KEY_MAVEN_SETTINGS_REPOSITORIES)) {
                    JSONArray repositoriesArray = result.getJSONArray(KEY_MAVEN_SETTINGS_REPOSITORIES);
                    for (int i = 0; i < repositoriesArray.length(); i++) {
                        mavenRepositories.add(new MavenRepositoryConfiguration(repositoriesArray.getJSONObject(i)));
                    }
                }

                // @TODO(Abe): 3. Do fancy mirror handling if asked by customers.
            } catch (Exception e) {
                LOGGER.warn("Could not parse maven settings", e);
            }
        }
    }

    /**
     * Main method
     */
    public static FossaConfig getFossaConfig() {
        if (instance == null) {
            String filename = System.getenv(CONFIG_FILENAME);
            if (filename == null) {
                filename = FILENAME;
            }

            JSONObject result;
            try {
                File file = new File(System.getProperty("user.dir"), filename);
                FileInputStream fis = new FileInputStream(file);
                byte[] data = new byte[(int) file.length()];
                fis.read(data);
                fis.close();

                String jsonString = new String(data, "UTF-8");
                result = new JSONObject(jsonString);
            } catch (Exception e) {
                // LOGGER.warn("Could not parse Fossa Config.", e);
                result = new JSONObject();
            }

            instance = new FossaConfig(result);
        }

        return instance;
    }

    public List<String> getProfiles() {
        return profiles;
    }

    public String getGradleBuildFile() {
        return gradleBuildFile;
    }

    public List<MavenRepositoryConfiguration> getMavenRepositories() {
        return mavenRepositories;
    }

    public List<MavenServerConfiguration> getMavenServers() {
        return mavenServers;
    }

    public List<ArtifactRepository> getMavenArtifactRepositories() {
        List<ArtifactRepository> remotes = new ArrayList<ArtifactRepository>();
        for (MavenRepositoryConfiguration repository : mavenRepositories) {
            MavenArtifactRepository remote = new MavenArtifactRepository(repository.getId(), repository.getUrl(),
                    new DefaultRepositoryLayout(), new ArtifactRepositoryPolicy(), new ArtifactRepositoryPolicy());
            for (MavenServerConfiguration server : mavenServers) {
                if (server.getId().equals(repository.getId())) {
                    remote.setAuthentication(new Authentication(server.getUsername(), server.getPassword()));
                }
            }
            remotes.add((ArtifactRepository)remote);
        }
        return remotes;
    }
}
