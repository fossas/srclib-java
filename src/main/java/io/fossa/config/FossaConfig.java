package io.fossa.config;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

public class FossaConfig {

    private List<String> profiles = new ArrayList<String>();

    private static final Logger LOGGER = LoggerFactory.getLogger(FossaConfig.class);
    private static FossaConfig instance;

    public static final String FILENAME = ".fossaconfig";

    public static final String CONFIG_PROFILES = "profiles";


    public FossaConfig(JSONObject config) {
        extrapolateConfig(config);
    }

    private void extrapolateConfig(JSONObject config) {
        if (config.has(CONFIG_PROFILES)) {
            JSONArray profiles = config.getJSONArray(CONFIG_PROFILES);
            for (int i = 0; i < profiles.length(); i++) {
                this.profiles.add(profiles.getString(i));
            }
        }
    }

    /**
     * Main method
     */
    public static FossaConfig getFossaConfig() {
        if (instance == null) {
            String filename = System.getenv("FOSSA_CONFIG_FILE");
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
                LOGGER.warn("Could not parse Fossa Config.", e);
                result = new JSONObject();
            }

            instance = new FossaConfig(result);
        }

        return instance;
    }

    public List<String> getProfiles() {
        return profiles;
    }
}
