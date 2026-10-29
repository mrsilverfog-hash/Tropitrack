package net.tropimon.tropitracker;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Capture automatique : activée UNIQUEMENT par « track <nom> auto » (même commande pour arrêter).
 *
 * Tant qu'elle est active, toutes les 6 secondes, TropiTracker cherche le Pokémon sauvage de l'espèce
 * choisie le plus proche (≤ 24 blocs, visible, hors combat, pas déjà visé), vise en tenant compte de
 * la retombée de la Poké Ball, et lance une balle de la hotbar. Avec 50 Pokémon de l'espèce autour,
 * il enchaîne donc 50 lancers, un par Pokémon.
 *
 * Calcul de la visée, d'après Cobblemon (PokeBallItem.throwPokeBall) :
 *  - angle de lancer réel = pitch du joueur - décalage, décalage = 5° (ou 5°·cos(pitch) si on vise
 *    vers le haut) ;
 *  - vitesse initiale = throwPower de la balle (1,25 par défaut) ;
 *  - trajectoire de projectile Minecraft : à chaque tick, position += vitesse ; vitesse × 0,99 ;
 *    vitesse.y -= 0,03.
 * On cherche par dichotomie l'angle qui passe par le centre du Pokémon.
 * La touche pause « * » suspend aussi les lancers.
 */
public final class AutoCapture {

    private static final double RANGE = 24.0;
    private static final int THROW_INTERVAL_TICKS = 120; // 6 secondes entre deux lancers
    private static final long RETARGET_COOLDOWN_MS = 4000;  // laisse le temps à la capture de se jouer
    private static final double GRAVITY = 0.03;
    private static final double DRAG = 0.99;

    /** Nom (en minuscules, français ou anglais) de l'espèce visée ; null = capture auto inactive. */
    private static String target = null;
    private static int tick = 0;
    private static final Map<UUID, Long> recentlyThrownAt = new HashMap<>();
    private static boolean noBallWarned = false;

