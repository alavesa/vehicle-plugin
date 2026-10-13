package com.CodDev.vehicle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.UUID;

public final class VehicleListener implements Listener {

    private final VehiclePlugin plugin;
    private final VehicleManager manager;
    private final DriveTask task;

    public VehicleListener(VehiclePlugin plugin, VehicleManager manager, DriveTask task) {
        this.plugin = plugin;
        this.manager = manager;
        this.task = task;
    }

    /** Live WASD / space / sneak state. */
    @EventHandler
    public void onInput(PlayerInputEvent event) {
        task.input(event.getPlayer(), event.getInput());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        task.forget(event.getPlayer().getUniqueId());
        // leave the seat so the stand is not saved inside the player's data
        if (event.getPlayer().getVehicle() instanceof ArmorStand stand && stand.getScoreboardTags().contains(VehicleManager.TAG_SEAT)) {
            event.getPlayer().leaveVehicle();
        }
    }

    /** Right-click anywhere on the vehicle (its hitbox or a seat's click box) to take the nearest free seat. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClickAt(PlayerInteractAtEntityEvent event) {
        LivingEntity base = manager.baseOf(event.getRightClicked());
        if (base == null) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        VehicleType type = manager.typeOf(base);
        if (type == null) return;
        if (!player.hasPermission("vehicle.use")) return;
        if (player.getVehicle() != null) return;
        if (player.isSneaking() && tryPickup(player, base, type)) return;

        Location click = event.getRightClicked().getLocation().add(event.getClickedPosition());
        List<VehicleManager.SeatRef> seats = manager.seats(base, type);
        VehicleManager.SeatRef chosen = null;
        int clickedIndex = manager.seatIndex(event.getRightClicked());
        if (clickedIndex >= 0 && clickedIndex < seats.size() && seats.get(clickedIndex).rider() == null) {
            chosen = seats.get(clickedIndex);
        } else {
            double best = Double.MAX_VALUE;
            for (VehicleManager.SeatRef s : seats) {
                if (s.rider() != null) continue;
                double d = s.stand().getLocation().distanceSquared(click);
                if (d < best) { best = d; chosen = s; }
            }
        }
        if (chosen == null) {
            player.sendActionBar(Component.text("This vehicle is full.", NamedTextColor.GRAY));
            return;
        }
        chosen.stand().addPassenger(player);
        player.sendActionBar(Component.text(chosen.index() == 0
            ? "You're driving: W/S throttle, A/D steer, space = brake, sneak = get out, F = horn"
            : "Passenger seat " + (chosen.index() + 1) + ". Sneak to get out.", NamedTextColor.GRAY));
    }

    /** The plain interact event fires too - cancel it so the pig is never saddled/bred/named. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent event) {
        if (manager.baseOf(event.getRightClicked()) != null) event.setCancelled(true);
    }

    private boolean tryPickup(Player player, LivingEntity base, VehicleType type) {
        if (!plugin.getConfig().getBoolean("pickup-enabled", true)) return false;
        UUID owner = manager.ownerOf(base);
        boolean allowed = player.hasPermission("vehicle.admin") || (owner != null && owner.equals(player.getUniqueId()));
        if (!allowed) {
            player.sendActionBar(Component.text("This is not your vehicle.", NamedTextColor.RED));
            return true;
        }
        if (!manager.occupants(base).isEmpty()) {
            player.sendActionBar(Component.text("Someone is still inside.", NamedTextColor.RED));
            return true;
        }
        manager.remove(base);
        player.getInventory().addItem(manager.vehicleItem(type, 1)).values()
            .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        player.sendActionBar(Component.text("Picked up " + type.name + ".", NamedTextColor.GREEN));
        return true;
    }

    /** Place a vehicle item: right-click a block. */
    @EventHandler
    public void onPlace(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        String id = manager.itemTypeId(item);
        if (id == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("vehicle.use")) return;
        VehicleType type = plugin.types().get(id);
        if (type == null) {
            player.sendActionBar(Component.text("Unknown vehicle type '" + id + "'.", NamedTextColor.RED));
            return;
        }
        Block b = event.getClickedBlock();
        if (b == null) return;
        Location at = b.getRelative(event.getBlockFace()).getLocation().add(0.5, 0, 0.5);
        if (event.getBlockFace().getModY() <= 0 && !b.getRelative(event.getBlockFace()).isPassable()) return;
        at.setYaw(player.getLocation().getYaw());
        manager.spawn(type, at, player.getUniqueId());
        if (player.getGameMode() != GameMode.CREATIVE && plugin.getConfig().getBoolean("consume-item-on-place", true)) item.setAmount(item.getAmount() - 1);
        player.sendActionBar(Component.text("Placed " + type.name + ".", NamedTextColor.GREEN));
    }

