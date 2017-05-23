package github.scarsz.deathbans;

import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.internal.LinkedTreeMap;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.time.DurationFormatUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.*;

public final class DeathBans extends JavaPlugin implements Listener {

    private Map<UUID, Long> bans = new HashMap<>();
    private File bansFile;
    private Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private UUID lastBanned = null;

    private double latest;
    private boolean updateIsAvailable = false;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        bansFile = new File(getDataFolder(), "bans.json");

        // start metrics
        try {
            if (!getConfig().getBoolean("MetricsDisabled")) new Metrics(this).start();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // load config, create if doesn't exist, update config if old
        saveDefaultConfig();
        if (getConfig().getDouble("ConfigVersion") < Double.parseDouble(getDescription().getVersion()) || !getConfig().isSet("ConfigVersion"))
            try {
                getLogger().info("Your DeathBans config file was outdated; attempting migration...");

                File config = new File(getDataFolder(), "config.yml");
                File oldConfig = new File(getDataFolder(), "config.yml-build." + getConfig().getDouble("ConfigVersion") + ".old");
                Files.move(config, oldConfig);
                saveResource("config.yml", false);

                Scanner s1 = new Scanner(oldConfig);
                ArrayList<String> oldConfigLines = new ArrayList<>();
                while (s1.hasNextLine()) oldConfigLines.add(s1.nextLine());
                s1.close();

                Scanner s2 = new Scanner(config);
                ArrayList<String> newConfigLines = new ArrayList<>();
                while (s2.hasNextLine()) newConfigLines.add(s2.nextLine());
                s2.close();

                Map<String, String> oldConfigMap = new HashMap<>();
                for (String line : oldConfigLines) {
                    if (line.startsWith("#") || line.startsWith("-") || line.isEmpty()) continue;
                    List<String> lineSplit = new ArrayList<>();
                    Collections.addAll(lineSplit, line.split(": +|:"));
                    if (lineSplit.size() < 2) continue;
                    String key = lineSplit.get(0);
                    lineSplit.remove(0);
                    String value = String.join(": ", lineSplit);
                    oldConfigMap.put(key, value);
                }

                Map<String, String> newConfigMap = new HashMap<>();
                for (String line : newConfigLines) {
                    if (line.startsWith("#") || line.startsWith("-") || line.isEmpty()) continue;
                    List<String> lineSplit = new ArrayList<>();
                    Collections.addAll(lineSplit, line.split(": +|:"));
                    if (lineSplit.size() >= 2) newConfigMap.put(lineSplit.get(0), lineSplit.get(1));
                }

                oldConfigMap.keySet().stream().filter(key -> newConfigMap.containsKey(key) && !key.startsWith("ConfigVersion")).forEachOrdered(key -> {
                    String oldKey = oldConfigMap.get(key);
                    getLogger().info("Migrating config option " + key + " with value " + oldKey + " to new config");
                    newConfigMap.put(key, oldConfigMap.get(key));
                });

                for (String line : newConfigLines) {
                    if (line.startsWith("#") || line.startsWith("ConfigVersion")) continue;
                    String key = line.split(":")[0];
                    if (oldConfigMap.containsKey(key))
                        newConfigLines.set(newConfigLines.indexOf(line), key + ": " + oldConfigMap.get(key));
                }
                FileUtils.writeStringToFile(config, String.join(System.lineSeparator(), newConfigLines), Charset.defaultCharset());

                getLogger().info("Migration complete.");
                reloadConfig();
            } catch (IOException ignored) { }
        saveDefaultConfig();
        reloadConfig();

        // update check
        if (!getConfig().getBoolean("UpdateCheckDisabled")) {
            latest = Double.parseDouble(requestHttp("https://raw.githubusercontent.com/Scarsz/DeathBans/master/latestbuild"));
            if (latest > Double.parseDouble(getDescription().getVersion())) {
                getLogger().warning(System.lineSeparator() + System.lineSeparator() + "The current build of DeathBans is outdated! Get build " + latest + " at http://dev.bukkit.org/bukkit-plugins/deathbansgg/" + System.lineSeparator() + System.lineSeparator());
                updateIsAvailable = true;
            } else {
                getLogger().info("DeathBans is up-to-date. For change logs see the latest file at http://dev.bukkit.org/bukkit-plugins/deathbansgg/");
            }
        }

        if (bansFile.exists()) {
            try {
                ((LinkedTreeMap<String, Double>) gson.fromJson(FileUtils.readFileToString(bansFile, Charset.defaultCharset()), LinkedTreeMap.class)).forEach((uuid, timeAtExpire) -> bans.put(UUID.fromString(uuid), Math.round(timeAtExpire)));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onDisable() {
        try {
            FileUtils.writeStringToFile(bansFile, gson.toJson(bans), Charset.defaultCharset());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (event.getEntity().hasPermission("deathbans.immunity") || event.getEntity().isOp() || !getConfig().getBoolean("DoBans")) return;

        bans.put(event.getEntity().getUniqueId(), System.currentTimeMillis() + (getConfig().getInt("BanTimeInMinutes") * 60000));
        lastBanned = event.getEntity().getUniqueId();

        getLogger().info("Player " + event.getEntity().getName() + " died and has been banned for " + getConfig().getInt("BanTimeInMinutes") + " minutes");

        if (getConfig().getBoolean("SilenceDeathMessagesFromBan")) event.setDeathMessage("");
        event.getEntity().getInventory().clear();
        event.getEntity().kickPlayer(getKickMessage(event.getEntity().getUniqueId()));
    }

    @EventHandler
    public void onPlayerQuit(PlayerKickEvent event) {
        if (lastBanned != event.getPlayer().getUniqueId()) return;
        event.setQuitMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("LeaveMessageFromBan").replace("{player}", event.getPlayer().getName())));
    }

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (event.getPlayer().hasPermission("deathbans.immunity") || bans.get(event.getPlayer().getUniqueId()) == null) return;

        if (bans.get(event.getPlayer().getUniqueId()) < System.currentTimeMillis()) {
            bans.remove(event.getPlayer().getUniqueId());
            return;
        }

        event.disallow(PlayerLoginEvent.Result.KICK_BANNED, getKickMessage(event.getPlayer().getUniqueId()));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if ((event.getPlayer().hasPermission("deathbans.admin") || event.getPlayer().isOp()) && updateIsAvailable) {
            event.getPlayer().sendMessage(ChatColor.RED + "The current build of DeathBans is outdated! Get build " + latest + " at http://dev.bukkit.org/bukkit-plugins/deathbansgg/");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("deathbans.admin") && !sender.isOp() && (!(sender instanceof ConsoleCommandSender))) return true;

        if (args.length == 0) {
            sender.sendMessage(new String[] {
                    ChatColor.RED + "==================================================",
                    ChatColor.WHITE + "                 DeathBans v" + getDescription().getVersion() + " by Scarsz",
                    ChatColor.RED + "",
                    ChatColor.RED + " /deathbans " + ChatColor.WHITE + "revive [player/uuid] - " + ChatColor.RED + "revive the player",
                    ChatColor.WHITE + "                 revive * - " + ChatColor.RED + "revive literally everyone",
                    ChatColor.WHITE + "                 reload - " + ChatColor.RED + "reload DeathBans config",
                    ChatColor.RED + "=================================================="
            });
        } else {
            switch (args[0]) {
                case "revive":
                    if (args.length == 1) {
                        sender.sendMessage(ChatColor.RED + "You need to specify a user or * to revive");
                    } else {
                        if (args[1].equals("*")) {
                            bans.clear();
                            sender.sendMessage(ChatColor.GREEN + "Literally everyone's been un-deathbanned, you anarchist.");
                            return true;
                        }
                        UUID target = null;
                        try {
                            target = UUID.fromString(args[1]);
                        } catch (IllegalArgumentException e) {
                            for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
                                if (offlinePlayer.getName().equalsIgnoreCase(args[1])) target = offlinePlayer.getUniqueId();
                            }
                        }
                        if (target == null) {
                            sender.sendMessage(ChatColor.RED + "Target \"" + args[1] + "\" was not found. Did you make a typo?");
                            return true;
                        }
                        if (!bans.containsKey(target)) {
                            sender.sendMessage(ChatColor.RED + "Target \"" + target + "\" isn't death banned, tard");
                            return true;
                        }
                        bans.remove(target);
                        sender.sendMessage(ChatColor.GREEN + "Target \"" + target + "\" has been revived. Neat!");
                    }
                    break;
                case "reload":
                    sender.sendMessage(ChatColor.GREEN + "DeathBans config has been reloaded");
                    break;
            }
        }
        return true;
    }

