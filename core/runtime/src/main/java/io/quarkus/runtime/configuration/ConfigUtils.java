package io.quarkus.runtime.configuration;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;
import org.jboss.logging.Logger;

import io.smallrye.config.PropertiesConfigSourceProvider;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 *
 */
public final class ConfigUtils {
    private static final Logger log = Logger.getLogger("io.quarkus.config");

    private ConfigUtils() {
    }

    public static <T> IntFunction<List<T>> listFactory() {
        return ArrayList::new;
    }

    public static <T> IntFunction<Set<T>> setFactory() {
        return LinkedHashSet::new;
    }

    public static <T> IntFunction<SortedSet<T>> sortedSetFactory() {
        return size -> new TreeSet<>();
    }

    /**
     * Get the basic configuration builder.
     *
     * @param runTime {@code true} if the configuration is run time, {@code false} if build time
     * @return the configuration builder
     */
    public static SmallRyeConfigBuilder configBuilder(final boolean runTime) {
        final SmallRyeConfigBuilder builder = new SmallRyeConfigBuilder();
        final ApplicationPropertiesConfigSource.InFileSystem inFileSystem = new ApplicationPropertiesConfigSource.InFileSystem();
        final ApplicationPropertiesConfigSource.InJar inJar = new ApplicationPropertiesConfigSource.InJar();
        final ApplicationPropertiesConfigSource.MpConfigInJar mpConfig = new ApplicationPropertiesConfigSource.MpConfigInJar();
        builder.withSources(inFileSystem, inJar, mpConfig);
        final ExpandingConfigSource.Cache cache = new ExpandingConfigSource.Cache();
        builder.withWrapper(ExpandingConfigSource.wrapper(cache));
        builder.withWrapper(DeploymentProfileConfigSource.wrapper());
        final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (runTime) {
            builder.addDefaultSources();
        } else {
            final List<ConfigSource> sources = new ArrayList<>();
            sources.addAll(new PropertiesConfigSourceProvider("META-INF/microprofile-config.properties", true, classLoader)
                    .getConfigSources(classLoader));
            // required by spec...
            sources.addAll(
                    new PropertiesConfigSourceProvider("WEB-INF/classes/META-INF/microprofile-config.properties", true,
                            classLoader).getConfigSources(classLoader));
            sources.add(new DotEnvConfigSource());
            sources.add(new EnvConfigSource());
            sources.add(new SysPropConfigSource());
            builder.withSources(sources.toArray(new ConfigSource[0]));
        }
        builder.addDiscoveredSources();
        builder.addDiscoveredConverters();
        return builder;
    }

    /**
     * Add a configuration source provider to the builder.
     *
     * @param builder the builder
     * @param provider the provider to add
     */
    public static void addSourceProvider(SmallRyeConfigBuilder builder, ConfigSourceProvider provider) {
        final Iterable<ConfigSource> sources = provider.getConfigSources(Thread.currentThread().getContextClassLoader());
        for (ConfigSource source : sources) {
            builder.withSources(source);
        }
    }

    static class EnvConfigSource implements ConfigSource {
        static final Pattern REP_PATTERN = Pattern.compile("[^a-zA-Z0-9_]");

        EnvConfigSource() {
        }

        public int getOrdinal() {
            return 300;
        }

        public Map<String, String> getProperties() {
            return Collections.emptyMap();
        }

        public String getValue(final String propertyName) {
            return getRawValue(REP_PATTERN.matcher(propertyName.toUpperCase(Locale.ROOT)).replaceAll("_"));
        }

        String getRawValue(final String name) {
            return System.getenv(name);
        }

        public String getName() {
            return "System environment";
        }
    }

    static class DotEnvConfigSource extends EnvConfigSource {
        private final Map<String, String> values;

        DotEnvConfigSource() {
            this(Paths.get(System.getProperty("user.dir", "."), ".env"));
        }

        DotEnvConfigSource(Path path) {
            Map<String, String> values = new HashMap<>();
            try (InputStream is = Files.newInputStream(path)) {
                try (InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    final Properties properties = new Properties();
                    properties.load(isr);
                    for (String name : properties.stringPropertyNames()) {
                        values.put(name, properties.getProperty(name));
                    }
                }
            } catch (FileNotFoundException | NoSuchFileException ignored) {
            } catch (IOException e) {
                log.debug("Failed to load `.env` file", e);
            }
            this.values = values;
        }

        public int getOrdinal() {
            return 295;
        }

        String getRawValue(final String name) {
            return values.get(name);
        }

        public String getName() {
            return ".env";
        }
    }

    static final class SysPropConfigSource implements ConfigSource {
        public Map<String, String> getProperties() {
            Map<String, String> output = new TreeMap<>();
            for (Map.Entry<Object, Object> entry : System.getProperties().entrySet()) {
                String key = (String) entry.getKey();
                if (key.startsWith("quarkus.")) {
                    output.put(key, entry.getValue().toString());
                }
            }
            return output;
        }

        public String getValue(final String propertyName) {
            return System.getProperty(propertyName);
        }

        public String getName() {
            return "System properties";
        }

        public int getOrdinal() {
            return 400;
        }
    }
}
