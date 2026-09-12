package com.slyph.clovercheck.listener;

import com.slyph.clovercheck.config.PluginSettings;
import com.slyph.clovercheck.service.CheckSessionService;
import com.slyph.clovercheck.service.MessageService;
import com.slyph.clovercheck.session.CheckSession;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class CheckProtectionListener implements Listener {
    private final CheckSessionService checks;
    private final MessageService messages;
    private record FreezeAnchor(String sessionId, Location location) { }

    private final Map<UUID, FreezeAnchor> freezeAnchors = new HashMap<>();

    public CheckProtectionListener(CheckSessionService checks, MessageService messages) {
        this.checks = checks;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!freeze(player).movement()) return;
        if (player.isSprinting()) {
            player.setSprinting(false);
        }
        Location to = event.getTo();
        if (to == null) return;
        FreezeAnchor stored = freezeAnchors.computeIfAbsent(player.getUniqueId(), ignored ->
                new FreezeAnchor(checks.activeSession(player.getUniqueId()).orElseThrow().id(), event.getFrom().clone()));
        Location anchor = stored.location();
        if (samePosition(anchor, to)) return;
        Location allowed = anchor.clone();
        allowed.setYaw(to.getYaw());
        allowed.setPitch(to.getPitch());
        event.setTo(allowed);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onVelocity(PlayerVelocityEvent event) {
        if (freeze(event.getPlayer()).movement()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (freeze(event.getPlayer()).movement()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSprint(PlayerToggleSprintEvent event) {
        Player player = event.getPlayer();
        if (!freeze(player).movement()) return;
        event.setCancelled(true);
        if (player.isSprinting()) {
            player.setSprinting(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player player && freeze(player).movement()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        PluginSettings.FreezeSettings freeze = freeze(player);
        if (!freeze.teleport()) return;
        Location to = event.getTo();
        FreezeAnchor stored = freezeAnchors.get(player.getUniqueId());
        Location anchor = stored == null ? null : stored.location();
        if (to != null && anchor != null && samePosition(anchor, to)) return;
        if (to != null && samePosition(event.getFrom(), to)) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleportCompleted(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (event.getTo() == null || !freeze(player).movement()) return;
        checks.activeSession(player.getUniqueId()).ifPresent(session ->
                freezeAnchors.put(player.getUniqueId(), new FreezeAnchor(session.id(), event.getTo().clone())));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFlight(PlayerToggleFlightEvent event) {
        if (freeze(event.getPlayer()).flight()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        PluginSettings.FreezeSettings freeze = freeze(event.getPlayer());
        if ((event.getClickedBlock() != null && freeze.blockInteract()) || (event.hasItem() && freeze.itemUse())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (freeze(event.getPlayer()).entityInteract()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (freeze(event.getPlayer()).entityInteract()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (freeze(event.getPlayer()).entityInteract()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeash(PlayerLeashEntityEvent event) {
        if (freeze(event.getPlayer()).entityInteract()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onUnleash(PlayerUnleashEntityEvent event) {
        if (freeze(event.getPlayer()).entityInteract()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onShear(PlayerShearEntityEvent event) {
        if (freeze(event.getPlayer()).entityInteract()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFish(PlayerFishEvent event) {
        if (freeze(event.getPlayer()).itemUse()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (freeze(event.getPlayer()).itemUse()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (freeze(event.getPlayer()).itemUse()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (freeze(event.getPlayer()).itemUse()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEditBook(PlayerEditBookEvent event) {
        if (freeze(event.getPlayer()).inventory()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (freeze(event.getPlayer()).blockInteract()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        if (freeze(event.getPlayer()).blockInteract()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLecternTake(PlayerTakeLecternBookEvent event) {
        if (freeze(event.getPlayer()).blockInteract()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreakStart(BlockDamageEvent event) {
        if (freeze(event.getPlayer()).blockBreak()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        if (freeze(event.getPlayer()).blockBreak()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) {
        if (freeze(event.getPlayer()).blockPlace()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && freeze(player).inventory()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryCreative(InventoryCreativeEvent event) {
        if (event.getWhoClicked() instanceof Player player && freeze(player).inventory()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCraft(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player player && freeze(player).inventory()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSmith(SmithItemEvent event) {
        if (event.getWhoClicked() instanceof Player player && freeze(player).inventory()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && freeze(player).inventory()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && freeze(player).inventory()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        if (freeze(event.getPlayer()).itemDrop()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && freeze(player).itemPickup()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onArrowPickup(PlayerPickupArrowEvent event) {
        if (freeze(event.getPlayer()).itemPickup()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExperience(PlayerExpChangeEvent event) {
        if (freeze(event.getPlayer()).itemPickup()) event.setAmount(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (freeze(event.getPlayer()).swapHand()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHeldItem(PlayerItemHeldEvent event) {
        if (freeze(event.getPlayer()).heldSlot()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (projectile.getShooter() instanceof Player player && freeze(player).itemUse()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && freeze(player).damage()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = attackingPlayer(event.getDamager());
        if (attacker != null && freeze(attacker).attack()) event.setCancelled(true);
        if (event.getEntity() instanceof Player target && freeze(target).damage()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && freeze(player).hunger()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player && freeze(player).vehicle()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player && freeze(player).vehicle()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        PluginSettings.FreezeSettings freeze = freeze(event.getPlayer());
        if (!freeze.commands() || checks.isCommandAllowed(event.getMessage())) return;
        event.setCancelled(true);
        messages.send(event.getPlayer(), "command-blocked");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        freezeAnchors.remove(event.getPlayer().getUniqueId());
        checks.handleQuit(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        freezeAnchors.remove(event.getPlayer().getUniqueId());
        checks.handleJoin(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        freezeAnchors.remove(event.getEntity().getUniqueId());
        checks.handleCheckedDeath(event.getEntity());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        freezeAnchors.remove(event.getPlayer().getUniqueId());
        checks.handleRespawn(event.getPlayer());
    }

    private PluginSettings.FreezeSettings freeze(Player player) {
        Optional<CheckSession> session = checks.activeSession(player.getUniqueId());
        if (session.isEmpty()) {
            freezeAnchors.remove(player.getUniqueId());
            return DISABLED;
        }
        PluginSettings.FreezeSettings settings = checks.freezeSettings();
        FreezeAnchor anchor = freezeAnchors.get(player.getUniqueId());
        if (!settings.movement() || (anchor != null && !anchor.sessionId().equals(session.get().id()))) {
            freezeAnchors.remove(player.getUniqueId());
        }
        return settings;
    }

    private static boolean samePosition(Location first, Location second) {
        return first.getWorld() == second.getWorld()
                && Double.compare(first.getX(), second.getX()) == 0
                && Double.compare(first.getY(), second.getY()) == 0
                && Double.compare(first.getZ(), second.getZ()) == 0;
    }

    private static Player attackingPlayer(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    private static final PluginSettings.FreezeSettings DISABLED = new PluginSettings.FreezeSettings(
            false, false, false, false, false, false, false, false, false, false,
            false, false, false, false, false, false, false, false, false
    );
}
