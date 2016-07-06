# Fossa Config

The fossa configuration file allows to you to change how the scanners analyze your source code.

## Configuration

By default, srclib-java looks for `.fossaconfig` in the repository.
This can be changed via the **FOSSA_CONFIG_FILE** environment variable.

### profiles

The profiles configuration enables you to choose which maven profile to use for analysis.
This will your dependency and licenses list.

**Example**: `{"profiles": ["hadoop-1"]}`

**Support**: Maven only
