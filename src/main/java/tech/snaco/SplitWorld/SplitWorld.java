package tech.snaco.SplitWorld;


import com.destroystokyo.paper.event.entity.EndermanEscapeEvent;
import io.papermc.paper.event.entity.EntityMoveEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;
import tech.snaco.SplitWorld.types.WorldConfig;

import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings({"unchecked", "DataFlowIssue"})
public class SplitWorld extends JavaPlugin implements Listener {
    FileConfiguration config;
    GameMode default_game_mode;
    Map<String, WorldConfig> world_configs;
    SplitWorldKeys keys;
    ArrayList<Item> dropped_items = new ArrayList<>();
    SplitWorldCommands commandHandler;
    Utils utils;
    PlayerUtils playerUtils;

    public SplitWorld() {
        keys = new SplitWorldKeys(this);
        config = getConfig();
        world_configs = config.getList("world_configs").stream().map(item -> new WorldConfig((Map<String, Object>) item)).collect(Collectors.toMap(item -> item.world_name, item -> item));
        commandHandler = new SplitWorldCommands(keys, world_configs, config.getBoolean("manage_creative_commands", true));
        default_game_mode = switch (config.getString("default_game_mode")) {
            case "creative" -> GameMode.CREATIVE;
            case "adventure" -> GameMode.ADVENTURE;
            case "spectator" -> GameMode.SPECTATOR;
            default -> GameMode.SURVIVAL;
        };
        utils = new Utils(world_configs);
        playerUtils = new PlayerUtils(utils, keys, default_game_mode);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();


        Bukkit.getPluginManager().registerEvents(this, this);
        new BukkitRunnable() {
            @Override
            public void run() {
                if ((long) dropped_items.size() > 0) {
                    var items_to_remove = new ArrayList<Item>();
                    for (Item item : dropped_items) {
                        if (utils.worldEnabled(item.getWorld()) && utils.locationInBufferZone(item.getLocation())) {
                            item.remove();
                            items_to_remove.add(item);
                        }
                    }
                    dropped_items.removeAll(items_to_remove);
                }
            }
        }.runTaskTimer(this, 0, 1L);
    }

