package gg.hcfrevival.revival.command;

import gg.hcfrevival.revival.Revival;
import gg.hcfrevival.revival.combat.LegacyKnockback;
import gg.hcfrevival.revival.config.ConfigReader;
import gg.hcfrevival.revival.config.KnockbackProfile;
import gg.hcfrevival.revival.config.RevivalConfig;
import gg.hcfrevival.revival.config.RevivalSettings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

/**
 * {@code /revival kb list | show | set | create | delete | use | reset | save | debug}: edits knockback profiles live.
 * Every edit builds a new settings snapshot and swaps it in whole, so a hit never sees half an edit. Edits are kept
 * until {@code save} (or at once with {@code knockback-autosave}); {@code /revival reload} drops unsaved ones.
 */
final class KnockbackCommand {

    static final String LABEL = "kb";
    static final String USAGE = "/revival kb <list | show | set | create | delete | use | reset | save | debug>";

    private static final List<String> SUBCOMMANDS = List.of("list", "show", "set", "create", "delete", "use", "reset", "save", "debug");
    private static final String NONE = "none";
    private static final Pattern NAME = Pattern.compile("[a-z0-9._-]{1,32}");

    private KnockbackCommand() {
    }

    static void execute(final CommandSender sender, final String[] args) {
        final String subcommand = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        try {
            switch (subcommand) {
                case "list" -> list(sender);
                case "show" -> show(sender, profileArg(args, 2));
                case "set" -> set(sender, args);
                case "create" -> create(sender, args);
                case "delete" -> delete(sender, profileArg(args, 2));
                case "use" -> use(sender, args);
                case "reset" -> reset(sender, profileArg(args, 2));
                case "save" -> save(sender);
                case "debug" -> debug(sender);
                default -> error(sender, "Usage: " + USAGE);
            }
        } catch (final IllegalArgumentException e) {
            error(sender, e.getMessage());
        }
    }

    static List<String> tabComplete(final String[] args) {
        final List<String> options = switch (args.length) {
            case 2 -> SUBCOMMANDS;
            case 3 -> switch (args[1].toLowerCase(Locale.ROOT)) {
                case "show", "set", "delete", "reset" -> profileNames();
                case "use" -> withNone(profileNames());
                default -> List.of();
            };
            case 4 -> switch (args[1].toLowerCase(Locale.ROOT)) {
                case "set" -> KnockbackProfile.KEYS;
                case "create" -> profileNames();
                case "use" -> List.of("world", "player");
                default -> List.of();
            };
            case 5 -> args[1].equalsIgnoreCase("use") && args[3].equalsIgnoreCase("world") ? worldNames()
                : args[1].equalsIgnoreCase("use") && args[3].equalsIgnoreCase("player") ? playerNames() : List.of();
            default -> List.of();
        };
        return StringUtil.copyPartialMatches(args[args.length - 1], options, new ArrayList<>(options.size()));
    }

    private static void list(final CommandSender sender) {
        final RevivalSettings settings = Revival.settings();
        sender.sendMessage(Component.text("Knockback profiles (server default: " + settings.legacyCombat().knockbackProfileName() + "):", NamedTextColor.GOLD));
        settings.knockbackProfiles().forEach((name, profile) -> {
            final KnockbackProfile preset = KnockbackProfile.PRESETS.get(name);
            final String kind = preset == null ? "custom" : preset.equals(profile) ? "preset" : "preset, edited";
            final List<String> worlds = worldsUsing(settings, name);
            sender.sendMessage(Component.text(" - " + name + " (" + kind + ")" + (worlds.isEmpty() ? "" : " used by " + String.join(", ", worlds)), NamedTextColor.GRAY));
        });
    }

    private static void show(final CommandSender sender, final String name) {
        final KnockbackProfile profile = existing(name);
        final KnockbackProfile preset = KnockbackProfile.PRESETS.get(name);
        sender.sendMessage(Component.text("Knockback profile " + name + ":", NamedTextColor.GOLD));
        final Map<String, Object> presetValues = preset == null ? Map.of() : preset.values();
        profile.values().forEach((key, value) -> {
            final Object builtIn = presetValues.get(key);
            sender.sendMessage(Component.text(" " + key + ": " + value + (builtIn != null && !builtIn.equals(value) ? " (preset " + builtIn + ")" : ""), NamedTextColor.GRAY));
        });
    }

    private static void set(final CommandSender sender, final String[] args) {
        if (args.length < 5) {
            throw new IllegalArgumentException("Usage: /revival kb set <profile> <key> <value>");
        }
        final String name = args[2];
        final KnockbackProfile updated = existing(name).with(args[3].toLowerCase(Locale.ROOT), args[4]);
        edit(sender, settings -> settings.withProfile(name, updated), "Set " + args[3] + " of knockback profile " + name + " to " + args[4] + ".");
    }

