package id.rnggagib.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class GatotkacasCommand implements TabExecutor {
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Reloadable reloadable;

    public interface Reloadable {
        void reload();
        String version();
        String mm(String key, String def);
    int culledLastTick();
    int processedLastTick();
    double ratioLastTick();
    int windowCulled();
    int windowProcessed();
    double windowRatio();
    boolean ratioPercent();
    String diag();
    }

    public GatotkacasCommand(Reloadable reloadable) {
        this.reloadable = reloadable;
    }

    private void send(CommandSender sender, String mini) {
        Component c = mm.deserialize(mini);
        sender.sendMessage(c);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            send(sender, reloadable.mm("messages.usage", "<gray>/" + label + " <yellow>[reload|info|diag]</yellow></gray>"));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                if (!sender.hasPermission("gatotkacas.reload")) {
                    send(sender, reloadable.mm("messages.no-permission", "<red>No permission.</red>"));
                    return true;
                }
                reloadable.reload();
                send(sender, reloadable.mm("messages.reloaded", "<green>Configuration reloaded.</green>"));
                return true;
            }
        case "info" -> {
        String msg = reloadable.mm("messages.info",
            "<gray>Running <green>gatotkacas</green> v<version></gray> | <gray>tick:<green><culled></green>/<green><processed></green> (<green><ratio></green>)</gray> | <gray>window:<green><wculled></green>/<green><wprocessed></green> (<green><wratio></green>)</gray>");
        String ratioTick = reloadable.ratioPercent()
            ? String.format("%.1f%%", reloadable.ratioLastTick() * 100.0)
            : String.format("%.3f", reloadable.ratioLastTick());
        String ratioWin = reloadable.ratioPercent()
            ? String.format("%.1f%%", reloadable.windowRatio() * 100.0)
            : String.format("%.3f", reloadable.windowRatio());
        msg = msg.replace("<version>", reloadable.version())
             .replace("<culled>", Integer.toString(reloadable.culledLastTick()))
             .replace("<processed>", Integer.toString(reloadable.processedLastTick()))
             .replace("<ratio>", ratioTick)
             .replace("<wculled>", Integer.toString(reloadable.windowCulled()))
             .replace("<wprocessed>", Integer.toString(reloadable.windowProcessed()))
             .replace("<wratio>", ratioWin);
        send(sender, msg);
                return true;
            }
            case "diag" -> {
                if (!sender.hasPermission("gatotkacas.diag")) {
                    send(sender, reloadable.mm("messages.no-permission", "<red>No permission.</red>"));
                    return true;
                }
                send(sender, reloadable.diag());
                return true;
            }
            default -> {
                send(sender, reloadable.mm("messages.usage", "<gray>/" + label + " <yellow>[reload|info|diag]</yellow></gray>"));
                return true;
            }
        }
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> list = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("gatotkacas.reload")) list.add("reload");
            list.add("info");
            if (sender.hasPermission("gatotkacas.diag")) list.add("diag");
        }
        return list;
    }
}
