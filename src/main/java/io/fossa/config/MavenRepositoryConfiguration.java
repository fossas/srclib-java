package io.fossa.config;

import org.json.JSONObject;

// @TODO(Abe): Intended to be like: http://download.eclipse.org/aether/aether-core/1.0.1/apidocs/org/eclipse/aether/repository/RemoteRepository.html
public class MavenRepositoryConfiguration {
    public static final String KEY_ID = "id";
    public static final String KEY_URL = "url";

    private String id;
    private String url;

    public MavenRepositoryConfiguration(JSONObject repositoryObject) {
        if (repositoryObject.has(KEY_ID)) {
            id = repositoryObject.getString(KEY_ID);
        }

        if (repositoryObject.has(KEY_URL)) {
            url = repositoryObject.getString(KEY_URL);
        }
    }

    public String getId() {
        return id;
    }

    public String getUrl() {
        return url;
    }
}