    /** F while seated = horn. */
    @EventHandler
    public void onHorn(PlayerSwapHandItemsEvent event) {
        Player p = event.getPlayer();
        if (!(p.getVehicle() instanceof ArmorStand stand) || !stand.getScoreboardTags().contains(VehicleManager.TAG_SEAT)) return;
        LivingEntity base = manager.baseOf(stand);
        if (base == null) return;
        event.setCancelled(true);
        VehicleType type = manager.typeOf(base);
        if (type == null || type.horn == null || type.horn.isEmpty()) return;
        try { base.getWorld().playSound(base.getLocation(), type.horn, 1.5f, 1.0f); } catch (Exception ignored) { }
    }

    /** Sneak = get out. Afterwards step out beside the vehicle rather than on top of it. */
    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!(event.getDismounted() instanceof ArmorStand stand) || !stand.getScoreboardTags().contains(VehicleManager.TAG_SEAT)) return;
        task.forget(player.getUniqueId());
        LivingEntity base = manager.baseOf(stand);
        if (base == null || !plugin.getConfig().getBoolean("drive.exit-beside", true)) return;
        VehicleType type = manager.typeOf(base);
        if (type == null) return;
        UUID baseId = base.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getVehicle() != null) return;
            if (!(Bukkit.getEntity(baseId) instanceof LivingEntity b)) return;
            double rad = Math.toRadians(task.yawOf(b));
            Vector right = new Vector(-Math.cos(rad), 0, -Math.sin(rad));
            double dist = type.hitbox / 2.0 + 0.8;
            for (int side = 1; side >= -1; side -= 2) {
                Location spot = b.getLocation().add(right.clone().multiply(dist * side));
                spot.setYaw(player.getLocation().getYaw());
                spot.setPitch(player.getLocation().getPitch());
                if (spot.getBlock().isPassable() && spot.clone().add(0, 1, 0).getBlock().isPassable()) {
                    player.teleport(spot);
                    return;
                }
            }
        });
    }

    /** The base pig is the health pool: attacks hurt it, the environment does not. Parts are untouchable. */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Entity e = event.getEntity();
        if (VehicleManager.isBase(e)) {
            LivingEntity base = (LivingEntity) e;
            if (manager.invincible() || !isCombat(event.getCause())) { event.setCancelled(true); return; }
            if (base.getHealth() - event.getFinalDamage() <= 0.0) {
                event.setCancelled(true);
                if (plugin.getConfig().getBoolean("crash.destroy-at-zero-health", true)) manager.destroy(base);
                else base.setHealth(1.0);
            }
            return;
        }
        if (VehicleManager.isPart(e)) event.setCancelled(true);
    }

    private static boolean isCombat(EntityDamageEvent.DamageCause c) {
        return c == EntityDamageEvent.DamageCause.ENTITY_ATTACK
            || c == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
            || c == EntityDamageEvent.DamageCause.PROJECTILE
            || c == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
            || c == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
            || c == EntityDamageEvent.DamageCause.CUSTOM;
    }

    /** Nobody equips, undresses or poses the car (the base and the seats are armor stands). */
    @EventHandler(ignoreCancelled = true)
    public void onManipulate(org.bukkit.event.player.PlayerArmorStandManipulateEvent event) {
        if (VehicleManager.isBase(event.getRightClicked()) || VehicleManager.isPart(event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (!VehicleManager.isBase(event.getEntity())) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        LivingEntity base = (LivingEntity) event.getEntity();
        Bukkit.getScheduler().runTask(plugin, () -> { for (Entity p : manager.parts(base)) { p.eject(); p.remove(); } });
    }

    @EventHandler
    public void onTransform(EntityTransformEvent event) {
        if (VehicleManager.isBase(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler
    public void onCombust(EntityCombustEvent event) {
        if (VehicleManager.isBase(event.getEntity()) || VehicleManager.isPart(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler
    public void onPortal(EntityPortalEvent event) {
        if (VehicleManager.isBase(event.getEntity()) || VehicleManager.isPart(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler
    public void onTarget(EntityTargetEvent event) {
        if (event.getTarget() != null && (VehicleManager.isBase(event.getTarget()) || VehicleManager.isPart(event.getTarget()))) event.setCancelled(true);
    }

    @EventHandler
    public void onLeash(PlayerLeashEntityEvent event) {
        if (VehicleManager.isBase(event.getEntity())) event.setCancelled(true);
    }
}