    private AutoCapture() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoCapture::onTick);
    }

    public static boolean isActiveFor(String nameLower) {
        return target != null && target.equals(nameLower);
    }

    /** Bascule la capture auto pour cette espèce. Renvoie true si elle vient d'être activée. */
    public static boolean toggle(String nameLower) {
        if (nameLower.equals(target)) {
            target = null;
            return false;
        }
        target = nameLower;
        recentlyThrownAt.clear();
        noBallWarned = false;
        tick = THROW_INTERVAL_TICKS;
        return true;
    }

    public static void stop() {
        target = null;
    }

    // ------------------------------------------------------------------

    private static void onTick(MinecraftClient client) {
        if (target == null) return;
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.interactionManager == null) return;
        if (client.currentScreen != null) return;            // pas pendant un menu
        if (TropiTrackerClient.isPaused()) return;           // touche « * »
        if (++tick < THROW_INTERVAL_TICKS) return;
        tick = 0;

        long now = System.currentTimeMillis();
        recentlyThrownAt.values().removeIf(t -> now - t > RETARGET_COOLDOWN_MS);

        PokemonEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : client.world.getEntities()) {
            if (!(e instanceof PokemonEntity pe) || pe.isRemoved()) continue;
            if (pe.getOwnerUuid() != null || pe.getPokemon().getOwnerUUID() != null) continue;
            if (pe.getBattleId() != null) continue;
            if (recentlyThrownAt.containsKey(pe.getUuid())) continue;
            if (!matches(pe)) continue;
            double d = player.squaredDistanceTo(pe);
            if (d > RANGE * RANGE || d >= bestDist) continue;
            if (!player.canSee(pe)) continue;
            best = pe;
            bestDist = d;
        }
        if (best == null) return;

        int slot = findBallSlot(player);
        if (slot < 0) {
            if (!noBallWarned) {
                noBallWarned = true;
                player.sendMessage(Text.literal("§cTropiTracker : plus de Poké Ball dans la hotbar, capture auto en attente."), false);
            }
            return;
        }
        noBallWarned = false;

        player.getInventory().selectedSlot = slot;
        float power = throwPower(player.getInventory().getStack(slot));

        Vec3d eye = player.getEyePos();
        Vec3d aimPoint = best.getPos().add(0, best.getHeight() * 0.5, 0);
        Vec3d delta = aimPoint.subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);

        float yaw = (float) (MathHelper.atan2(delta.z, delta.x) * 180.0 / Math.PI) - 90.0f;
        Float launchPitch = solveLaunchPitch(horizontal, delta.y, power);
        if (launchPitch == null) return; // hors de portée de lancer
        float playerPitch = toPlayerPitch(launchPitch);

        float oldYaw = player.getYaw();
        float oldPitch = player.getPitch();
        player.setYaw(yaw);
        player.setPitch(MathHelper.clamp(playerPitch, -90f, 90f));
        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        player.swingHand(Hand.MAIN_HAND);
        // On rend la caméra au joueur juste après le lancer
        player.setYaw(oldYaw);
        player.setPitch(oldPitch);

        recentlyThrownAt.put(best.getUuid(), now);
    }

    private static boolean matches(PokemonEntity pe) {
        String species = pe.getPokemon().getSpecies().getName().toLowerCase(Locale.ROOT);
        if (species.equals(target)) return true;
        String key = "cobblemon.species." + species + ".name";
        String fr = net.minecraft.client.resource.language.I18n.translate(key).toLowerCase(Locale.ROOT);
        return fr.equals(target);
    }

    /** Premier emplacement de la hotbar contenant une Poké Ball (PokeBallItem de Cobblemon). */
    private static int findBallSlot(ClientPlayerEntity player) {
        int selected = player.getInventory().selectedSlot;
        if (isBall(player.getInventory().getStack(selected))) return selected;
        for (int i = 0; i < 9; i++) {
            if (isBall(player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    private static boolean isBall(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().getClass().getSimpleName().equals("PokeBallItem");
    }

    /** throwPower de la balle (PokeBallItem.getPokeBall().getThrowPower()), 1,25 par défaut. */
    private static float throwPower(ItemStack stack) {
        try {
            Method getBall = stack.getItem().getClass().getMethod("getPokeBall");
            Object ball = getBall.invoke(stack.getItem());
            Method getPower = ball.getClass().getMethod("getThrowPower");
            Object p = getPower.invoke(ball);
            if (p instanceof Number n && n.floatValue() > 0) return n.floatValue();
        } catch (Throwable ignored) {
        }
        return 1.25f;
    }

    /**
     * Angle de lancer (convention Minecraft : négatif = vers le haut) pour atteindre un point situé à
     * `horizontal` blocs et `dy` blocs plus haut. Dichotomie sur la hauteur atteinte, en ne gardant
     * que le tir tendu (le plus direct). null si la cible est hors de portée.
     */
    private static Float solveLaunchPitch(double horizontal, double dy, float power) {
        double lo = -60, hi = 60; // degrés : -60 = vers le haut, 60 = vers le bas
        // Le tir le plus haut (lo) doit passer au-dessus de la cible, sinon hors de portée
        if (heightAt(horizontal, lo, power) < dy) return null;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            if (heightAt(horizontal, mid, power) >= dy) lo = mid; else hi = mid;
        }
        return (float) lo;
    }

    /** Hauteur de la balle quand elle a parcouru `horizontal` blocs, pour un angle donné. */
    private static double heightAt(double horizontal, double pitchDeg, float power) {
        double rad = Math.toRadians(pitchDeg);
        double vx = Math.cos(rad) * power;
        double vy = -Math.sin(rad) * power;
        double x = 0, y = 0;
        for (int t = 0; t < 200; t++) {
            double nx = x + vx;
            double ny = y + vy;
            if (nx >= horizontal) {
                double f = (horizontal - x) / (nx - x);
                return y + (ny - y) * f;
            }
            x = nx;
            y = ny;
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
            if (vx < 1e-4) break;
        }
        return Double.NEGATIVE_INFINITY;
    }

    /** Inverse du décalage de Cobblemon : angle réel = pitch joueur - (5° ou 5°·cos(pitch) si pitch < 0). */
    private static float toPlayerPitch(float launchPitch) {
        float p = launchPitch + 5f;
        for (int i = 0; i < 8; i++) {
            float offset = p < 0 ? 5f * (float) Math.cos(Math.toRadians(p)) : 5f;
            p = launchPitch + offset;
        }
        return p;
    }
}
