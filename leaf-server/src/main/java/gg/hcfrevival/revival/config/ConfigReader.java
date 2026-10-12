package gg.hcfrevival.revival.config;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * Reads revival.yml values and records each default, so a missing key is written back on save.
 * <p>
 * A value of the wrong type (for example {@code enabled: yes please}) falls back to the default and is
 * recorded in {@link #problems()}, so a typo is reported instead of silently changing behaviour. The
 * file keeps the owner's value so they can see and fix it.
 * <p>
 * Per-world values resolve as {@code world-settings.<world>.<path>}, then
 * {@code world-settings.default.<path>}, then the given default. Only the {@code default} section is
 * written back; per-world sections are left as the owner wrote them.
 */
public final class ConfigReader {

    public static final String WORLD_SETTINGS = "world-settings";
    public static final String DEFAULT_WORLD = "default";

    private final YamlConfiguration config;
    private final List<String> problems = new ArrayList<>();

    public ConfigReader(final YamlConfiguration config) {
        this.config = config;
    }

    public YamlConfiguration config() {
        return this.config;
    }

    /** Values that had the wrong type and were replaced by their default, one message each. */
    public List<String> problems() {
        return List.copyOf(this.problems);
    }

    public boolean getBoolean(final String path, final boolean def) {
        this.config.addDefault(path, def);
        return this.readBoolean(path, def);
    }

    public int getInt(final String path, final int def) {
        this.config.addDefault(path, def);
        return this.readInt(path, def);
    }

    public double getDouble(final String path, final double def) {
        this.config.addDefault(path, def);
        return this.readDouble(path, def);
    }

    public String getString(final String path, final String def) {
        this.config.addDefault(path, def);
        return this.readString(path, def);
    }

    public List<String> getStringList(final String path, final List<String> def) {
        this.config.addDefault(path, def);
        final Object value = this.raw(path);
        if (value == null) {
            return List.copyOf(def);
        }
        if (value instanceof final List<?> list) {
            final List<String> strings = new ArrayList<>(list.size());
            for (final Object element : list) {
                if (element != null) {
                    strings.add(element.toString());
                }
            }
            return List.copyOf(strings);
        }
        this.problem(path, value, "a list", def);
        return List.copyOf(def);
    }

    public void set(final String path, final Object value) {
        this.config.addDefault(path, value);
        this.config.set(path, value);
    }

    public boolean getWorldBoolean(final String world, final String path, final boolean def) {
        final boolean fallback = this.getBoolean(worldPath(DEFAULT_WORLD, path), def);
        return DEFAULT_WORLD.equals(world) ? fallback : this.readBoolean(worldPath(world, path), fallback);
    }

    public int getWorldInt(final String world, final String path, final int def) {
        final int fallback = this.getInt(worldPath(DEFAULT_WORLD, path), def);
        return DEFAULT_WORLD.equals(world) ? fallback : this.readInt(worldPath(world, path), fallback);
    }

    public double getWorldDouble(final String world, final String path, final double def) {
        final double fallback = this.getDouble(worldPath(DEFAULT_WORLD, path), def);
        return DEFAULT_WORLD.equals(world) ? fallback : this.readDouble(worldPath(world, path), fallback);
    }

    public String getWorldString(final String world, final String path, final String def) {
        final String fallback = this.getString(worldPath(DEFAULT_WORLD, path), def);
        return DEFAULT_WORLD.equals(world) ? fallback : this.readString(worldPath(world, path), fallback);
    }

    public static String worldPath(final String world, final String path) {
        return WORLD_SETTINGS + "." + world + "." + path;
    }

    private boolean readBoolean(final String path, final boolean fallback) {
        final Object value = this.raw(path);
        if (value == null) {
            return fallback;
        }
        if (value instanceof final Boolean bool) {
            return bool;
        }
        this.problem(path, value, "true or false", fallback);
        return fallback;
    }

    private int readInt(final String path, final int fallback) {
        final Object value = this.raw(path);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).intValue();
        }
        this.problem(path, value, "a whole number", fallback);
        return fallback;
    }

    private double readDouble(final String path, final double fallback) {
        final Object value = this.raw(path);
        if (value == null) {
            return fallback;
        }
        if (value instanceof final Number number) {
            return number.doubleValue();
        }
        this.problem(path, value, "a number", fallback);
        return fallback;
    }

    private String readString(final String path, final String fallback) {
        final Object value = this.raw(path);
        if (value == null) {
            return fallback;
        }
        // YAML reads `profile: 1.7` as a number and `profile: on` as a boolean; both are fine as text
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        this.problem(path, value, "text", fallback);
        return fallback;
    }

    // The value written in the file, ignoring registered defaults
    private @Nullable Object raw(final String path) {
        return this.config.get(path, null);
    }

    private void problem(final String path, final Object value, final String expected, final Object fallback) {
        this.problems.add("revival.yml: " + path + " is '" + value + "', expected " + expected + "; using " + fallback);
    }
}
