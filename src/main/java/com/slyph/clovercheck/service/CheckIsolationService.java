package com.slyph.clovercheck.service;

import com.slyph.clovercheck.config.PluginSettings;
import com.slyph.clovercheck.model.CheckState;
import com.slyph.clovercheck.model.StoredLocation;
import com.slyph.clovercheck.session.CheckSession;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

public final class CheckIsolationService {
    private static final Vector ZERO_VELOCITY = new Vector(0.0, 0.0, 0.0);
    private static final double POSITION_EPSILON_SQUARED = 1.0E-8;

    private final JavaPlugin plugin;
    private final CheckSessionService checks;
    private final Logger logger;
    private final boolean cardboardRuntime;
    private final Map<UUID, PlayerState> states = new HashMap<>();
    private final Set<UUID> internalTeleports = new HashSet<>();
    private final Set<String> loggedFailures = new HashSet<>();
    private BukkitTask task;

    public CheckIsolationService(JavaPlugin plugin, CheckSessionService checks, Logger logger, boolean cardboardRuntime) {
        this.plugin = plugin;
        this.checks = checks;
        this.logger = logger;
        this.cardboardRuntime = cardboardRuntime;
    }

    public void start() {
        if (task != null) {
            task.cancel();
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID playerId : Set.copyOf(states.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                restore(player);
            } else {
                states.remove(playerId);
            }
        }
        internalTeleports.clear();
    }

    public boolean isInternalTeleport(UUID playerId) {
        return internalTeleports.contains(playerId);
    }

    private void tick() {
        Set<UUID> active = new HashSet<>();
        PluginSettings.FreezeSettings freeze = checks.freezeSettings();
        for (CheckSession session : checks.activeSessions()) {
            if (session.state() != CheckState.ACTIVE) {
                continue;
            }
            Player player = Bukkit.getPlayer(session.playerId());
            if (player == null || !player.isOnline()) {
                continue;
            }
            active.add(player.getUniqueId());
            states.computeIfAbsent(player.getUniqueId(), ignored -> capture(player));
            enforce(player, session.snapshot().origin(), freeze);
        }
        for (UUID playerId : Set.copyOf(states.keySet())) {
            if (active.contains(playerId)) {
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                restore(player);
            } else {
                states.remove(playerId);
            }
        }
    }

