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
 * Plusieurs espèces peuvent être en capture auto en même temps (une commande par espèce).
 * Tant qu'elle est active, toutes les 0,3 s (6 s avant de reviser un même Pokémon), TropiTracker cherche le Pokémon sauvage de l'espèce
 * choisie le plus proche (≤ 10 blocs, visible, hors combat, pas déjà visé), vise en tenant compte de
 * la retombée de la Poké Ball, et lance une balle de la hotbar. Avec 50 Pokémon de l'espèce autour,
 * il enchaîne donc 50 lancers, un par Pokémon.
 *
 * Calcul de la visée, d'après Cobblemon (PokeBallItem.throwPokeBall) :
 *  - angle de lancer réel = pitch du joueur - décalage, décalage = 5° (ou 5°·cos(pitch) si on vise
 *    vers le haut) ;
 *  - vitesse initiale = throwPower de la balle (1,25 par défaut) ;
 *  - trajectoire de projectile Minecraft : à chaque tick, position += vitesse ; vitesse × 0,99 ;
 *    vitesse.y -= 0,03.
 * La balle hérite du déplacement du joueur (marche, course, saut) et la cible est anticipée :
 * le calcul cherche la durée de vol pour laquelle la balle et le Pokémon se rejoignent.
 * La touche pause « * » suspend aussi les lancers.
 */
public final class AutoCapture {

    private static final double RANGE = 10.0;   // lancer seulement à 10 blocs maximum
    private static final int THROW_INTERVAL_TICKS = 6;   // 0,3 s entre deux Pokémon différents
    private static final long RETARGET_COOLDOWN_MS = 6000;  // 6 s avant de relancer sur le même Pokémon
    private static final double GRAVITY = 0.03;
    private static final double DRAG = 0.99;

    /** Noms (en minuscules, français ou anglais) des espèces visées ; vide = capture auto inactive. */
    private static final java.util.Set<String> targets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static int tick = 0;
    private static final Map<UUID, Long> recentlyThrownAt = new HashMap<>();
    private static boolean noBallWarned = false;