    private static void create(final CommandSender sender, final String[] args) {
        final String name = profileArg(args, 2).toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches() || name.equals(NONE)) {
            throw new IllegalArgumentException("Profile names use a-z, 0-9, '.', '_' and '-', up to 32 characters, and can't be '" + NONE + "'.");
        }
        if (Revival.settings().knockbackProfile(name) != null) {
            throw new IllegalArgumentException("There is already a knockback profile named " + name + ".");
        }
        final String from = args.length > 3 ? args[3] : KnockbackProfile.DEFAULT;
        final KnockbackProfile base = existing(from);
        edit(sender, settings -> settings.withProfile(name, base), "Created knockback profile " + name + " from " + from + ".");
    }

    private static void delete(final CommandSender sender, final String name) {
        existing(name);
        if (KnockbackProfile.PRESETS.containsKey(name)) {
            throw new IllegalArgumentException(name + " is a built-in preset; use '/revival kb reset " + name + "' to undo edits.");
        }
        final List<String> users = worldsUsing(Revival.settings(), name);
        if (!users.isEmpty()) {
            throw new IllegalArgumentException(name + " is used by " + String.join(", ", users) + "; give them another profile first.");
        }
        edit(sender, settings -> settings.withProfile(name, null), "Deleted knockback profile " + name + ". Players who had it use their world's profile.");
    }

    private static void use(final CommandSender sender, final String[] args) {
        final String name = profileArg(args, 2);
        final String target = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "";
        switch (target) {
            case "" -> {
                existing(name);
                edit(sender, settings -> settings.withWorldProfile(ConfigReader.DEFAULT_WORLD, name), "Every world without its own profile now uses " + name + ".");
            }
            case "world" -> {
                final String world = argument(args, 4, "Usage: /revival kb use <profile> world <world>");
                existing(name);
                if (Bukkit.getWorld(world) == null) {
                    sender.sendMessage(Component.text("No world named " + world + " is loaded; the profile applies when it is.", NamedTextColor.YELLOW));
                }
                edit(sender, settings -> settings.withWorldProfile(world, name), "World " + world + " now uses knockback profile " + name + ".");
            }
            case "player" -> {
                final Player player = Bukkit.getPlayerExact(argument(args, 4, "Usage: /revival kb use <profile | " + NONE + "> player <player>"));
                if (player == null) {
                    throw new IllegalArgumentException("No player named " + args[4] + " is online.");
                }
                if (name.equalsIgnoreCase(NONE)) {
                    ((CraftPlayer) player).getHandle().revivalKnockbackProfile = null;
                    Command.broadcastCommandMessage(sender, Component.text(player.getName() + "'s legacy hits use their world's knockback profile again.", NamedTextColor.GREEN));
                } else {
                    existing(name);
                    ((CraftPlayer) player).getHandle().revivalKnockbackProfile = name;
                    Command.broadcastCommandMessage(sender, Component.text(player.getName() + "'s legacy hits now use knockback profile " + name + " until it is cleared or they leave.", NamedTextColor.GREEN));
                }
            }
            default -> throw new IllegalArgumentException("Usage: /revival kb use <profile> [world <world> | player <player>]");
        }
    }

    private static void reset(final CommandSender sender, final String name) {
        existing(name);
        final KnockbackProfile original = KnockbackProfile.PRESETS.getOrDefault(name, KnockbackProfile.LEGACY_1_7);
        edit(sender, settings -> settings.withProfile(name, original), "Reset knockback profile " + name + " to " + (KnockbackProfile.PRESETS.containsKey(name) ? "its built-in values." : "legacy-1.7's values."));
    }

    private static void save(final CommandSender sender) {
        try {
            RevivalConfig.saveKnockback();
            Command.broadcastCommandMessage(sender, Component.text("Saved knockback profiles to revival.yml.", NamedTextColor.GREEN));
        } catch (final IOException e) {
            error(sender, "Couldn't save revival.yml: " + e.getMessage());
        }
    }

    private static void debug(final CommandSender sender) {
        if (!(sender instanceof final Player player)) {
            throw new IllegalArgumentException("Only players can see knockback debug lines.");
        }
        if (LegacyKnockback.DEBUG.remove(player.getUniqueId())) {
            sender.sendMessage(Component.text("Knockback debug off.", NamedTextColor.GRAY));
        } else {
            LegacyKnockback.DEBUG.add(player.getUniqueId());
            sender.sendMessage(Component.text("Knockback debug on: a line for every legacy knock you deal or take.", NamedTextColor.GRAY));
        }
    }

    private static void edit(final CommandSender sender, final UnaryOperator<RevivalSettings> change, final String done) {
        try {
            RevivalConfig.update(change);
            final boolean saved = Revival.settings().knockbackAutosave();
            Command.broadcastCommandMessage(sender, Component.text(done + (saved ? " Saved." : " Use '/revival kb save' to keep it."), NamedTextColor.GREEN));
        } catch (final IOException e) {
            error(sender, "The change is live, but saving revival.yml failed: " + e.getMessage());
        }
    }

    static List<String> worldsUsing(final RevivalSettings settings, final String name) {
        final List<String> users = new ArrayList<>();
        if (settings.legacyCombat().knockbackProfileName().equals(name)) {
            users.add(ConfigReader.DEFAULT_WORLD);
        }
        settings.worldLegacyCombat().forEach((world, worldSettings) -> {
            if (worldSettings.knockbackProfileName().equals(name)) {
                users.add(world);
            }
        });
        return users;
    }

    private static KnockbackProfile existing(final String name) {
        final KnockbackProfile profile = Revival.settings().knockbackProfile(name);
        if (profile == null) {
            throw new IllegalArgumentException("No knockback profile named " + name + ". See '/revival kb list'.");
        }
        return profile;
    }

    private static String profileArg(final String[] args, final int index) {
        return argument(args, index, "Name a knockback profile. See '/revival kb list'.");
    }

    private static String argument(final String[] args, final int index, final String missing) {
        if (args.length <= index) {
            throw new IllegalArgumentException(missing);
        }
        return args[index];
    }

    private static void error(final CommandSender sender, final String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    private static List<String> profileNames() {
        return List.copyOf(Revival.settings().knockbackProfiles().keySet());
    }

    private static List<String> withNone(final List<String> names) {
        final List<String> all = new ArrayList<>(names);
        all.add(NONE);
        return all;
    }

    private static List<String> worldNames() {
        return Bukkit.getWorlds().stream().map(World::getName).toList();
    }

    private static List<String> playerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }
}