    private String getKickMessage(UUID player) {
        String timeUntilBan = getTimeUntilBanExpires(player);
        if (timeUntilBan.length() != 0) timeUntilBan = getConfig().getString("KickMessagePart2Timed") + timeUntilBan;
        else timeUntilBan = getConfig().getString("KickMessagePart2Permanent");

        return ChatColor.translateAlternateColorCodes('&', getConfig().getString("KickMessagePart1") + timeUntilBan);
    }

    private String getTimeUntilBanExpires(UUID player) {
        // 0:00:17.592
        String dateUgly = DurationFormatUtils.formatDurationHMS(bans.get(player) - System.currentTimeMillis());
        int days = (int) Math.floor(Integer.parseInt(dateUgly.split(":")[0]) / 24.0);
        int hours = Integer.parseInt(dateUgly.split(":")[0]) - (days * 24);
        int minutes = Integer.parseInt(dateUgly.split(":")[1]);
        int seconds = Integer.parseInt(dateUgly.split(":")[2].split("\\.")[0]);

        List<String> timeUntilBanList = new LinkedList<>();
        if (days > 0) timeUntilBanList.add(days + " days");
        if (hours > 0) timeUntilBanList.add(hours + " hours");
        if (minutes > 0) timeUntilBanList.add(minutes + " minutes");
        if (seconds > 0) timeUntilBanList.add(seconds + " seconds");
        return String.join(", ", timeUntilBanList);
    }

    private static String requestHttp(String requestUrl) {
        try {
            return IOUtils.toString(new URL(requestUrl), Charset.defaultCharset());
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
    }

}