    private AutoCapture() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoCapture::onTick);
    }

    public static boolean isActiveFor(String nameLower) {
        return targets.contains(nameLower);
    }

    public static boolean isActive() {
        return !targets.isEmpty();
    }

    /** Pokémon sauvages de l'espèce en capture auto, chargés côté client : ils reçoivent la petite cible. */
    private static volatile java.util.List<PokemonEntity> marked = java.util.List.of();
    private static int markTick = 0;

    public static java.util.List<PokemonEntity> getMarked() {
        return targets.isEmpty() ? java.util.List.of() : marked;
    }

    private static void refreshMarked(MinecraftClient client) {
        if (targets.isEmpty() || client.world == null) {
            marked = java.util.List.of();
            return;
        }
        java.util.List<PokemonEntity> list = new java.util.ArrayList<>();
        for (Entity e : client.world.getEntities()) {
            if (!(e instanceof PokemonEntity pe) || pe.isRemoved()) continue;
            if (pe.getOwnerUuid() != null || pe.getPokemon().getOwnerUUID() != null) continue;
            if (pe.getBattleId() != null) continue;
            if (!matches(pe)) continue;
            list.add(pe);
        }
        marked = list;
    }

    /** Bascule la capture auto pour cette espèce (plusieurs espèces possibles). Renvoie true si elle vient d'être activée. */
    public static boolean toggle(String nameLower) {
        if (targets.remove(nameLower)) return false;
        if (targets.isEmpty()) {
            recentlyThrownAt.clear();
            tick = THROW_INTERVAL_TICKS;
        }
        targets.add(nameLower);
        noBallWarned = false;
        return true;
    }

    /** Arrête la capture auto pour cette espèce seulement. */
    public static void stop(String nameLower) {
        targets.remove(nameLower);
    }

    public static void stopAll() {
        targets.clear();
    }

    public static java.util.Set<String> getTargets() {
        return java.util.Collections.unmodifiableSet(targets);
    }

    // ------------------------------------------------------------------

    private static void onTick(MinecraftClient client) {
        if (++markTick >= 10) {   // liste des cibles rafraîchie toutes les 0,5 s
            markTick = 0;
            refreshMarked(client);
        }
        if (targets.isEmpty()) return;
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

        // Visée en mouvement : la balle hérite de la vitesse du joueur (Minecraft ajoute le
        // déplacement du lanceur, sans la composante verticale s'il est au sol), et la cible
        // est anticipée selon son propre déplacement.
        Vec3d start = player.getEyePos().subtract(0, 0.1, 0);
        Vec3d shooterVel = new Vec3d(player.getX() - player.prevX,
                player.isOnGround() ? 0 : player.getY() - player.prevY,
                player.getZ() - player.prevZ);
        Vec3d targetVel = new Vec3d(best.getX() - best.prevX, best.getY() - best.prevY, best.getZ() - best.prevZ);
        if (targetVel.lengthSquared() > 1.0) targetVel = Vec3d.ZERO; // téléportation : on ignore
        Vec3d aimPoint = best.getPos().add(0, best.getHeight() * 0.5, 0);
        Vec3d launchDir = solveMovingShot(start, shooterVel, aimPoint, targetVel, power);
        if (launchDir == null) return; // hors de portée de lancer

        float yaw = (float) Math.toDegrees(Math.atan2(-launchDir.x, launchDir.z));
        float launchPitch = (float) -Math.toDegrees(Math.asin(MathHelper.clamp(launchDir.y, -1.0, 1.0)));
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
        if (targets.contains(species)) return true;
        String key = "cobblemon.species." + species + ".name";
        String fr = net.minecraft.client.resource.language.I18n.translate(key).toLowerCase(Locale.ROOT);
        return targets.contains(fr);
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
     * Direction de lancer (vecteur unitaire) pour toucher une cible mobile depuis un joueur mobile.
     *
     * Trajectoire Minecraft (par tick : position += vitesse ; vitesse × 0,99 ; vitesse.y -= 0,03) :
     * après n ticks, déplacement = v0·S(n) - (0, G·(n - S(n))/(1 - D), 0) avec S(n) = (1 - Dⁿ)/(1 - D).
     * Pour chaque durée de vol n, on en déduit la vitesse initiale nécessaire w(n) ; la part due au
     * lancer est w(n) - vitesse du joueur, et doit valoir exactement la puissance de la balle.
     * On garde la plus petite durée qui convient (tir tendu). null si impossible.
     */
    private static Vec3d solveMovingShot(Vec3d start, Vec3d shooterVel, Vec3d target, Vec3d targetVel, float power) {
        double prevN = -1, prevF = 0;
        for (double n = 0.25; n <= 120; n += 0.25) {
            double f = excess(n, start, shooterVel, target, targetVel, power);
            if (prevN > 0 && prevF > 0 && f <= 0) {
                double lo = prevN, hi = n;
                for (int i = 0; i < 30; i++) {
                    double mid = (lo + hi) / 2;
                    if (excess(mid, start, shooterVel, target, targetVel, power) > 0) lo = mid; else hi = mid;
                }
                Vec3d launch = requiredVelocity(hi, start, target, targetVel).subtract(shooterVel);
                return launch.lengthSquared() < 1e-8 ? null : launch.normalize();
            }
            prevN = n;
            prevF = f;
        }
        return null;
    }

    private static double excess(double n, Vec3d start, Vec3d shooterVel, Vec3d target, Vec3d targetVel, float power) {
        return requiredVelocity(n, start, target, targetVel).subtract(shooterVel).length() - power;
    }

    /** Vitesse initiale totale pour être sur la cible (anticipée) au bout de n ticks. */
    private static Vec3d requiredVelocity(double n, Vec3d start, Vec3d target, Vec3d targetVel) {
        double s = (1 - Math.pow(DRAG, n)) / (1 - DRAG);
        double drop = GRAVITY * (n - s) / (1 - DRAG);
        Vec3d aim = target.add(targetVel.multiply(n)).subtract(start);
        return new Vec3d(aim.x / s, (aim.y + drop) / s, aim.z / s);
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
