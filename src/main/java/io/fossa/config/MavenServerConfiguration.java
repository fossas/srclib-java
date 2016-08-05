package io.fossa.config;

import org.json.JSONObject;

// @TODO(Abe): Add all of https://maven.apache.org/settings.html#Servers
public class MavenServerConfiguration {
    public static final String KEY_ID = "id";
    public static final String KEY_USERNAME = "username";
    public static final String KEY_PASSWORD = "password";

    private String id;
    private String username;
    private String password;

    public MavenServerConfiguration(JSONObject serverObject) {
        if (serverObject.has(KEY_ID)) {
            id = serverObject.getString(KEY_ID);
        }

        if (serverObject.has(KEY_USERNAME)) {
            username = serverObject.getString(KEY_USERNAME);
        }

        if (serverObject.has(KEY_PASSWORD)) {
            password = serverObject.getString(KEY_PASSWORD);
        }
    }

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }
}
