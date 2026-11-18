package dev.boomerang;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Один летящий бумеранг. */
final class Flight {

    private final BoomerangPlugin plugin;
    private final BoomerangPlugin.Stats stats;
    private final UUID ownerId;
    private final ItemStack stack;
    private final World world;
    private final ItemDisplay display;
    private final Map<UUID, Integer> lastHit = new HashMap<>();

    private Vector pos;
    private Vector dir;
    private int bounces = 0;
    private int sinceBounce = 0;
    private int age = 0;
    private boolean returning = false;
    private float angle = 0f;

    Flight(BoomerangPlugin plugin, Player owner, ItemStack stack, BoomerangPlugin.Stats stats) {
        this.plugin = plugin;
        this.stats = stats;
        this.ownerId = owner.getUniqueId();
        this.stack = stack;
        this.world = owner.getWorld();
        this.dir = owner.getEyeLocation().getDirection().normalize();
        this.pos = owner.getEyeLocation().toVector().add(dir.clone().multiply(0.6));

        ItemStack shown = stack.clone();
        this.display = world.spawn(pos.toLocation(world), ItemDisplay.class, d -> {
            d.setItemStack(shown);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setPersistent(false);
            d.setTeleportDuration(1);
            d.setInterpolationDuration(1);
            d.setViewRange(2.0f);
        });
        world.playSound(pos.toLocation(world), Sound.ENTITY_SNOWBALL_THROW, 1f, 0.8f);
    }

    /** @return true, если бумеранг закончил полёт и его надо убрать из списка. */
    boolean tick() {
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null || !owner.isOnline() || owner.isDead() || !owner.getWorld().equals(world)) {
            dropAtPosition();
            return true;
        }

        age++;

        if (!returning && (bounces >= stats.maxBounces() || sinceBounce >= stats.noHitTicks())) {
            startReturn();
        }
        if (age > plugin.maxAgeTicks()) {
            catchBy(owner);
            return true;
        }

        if (returning) {
            Vector to = owner.getBoundingBox().getCenter().subtract(pos);
            double dist = to.length();
            if (dist <= 1.3) {
                catchBy(owner);
                return true;
            }
            Vector desired = to.multiply(1.0 / dist);
            Vector steered = dir.clone().multiply(0.5).add(desired.multiply(0.5));
            dir = steered.lengthSquared() < 1.0E-6 ? desired : steered.normalize();
            pos.add(dir.clone().multiply(Math.min(stats.returnSpeed(), dist)));
        } else {
            double speed = stats.throwSpeed();
            RayTraceResult hit = world.rayTraceBlocks(
                    pos.toLocation(world), dir, speed + 0.2, FluidCollisionMode.NEVER, true);
            if (hit != null && hit.getHitBlockFace() != null) {
                Vector normal = hit.getHitBlockFace().getDirection();
                pos = hit.getHitPosition().clone().add(normal.clone().multiply(0.2));
                dir.subtract(normal.clone().multiply(2.0 * dir.dot(normal))).normalize();
                bounces++;
                sinceBounce = 0;
                Particle.CRIT.builder().location(pos.toLocation(world)).count(6).offset(0.1, 0.1, 0.1).spawn();
                world.playSound(pos.toLocation(world), Sound.ENTITY_ARROW_HIT, 1f, 1.5f);
            } else if (hit != null) {
                // начали внутри блока и нормали нет — просто возвращаемся
                startReturn();
            } else {
                pos.add(dir.clone().multiply(speed));
                sinceBounce++;
            }
        }

        hitEntities(owner);
        updateDisplay();

        if (age % 4 == 0) {
            world.playSound(pos.toLocation(world), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.4f, 1.3f);
        }
        return false;
    }

    private void startReturn() {
        if (returning) return;
        returning = true;
        world.playSound(pos.toLocation(world), Sound.ENTITY_ENDER_EYE_LAUNCH, 0.7f, 1.4f);
    }

    private void hitEntities(Player owner) {
        for (LivingEntity e : world.getNearbyLivingEntities(pos.toLocation(world), 0.7)) {
            if (e.getUniqueId().equals(ownerId) || e instanceof ArmorStand || e.isDead()) continue;
            Integer last = lastHit.get(e.getUniqueId());
            if (last != null && age - last < 10) continue;
            lastHit.put(e.getUniqueId(), age);
            e.damage(stats.damage(), owner);
            world.playSound(e.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 1f);
        }
    }

    private void updateDisplay() {
        angle = (angle + 60f) % 360f;
        display.teleport(pos.toLocation(world));
        Quaternionf left = new Quaternionf().rotateY((float) Math.toRadians(angle));
        Quaternionf right = new Quaternionf().rotateX((float) Math.toRadians(-90));
        display.setTransformation(new Transformation(new Vector3f(), left, new Vector3f(1.4f), right));
        display.setInterpolationDelay(0);
    }

    private void catchBy(Player owner) {
        display.remove();
        Map<Integer, ItemStack> left = owner.getInventory().addItem(stack);
        for (ItemStack rest : left.values()) {
            owner.getWorld().dropItemNaturally(owner.getLocation(), rest);
        }
        owner.getWorld().playSound(owner.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);
    }

    private void dropAtPosition() {
        display.remove();
        world.dropItemNaturally(pos.toLocation(world), stack);
    }

    /** Вызывается при выключении плагина. */
    void abort() {
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner != null && owner.isOnline() && !owner.isDead()) {
            catchBy(owner);
        } else {
            dropAtPosition();
        }
    }
}