    private PlayerState capture(Player player) {
        Map<Attribute, Double> attributes = new LinkedHashMap<>();
        captureAttribute(player, attributes, Attribute.MOVEMENT_SPEED);
        captureAttribute(player, attributes, Attribute.FLYING_SPEED);
        captureAttribute(player, attributes, Attribute.JUMP_STRENGTH);
        captureAttribute(player, attributes, Attribute.SNEAKING_SPEED);
        captureAttribute(player, attributes, Attribute.BLOCK_INTERACTION_RANGE);
        captureAttribute(player, attributes, Attribute.ENTITY_INTERACTION_RANGE);
        captureAttribute(player, attributes, Attribute.BLOCK_BREAK_SPEED);
        captureAttribute(player, attributes, Attribute.KNOCKBACK_RESISTANCE);
        return new PlayerState(
                player.isInvulnerable(),
                player.getCanPickupItems(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getExhaustion(),
                player.getInventory().getHeldItemSlot(),
                attributes
        );
    }

    private void enforce(Player player, StoredLocation origin, PluginSettings.FreezeSettings freeze) {
        PlayerState state = states.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        if (freeze.movement()) {
            enforcePosition(player, origin);
            safe("velocity", () -> player.setVelocity(ZERO_VELOCITY));
            safe("sprint", () -> player.setSprinting(false));
            safe("sneak", () -> player.setSneaking(false));
            safe("jump", () -> player.setJumping(false));
            safe("swim", () -> player.setSwimming(false));
            safe("glide", () -> player.setGliding(false));
            setAttribute(player, Attribute.MOVEMENT_SPEED, 0.0);
            setAttribute(player, Attribute.FLYING_SPEED, 0.0);
            setAttribute(player, Attribute.JUMP_STRENGTH, 0.0);
            setAttribute(player, Attribute.SNEAKING_SPEED, 0.0);
            setAttribute(player, Attribute.KNOCKBACK_RESISTANCE, 1.0);
        }
        if (freeze.flight()) {
            safe("flying", () -> player.setFlying(false));
        }
        if (freeze.blockInteract() || freeze.blockPlace() || freeze.itemUse()) {
            setAttribute(player, Attribute.BLOCK_INTERACTION_RANGE, 0.0);
        }
        if (freeze.entityInteract() || freeze.attack()) {
            setAttribute(player, Attribute.ENTITY_INTERACTION_RANGE, 0.0);
        }
        if (freeze.blockBreak()) {
            setAttribute(player, Attribute.BLOCK_BREAK_SPEED, 0.0);
        }
        if (freeze.itemUse()) {
            safe("active-item", player::clearActiveItem);
        }
        if (freeze.damage()) {
            safe("invulnerable", () -> player.setInvulnerable(true));
        }
        if (freeze.hunger()) {
            safe("food", () -> {
                player.setFoodLevel(state.foodLevel());
                player.setSaturation(state.saturation());
                player.setExhaustion(state.exhaustion());
            });
        }
        if (freeze.itemPickup()) {
            safe("pickup", () -> player.setCanPickupItems(false));
        }
        if (freeze.heldSlot()) {
            safe("held-slot", () -> player.getInventory().setHeldItemSlot(state.heldSlot()));
        }
        if (freeze.inventory()) {
            safe("inventory-close", () -> {
                if (player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING) {
                    player.closeInventory();
                }
            });
        }
        if (freeze.vehicle() && player.isInsideVehicle()) {
            safe("vehicle", player::leaveVehicle);
        }
    }

    private void enforcePosition(Player player, StoredLocation origin) {
        World world = Bukkit.getWorld(origin.world());
        if (world == null) {
            logOnce("origin-world", "Cannot enforce check position because world is unavailable: " + origin.world());
            return;
        }
        Location current = player.getLocation();
        boolean wrongWorld = current.getWorld() != world;
        double dx = current.getX() - origin.x();
        double dy = current.getY() - origin.y();
        double dz = current.getZ() - origin.z();
        if (!wrongWorld && dx * dx + dy * dy + dz * dz <= POSITION_EPSILON_SQUARED) {
            return;
        }
        Location target = new Location(world, origin.x(), origin.y(), origin.z(), current.getYaw(), current.getPitch());
        UUID playerId = player.getUniqueId();
        internalTeleports.add(playerId);
        try {
            if (!player.teleport(target) && cardboardRuntime) {
                teleportByCommand(player, target);
            }
        } catch (RuntimeException exception) {
            if (cardboardRuntime) {
                teleportByCommand(player, target);
            } else {
                logFailure("teleport", exception);
            }
        } finally {
            internalTeleports.remove(playerId);
        }
    }

    private void teleportByCommand(Player player, Location target) {
        String name = player.getName();
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            logOnce("teleport-name", "Cannot use Cardboard teleport fallback for unsafe player name");
            return;
        }
        String command = "minecraft:tp " + name + " "
                + Double.toString(target.getX()) + " "
                + Double.toString(target.getY()) + " "
                + Double.toString(target.getZ()) + " "
                + Float.toString(target.getYaw()) + " "
                + Float.toString(target.getPitch());
        try {
            if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                logOnce("teleport-command", "Cardboard vanilla teleport fallback was rejected");
            }
        } catch (RuntimeException exception) {
            logFailure("teleport-command", exception);
        }
    }

    private void restore(Player player) {
        PlayerState state = states.remove(player.getUniqueId());
        if (state == null) {
            return;
        }
        safe("restore-invulnerable", () -> player.setInvulnerable(state.invulnerable()));
        safe("restore-pickup", () -> player.setCanPickupItems(state.canPickupItems()));
        safe("restore-food", () -> {
            player.setFoodLevel(state.foodLevel());
            player.setSaturation(state.saturation());
            player.setExhaustion(state.exhaustion());
        });
        for (Map.Entry<Attribute, Double> entry : state.attributeBaseValues().entrySet()) {
            setAttribute(player, entry.getKey(), entry.getValue());
        }
    }

    private void captureAttribute(Player player, Map<Attribute, Double> target, Attribute attribute) {
        try {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                target.put(attribute, instance.getBaseValue());
            }
        } catch (RuntimeException exception) {
            logFailure("attribute-capture-" + attribute.key().asString(), exception);
        }
    }

    private void setAttribute(Player player, Attribute attribute, double value) {
        try {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null && Double.compare(instance.getBaseValue(), value) != 0) {
                instance.setBaseValue(value);
            }
        } catch (RuntimeException exception) {
            logFailure("attribute-" + attribute.key().asString(), exception);
        }
    }

    private void safe(String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            logFailure(operation, exception);
        }
    }

    private void logFailure(String operation, RuntimeException exception) {
        logOnce(operation, "Check isolation operation '" + operation + "' is unavailable on this runtime ("
                + exception.getClass().getSimpleName() + ": " + safeMessage(exception) + ")");
    }

    private void logOnce(String key, String message) {
        if (loggedFailures.add(key)) {
            logger.warning(message);
        }
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "no message" : message.replace('\r', ' ').replace('\n', ' ');
    }

    private record PlayerState(
            boolean invulnerable,
            boolean canPickupItems,
            int foodLevel,
            float saturation,
            float exhaustion,
            int heldSlot,
            Map<Attribute, Double> attributeBaseValues
    ) {
    }
}
