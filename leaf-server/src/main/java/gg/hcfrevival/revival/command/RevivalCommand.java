package gg.hcfrevival.revival.command;

import gg.hcfrevival.revival.config.RevivalConfig;
import io.papermc.paper.ServerBuildInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.StringUtil;
import org.jspecify.annotations.Nullable;

/** {@code /revival reload | version | kb ...}. */
public final class RevivalCommand extends Command {

    public static final String LABEL = "revival";
    public static final String PERMISSION = "revival.command";
    public static final String RELOAD_PERMISSION = PERMISSION + ".reload";
    public static final String VERSION_PERMISSION = PERMISSION + ".version";
    public static final String KNOCKBACK_PERMISSION = PERMISSION + ".knockback";

    private static final String RELOAD = "reload";
    private static final String VERSION = "version";

    public RevivalCommand() {
        super(LABEL);
        this.description = "RevivalPaper commands";
        this.usageMessage = "/" + LABEL + " [" + RELOAD + " | " + VERSION + " | " + KnockbackCommand.LABEL + "]";
        this.setPermission(PERMISSION);
    }

    /** Registers the command permissions once; safe to call again after {@code /reload}. */
    public static void registerPermissions() {
        final PluginManager pluginManager = Bukkit.getPluginManager();
        addPermission(pluginManager, new Permission(RELOAD_PERMISSION, "Reload revival.yml", PermissionDefault.OP));
        addPermission(pluginManager, new Permission(VERSION_PERMISSION, "Show the RevivalPaper build", PermissionDefault.OP));
        addPermission(pluginManager, new Permission(KNOCKBACK_PERMISSION, "Edit and assign knockback profiles", PermissionDefault.OP));
        addPermission(pluginManager, new Permission(PERMISSION, "Use /revival", PermissionDefault.OP, Map.of(RELOAD_PERMISSION, true, VERSION_PERMISSION, true, KNOCKBACK_PERMISSION, true)));
    }

    private static void addPermission(final PluginManager pluginManager, final Permission permission) {
        if (pluginManager.getPermission(permission.getName()) == null) {
            pluginManager.addPermission(permission);
        }
    }

    @Override
    public boolean execute(final CommandSender sender, final String commandLabel, final String[] args) {
        if (!this.testPermission(sender)) {
            return true;
        }
        final String subcommand = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case RELOAD -> this.reload(sender);
            case VERSION, "ver" -> this.version(sender);
            case KnockbackCommand.LABEL, "knockback" -> {
                if (sender.hasPermission(KNOCKBACK_PERMISSION)) {
                    KnockbackCommand.execute(sender, args);
                } else {
                    sender.sendMessage(Bukkit.permissionMessage());
                }
            }
            default -> sender.sendMessage(Component.text("Usage: " + this.usageMessage, NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args, final @Nullable Location location) {
        if (args.length == 0 || !this.testPermissionSilent(sender)) {
            return List.of();
        }
        if (args.length > 1) {
            return args[0].equalsIgnoreCase(KnockbackCommand.LABEL) && sender.hasPermission(KNOCKBACK_PERMISSION) ? KnockbackCommand.tabComplete(args) : List.of();
        }
        final List<String> options = new ArrayList<>(3);
        if (sender.hasPermission(RELOAD_PERMISSION)) {
            options.add(RELOAD);
        }
        if (sender.hasPermission(VERSION_PERMISSION)) {
            options.add(VERSION);
        }
        if (sender.hasPermission(KNOCKBACK_PERMISSION)) {
            options.add(KnockbackCommand.LABEL);
        }
        return StringUtil.copyPartialMatches(args[0], options, new ArrayList<>(options.size()));
    }

    private void reload(final CommandSender sender) {
        if (!sender.hasPermission(RELOAD_PERMISSION)) {
            sender.sendMessage(Bukkit.permissionMessage());
            return;
        }
        Command.broadcastCommandMessage(sender, Component.text("Reloading revival.yml...", NamedTextColor.GREEN));
        final RevivalConfig.Reload result = RevivalConfig.reload();
        if (!result.loaded()) {
            Command.broadcastCommandMessage(sender, Component.text("revival.yml could not be loaded, so the previous settings are still live. See the console.", NamedTextColor.RED));
        } else if (!result.problems().isEmpty()) {
            Command.broadcastCommandMessage(sender, Component.text("revival.yml reloaded, but " + result.problems().size() + " value(s) had the wrong type and use their defaults:", NamedTextColor.YELLOW));
            for (final String problem : result.problems()) {
                sender.sendMessage(Component.text(" - " + problem, NamedTextColor.YELLOW));
            }
        } else {
            Command.broadcastCommandMessage(sender, Component.text("revival.yml reloaded.", NamedTextColor.GREEN));
        }
    }

    private void version(final CommandSender sender) {
        if (!sender.hasPermission(VERSION_PERMISSION)) {
            sender.sendMessage(Bukkit.permissionMessage());
            return;
        }
        final ServerBuildInfo build = ServerBuildInfo.buildInfo();
        sender.sendMessage(Component.text(
            build.brandName() + " " + build.asString(ServerBuildInfo.StringRepresentation.VERSION_FULL)
                + " (revival.yml config-version " + RevivalConfig.settings().configVersion() + ")",
            NamedTextColor.GRAY
        ));
    }
}
