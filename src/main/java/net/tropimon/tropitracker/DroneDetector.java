package net.tropimon.tropitracker;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Drones Rotom de Tropifurnitures, seulement les plus rares : épique (violet) et légendaire (orange).
 *
 * Deux sources, fusionnées :
 *  - le radar de Tropimon (icônes « motismadrone_ultrarare » et « motismadrone_legendary »), qui les
 *    voit de loin ;
 *  - les drones chargés autour du joueur (entité tropifurnitures:rotom_drone), dont la rareté est lue
 *    par réflexion (RotomDroneEntity.getTier().getName() = « epic » / « legendary »).
 * Affichage : faisceau orange vif vers le joueur, petit carré orange au centre, distance en bas.
 */
public final class DroneDetector {

    private static final String DRONE_ID = "tropifurnitures:rotom_drone";
    private static final int SCAN_INTERVAL_TICKS = 10;

    /** Drones épiques / légendaires chargés : identifiant d'entité -> entité. */
    private static volatile Map<Integer, Entity> loaded = new HashMap<>();
    private static int tick = 0;
    private static Method getTier;
    private static Method getTierName;

    private DroneDetector() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null || client.player == null) {
                loaded = new HashMap<>();
                return;
            }
            if (++tick < SCAN_INTERVAL_TICKS) return;
            tick = 0;
            Map<Integer, Entity> found = new HashMap<>();
            for (Entity e : client.world.getEntities()) {
                if (e.isRemoved()) continue;
                if (!DRONE_ID.equals(Registries.ENTITY_TYPE.getId(e.getType()).toString())) continue;
                String tier = tierOf(e);
                if ("epic".equals(tier) || "legendary".equals(tier)) found.put(e.getId(), e);
            }
            loaded = found;
        });
    }

    private static String tierOf(Entity e) {
        try {
            if (getTier == null) getTier = e.getClass().getMethod("getTier");
            Object t = getTier.invoke(e);
            if (t == null) return null;
            if (getTierName == null) getTierName = t.getClass().getMethod("getName");
            Object n = getTierName.invoke(t);
            return n == null ? null : n.toString().toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Centres des drones à afficher : entité chargée si possible, sinon position donnée par le radar. */
    public static java.util.List<Vec3d> positions(MinecraftClient client, float tickDelta) {
        java.util.List<Vec3d> out = new java.util.ArrayList<>();
        Map<Integer, Entity> now = loaded;
        for (Entity e : now.values()) {
            if (!e.isRemoved()) out.add(ShinyBeamRenderer.centerOf(e, tickDelta));
        }
        for (Map.Entry<Integer, Vec3d> b : RadarDetector.getDroneBlips().entrySet()) {
            if (now.containsKey(b.getKey())) continue;
            Entity e = client.world == null ? null : client.world.getEntityById(b.getKey());
            out.add(e != null && !e.isRemoved() ? ShinyBeamRenderer.centerOf(e, tickDelta) : b.getValue().add(0, 0.5, 0));
        }
        return out;
    }
}
