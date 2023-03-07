package net.william278.huskhomes.command;

import de.themoep.minedown.adventure.MineDown;
import net.william278.desertwell.AboutMenu;
import net.william278.huskhomes.HuskHomes;
import net.william278.huskhomes.user.OnlineUser;
import net.william278.huskhomes.util.Permission;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class HuskHomesCommand extends Command implements ConsoleExecutable, TabProvider {

    private final String[] SUB_COMMANDS = {"about", "help", "reload", "update"};
    private final AboutMenu aboutMenu;

    protected HuskHomesCommand(@NotNull HuskHomes implementor) {
        super("huskhomes", Permission.COMMAND_HUSKHOMES, implementor);
        this.aboutMenu = AboutMenu.create("HuskHomes")
                .withDescription("A powerful, intuitive and flexible teleportation suite")
                .withVersion(implementor.getVersion())
                .addAttribution("Author",
                        AboutMenu.Credit.of("William278").withDescription("Click to visit website").withUrl("https://william278.net"))
                .addAttribution("Contributors",
                        AboutMenu.Credit.of("imDaniX").withDescription("Code, refactoring"),
                        AboutMenu.Credit.of("Log1x").withDescription("Code"))
                .addAttribution("Translators",
                        AboutMenu.Credit.of("SnivyJ").withDescription("Simplified Chinese (zh-cn)"),
                        AboutMenu.Credit.of("ApliNi").withDescription("Simplified Chinese (zh-cn)"),
                        AboutMenu.Credit.of("TonyPak").withDescription("Traditional Chinese (zh-tw)"),
                        AboutMenu.Credit.of("davgo0103").withDescription("Traditional Chinese (zh-tw)"),
                        AboutMenu.Credit.of("Villag3r_").withDescription("Italian (it-it)"),
                        AboutMenu.Credit.of("ReferTV").withDescription("Polish (pl)"),
                        AboutMenu.Credit.of("anchelthe").withDescription("Spanish (es-es)"),
                        AboutMenu.Credit.of("Chiquis2005").withDescription("Spanish (es-es)"),
                        AboutMenu.Credit.of("Ceddix").withDescription("German, (de-de)"),
                        AboutMenu.Credit.of("Pukejoy_1").withDescription("Bulgarian (bg-bg)"))
                .addButtons(
                        AboutMenu.Link.of("https://william278.net/docs/huskhomes").withText("Documentation").withIcon("⛏"),
                        AboutMenu.Link.of("https://github.com/WiIIiam278/HuskHomes2/issues").withText("Issues").withIcon("❌").withColor("#ff9f0f"),
                        AboutMenu.Link.of("https://discord.gg/tVYhJfyDWG").withText("Discord").withIcon("⭐").withColor("#6773f5"));
    }

    @Override
    public void onExecute(@NotNull OnlineUser onlineUser, @NotNull String[] args) {
        if (args.length == 0) {
            sendAboutMenu(onlineUser);
            return;
        }
        if (args.length > 2) {
            plugin.getLocales().getLocale("error_invalid_syntax", "/huskhomes [about|help|reload|update]")
                    .ifPresent(onlineUser::sendMessage);
            return;
        }
        switch (args[0].toLowerCase()) {
            case "about" -> sendAboutMenu(onlineUser);
            case "help" -> {
                if (!onlineUser.hasPermission(Permission.COMMAND_HUSKHOMES_HELP.node)) {
                    plugin.getLocales().getLocale("error_no_permission")
                            .ifPresent(onlineUser::sendMessage);
                    return;
                }
                int page = 1;
                if (args.length == 2) {
                    try {
                        page = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ignored) {
                        plugin.getLocales().getLocale("error_invalid_syntax", "/huskhomes help <page>")
                                .ifPresent(onlineUser::sendMessage);
                        return;
                    }
                }
                onlineUser.sendMessage(plugin.getCache().getCommandList(onlineUser,
                        plugin.getLocales(), plugin.getCommands(), plugin.getSettings().getListItemsPerPage(), page));
            }
            case "reload" -> {
                if (!onlineUser.hasPermission(Permission.COMMAND_HUSKHOMES_RELOAD.node)) {
                    plugin.getLocales().getLocale("error_no_permission")
                            .ifPresent(onlineUser::sendMessage);
                    return;
                }
                if (!plugin.reload()) {
                    onlineUser.sendMessage(new MineDown("[Error:](#ff3300) [Failed to reload the plugin. Check console for errors.](#ff7e5e)"));
                    return;
                }
                onlineUser.sendMessage(new MineDown("[HuskHomes](#00fb9a bold) &#00fb9a&| Reloaded config & message files."));
            }
            case "update" -> {
                if (!onlineUser.hasPermission(Permission.COMMAND_HUSKHOMES_UPDATE.node)) {
                    plugin.getLocales().getLocale("error_no_permission")
                            .ifPresent(onlineUser::sendMessage);
                    return;
                }
                plugin.getLatestVersionIfOutdated().thenAccept(newestVersion ->
                        newestVersion.ifPresentOrElse(
                                newVersion -> onlineUser.sendMessage(
                                        new MineDown("[HuskHomes](#00fb9a bold) [| A new version of HuskHomes is available!"
                                                     + " (v" + newVersion + " (Running: v" + plugin.getVersion() + ")](#00fb9a)")),
                                () -> onlineUser.sendMessage(
                                        new MineDown("[HuskHomes](#00fb9a bold) [| HuskHomes is up-to-date."
                                                     + " (Running: v" + plugin.getVersion() + ")](#00fb9a)"))));
            }
            case "migrate" -> plugin.getLocales().getLocale("error_console_command_only")
                    .ifPresent(onlineUser::sendMessage);
            default -> plugin.getLocales().getLocale("error_invalid_syntax", "/huskhomes [about|help|reload|update]")
                    .ifPresent(onlineUser::sendMessage);
        }
    }

    @Override
    public void onConsoleExecute(@NotNull String[] args) {
        if (args.length == 0) {
            Arrays.stream(aboutMenu.toString().split("\n")).forEach(message ->
                    plugin.getLoggingAdapter().log(Level.INFO, message));
            return;
        }
        switch (args[0].toLowerCase()) {
            case "about" -> Arrays.stream(aboutMenu.toString().split("\n")).forEach(message ->
                    plugin.getLoggingAdapter().log(Level.INFO, message));
            case "help" -> {
                plugin.getLoggingAdapter().log(Level.INFO, "List of enabled console-executable commands:");
                plugin.getCommands()
                        .stream().filter(command -> command instanceof ConsoleExecutable)
                        .forEach(command -> plugin.getLoggingAdapter().log(Level.INFO,
                                command.command +
                                (command.command.length() < 16 ? " ".repeat(16 - command.command.length()) : "")
                                + " - " + command.getDescription()));
            }
            case "reload" -> {
                if (!plugin.reload()) {
                    plugin.getLoggingAdapter().log(Level.SEVERE, "Failed to reload the plugin.");
                    return;
                }
                plugin.getLoggingAdapter().log(Level.INFO, "Reloaded config & message files.");
            }
            case "update" -> plugin.getLatestVersionIfOutdated().thenAccept(newestVersion ->
                    newestVersion.ifPresentOrElse(newVersion -> plugin.getLoggingAdapter().log(Level.WARNING,
                                    "An update is available for HuskHomes, v" + newVersion
                                    + " (Running v" + plugin.getVersion() + ")"),
                            () -> plugin.getLoggingAdapter().log(Level.INFO,
                                    "HuskHomes is up to date" +
                                    " (Running v" + plugin.getVersion() + ")")));
        }
    }

    private void sendAboutMenu(@NotNull OnlineUser onlineUser) {
        if (!onlineUser.hasPermission(Permission.COMMAND_HUSKHOMES_ABOUT.node)) {
            plugin.getLocales().getLocale("error_no_permission")
                    .ifPresent(onlineUser::sendMessage);
            return;
        }
        onlineUser.sendMessage(aboutMenu.toMineDown());
    }

    @Override
    @NotNull
    public final List<String> suggest(@NotNull CommandUser user, @NotNull String[] args) {
        if (args.length == 0 || args.length == 1) {
            return Arrays.stream(SUB_COMMANDS)
                    .filter(s -> s.toLowerCase().startsWith(args.length == 1 ? args[0].toLowerCase() : ""))
                    .sorted().collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