    /* Commands */
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
       return commandHandler.onCommand(sender, command, args);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        return commandHandler.onTabComplete(command, args);
    }

    @EventHandler
    public void preProcessCommand(PlayerCommandPreprocessEvent event) {
        commandHandler.preProcessCommand(event);
    }

    /* TBD */

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        var welcome_message_disabled = config.getBoolean("disable_welcome_message");
        if (welcome_message_disabled) {
            return;
        }
        var player = event.getPlayer();
        var player_pdc = player.getPersistentDataContainer();

        //track who participated in the competition
        var is_competition_ended = player.getWorld().getPersistentDataContainer().get(keys.getCompetition_ended(), PersistentDataType.INTEGER);
        if (is_competition_ended == null || is_competition_ended == 0) {
            var is_participant = player_pdc.get(keys.getCompetition_participant(), PersistentDataType.INTEGER);
            if (is_participant == null) {
                player_pdc.set(keys.getCompetition_participant(), PersistentDataType.INTEGER, 1);
            }
        }

        var world_name = player.getWorld().getName();
        var no_welcome = player_pdc.get(keys.getNo_welcome(), PersistentDataType.INTEGER);
        if (!world_configs.containsKey(world_name) || !world_configs.get(world_name).enabled) {
            return;
        }
        var world_config = utils.getWorldConfig(player.getWorld());
        if (no_welcome == null) {
            player.sendMessage(Component.text("Hello " + player.getName() + "! "
                    + "This world is split! You can head over towards the " + world_config.creative_side
                    + " side of the border at " + world_config.border_axis + "=" + world_config.border_location
                    + " to enter the creative side of the world. Your inventory will automatically be saved"
                    + " and loaded whenever you cross the border. Have fun! (To disable this message use /understood)"));
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (!utils.worldEnabled(event.getBlock().getWorld())) { return; }
        if (utils.locationInBufferZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
        if (utils.locationOnSurvivalSide(event.getBlock().getLocation()) && utils.locationInBufferZone(event.getPlayer().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!utils.worldEnabled(event.getPlayer().getWorld())) { return; }
        if (utils.locationInBufferZone(event.getPlayer().getLocation())) {
            event.getPlayer().setHealth(0.1);
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onHealthRegain(EntityRegainHealthEvent event) {
        if (!utils.worldEnabled(event.getEntity().getWorld())) { return; }
        if (utils.locationInBufferZone(event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (!utils.worldEnabled(event.getBlock().getWorld())) { return; }
        if (utils.locationInBufferZone(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
        if (utils.locationOnSurvivalSide(event.getBlock().getLocation()) && utils.locationInBufferZone(event.getPlayer().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        var destination = event.getTo();
        var player = event.getPlayer();

        if (!utils.worldEnabled(destination.getWorld())) {
            playerUtils.switchPlayerGameMode(player, default_game_mode);
            return;
        }

        if (utils.locationInBufferZone(destination)) {
            playerUtils.switchPlayerGameMode(player, GameMode.SPECTATOR);
            return;
        } else if (utils.locationOnCreativeSide(destination)) {
            playerUtils.switchPlayerGameMode(player, GameMode.CREATIVE);
            return;
        }

        var needs_warp = player.getGameMode() != GameMode.SURVIVAL;
        playerUtils.switchPlayerGameMode(player, GameMode.SURVIVAL);
        if (needs_warp) {
            playerUtils.warpPlayerToGround(player, player.getLocation());
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!utils.worldEnabled(event.getPlayer().getWorld())) { return; }
        var player = event.getPlayer();
        var player_pdc = player.getPersistentDataContainer();
        var disabled = player_pdc.get(keys.getSplit_world_disabled(), PersistentDataType.INTEGER);
        if (disabled != null && disabled == 1) {
            return;
        }

        // handle transitioning to survival safely
        if (player.getGameMode() != GameMode.SURVIVAL && utils.locationOnSurvivalSide(event.getTo()) && utils.locationOnSurvivalSide(player.getLocation())) {
            // temporarily load survival inv to check equip status
            playerUtils.loadPlayerInventory(player, GameMode.SURVIVAL);
            var player_has_elytra_equipped = player.getInventory().getChestplate() != null && player.getInventory().getChestplate().getType() == Material.ELYTRA;
            player.getInventory().clear();
            if (player_has_elytra_equipped && player.getLocation().getBlock().getType() == Material.AIR) {
                player.setGliding(true);
            }
            //noinspection deprecation
            if (!player_has_elytra_equipped && utils.locationOnSurvivalSide(player.getLocation()) && !player.isOnGround()) {
                playerUtils.warpPlayerToGround(player, event.getTo());
            }
        }
        playerUtils.switchPlayerToConfiguredGameMode(player);
        if (utils.playerInBufferZone(player)) {
            player.getInventory().clear();
            var next_location = event.getTo();
            if (!utils.locationIsTraversable(next_location)) {
                event.setCancelled(true);
            }
        }
        playerUtils.convertBufferZoneBlocksAroundPlayer(player);
    }

    @EventHandler
    public void onPlayerWorldChange(PlayerChangedWorldEvent event) {
        if (!utils.worldEnabled(event.getPlayer().getWorld())) {
            return;
        }
        playerUtils.switchPlayerToConfiguredGameMode(event.getPlayer());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        var custom_respawn = config.getBoolean("custom_respawn", false);
        var coordinates = Arrays.stream(config.getString("respawn_coordinates").split(" ")).map(Double::parseDouble).toList();
        if (custom_respawn && !event.isAnchorSpawn() && !event.isBedSpawn()) {
            event.setRespawnLocation(new Location(event.getRespawnLocation().getWorld(), coordinates.get(0), coordinates.get(1), coordinates.get(2), -88, 6));
        }
    }

    @EventHandler
    public void onBlockFromTo(BlockFromToEvent event) {
        if (!utils.worldEnabled(event.getBlock().getWorld())) { return; }
        if (utils.locationInBufferZone(event.getToBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityPortal(EntityPortalEvent event) {
        if (!utils.worldEnabled(event.getFrom().getWorld())) { return; }
        if (utils.locationOnCreativeSide(event.getFrom())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!utils.worldEnabled(event.getEntity().getWorld())) { return; }
        dropped_items.add(event.getEntity());
    }

    @EventHandler
    public void onEndermanEscape(EndermanEscapeEvent event) {
        if (!utils.worldEnabled(event.getEntity().getWorld())) { return; }

        if (utils.locationOnCreativeSide(event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityMove(EntityMoveEvent event) {
        if (!utils.worldEnabled(event.getEntity().getWorld())) { return; }

        var entity = event.getEntity();
        var entity_next_location = event.getTo();
        var entity_world_name = entity_next_location.getWorld().getName();

        // only do this if players are online
        if (entity.getServer().getOnlinePlayers().size() == 0) { return; }
        // don't do for players (JIC)
        if (entity instanceof Player) { return; }
        // Make sure it's in an enabled world
        if (!world_configs.containsKey(entity_world_name) || !world_configs.get(entity_world_name).enabled) { return; }

        // stop no crossing unless you are a player
        if (utils.locationInBufferZone(entity_next_location)) {
            event.setCancelled(true);
        }

        // only monsters on survival side
        if (entity instanceof Monster && utils.locationOnCreativeSide(entity_next_location) && utils.getWorldConfig(entity.getWorld()).no_creative_monsters) {
            entity.remove();
        }
    }

    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!utils.worldEnabled(event.getEntity().getWorld())) { return; }
        if (event.getTarget() instanceof Player && !utils.locationOnSurvivalSide(event.getTarget().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        var world = event.getLocation().getWorld();
        if (!utils.worldEnabled(world)) { return; }
        if (!utils.locationOnSurvivalSide(event.getLocation())
                && event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL
                && utils.getWorldConfig(world).no_creative_monsters
                && event.getEntity() instanceof Monster
        ) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPickup(PlayerAttemptPickupItemEvent event) {
        if (!utils.worldEnabled(event.getPlayer().getWorld())) { return; }
        if (utils.locationInBufferZone(event.getItem().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSpawn(PlayerSpawnLocationEvent event) {
        var custom_respawn = config.getBoolean("custom_respawn", false);
        var coordinates = Arrays.stream(config.getString("respawn_coordinates").split(" ")).map(Double::parseDouble).toList();
        var player_pdc = event.getPlayer().getPersistentDataContainer();
        var first_join = player_pdc.get(keys.getFirst_join(), PersistentDataType.INTEGER);
        if (custom_respawn && (first_join == null || first_join != 1)) {
            player_pdc.set(keys.getFirst_join(), PersistentDataType.INTEGER , 1);
            event.setSpawnLocation(new Location(event.getPlayer().getWorld(), coordinates.get(0), coordinates.get(1), coordinates.get(2), -88, 6));
        }
    }

    @EventHandler
    public void onPlayerFish(PlayerFishEvent event) {
        if (!utils.worldEnabled(event.getPlayer().getWorld())) { return; }

        // no fishing creative stuff to survival side
        var caught = event.getCaught();
        if (caught == null) {
            return;
        }

        if (!utils.locationOnSurvivalSide(caught.getLocation()) && !utils.locationOnCreativeSide(event.getPlayer().getLocation())) {
            event.setCancelled(true);
        }

        //snark
        var player_pdc = event.getPlayer().getPersistentDataContainer();
        var first_attempt = player_pdc.get(keys.getFirst_fish_attempt(), PersistentDataType.INTEGER);
        if (first_attempt == null) {
            event.getPlayer().giveExp(100);
            player_pdc.set(keys.getFirst_fish_attempt(), PersistentDataType.INTEGER, 1);
        }
        event.getPlayer().sendMessage("Nice try.");
    }

    @EventHandler
    public void playerHunger(FoodLevelChangeEvent event) {
        if (!utils.worldEnabled(event.getEntity().getWorld())) { return; }
        if (event.getEntity() instanceof Player && utils.locationInBufferZone(event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }
}
