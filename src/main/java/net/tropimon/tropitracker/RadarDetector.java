package net.tropimon.tropitracker;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Détection longue portée des shiny grâce au radar de Tropimon.
 *
 * Le serveur envoie à TropimodClient (paquet ContextBarShow) la liste des « blips » du radar :
 * identifiant d'entité, position (x, y, z) et icône (« shiny », « shiny_alpha », « legendary »...).
 * Ces blips arrivent bien avant que le Pokémon soit chargé côté client, donc plus loin que
 * ce que TropiTracker voit en lisant les entités du monde.
 *
 * Les données sont lues par réflexion dans ContextBarManager.shown, sans dépendance de compilation.
 * Un shiny repéré au radar déclenche l'alerte tout de suite (titre + son) et un faisceau doré
 * vers sa position ; quand le Pokémon finit par se charger, l'alerte n'est pas rejouée.
 */
public class RadarDetector {

    private static final String MANAGER_CLASS = "fr.erusel.tropimodclient.client.hud.contextbar.ContextBarManager";
    private static final int POLL_INTERVAL_TICKS = 5;

    private static boolean resolved = false;
    private static boolean errorLogged = false;
    private static Field shownField = null;

    private static int tick = 0;

    // Identifiants d'entités déjà annoncés via le radar (pour ne pas rejouer l'alerte au chargement)
    private static final Set<Integer> alertedIds = new HashSet<>();

    // Shiny repérés au radar mais pas encore chargés côté client : position pour le faisceau
    private static volatile List<Vec3d> farShinyPositions = new ArrayList<>();
    // Pokémon de la chasse (icône « hunt ») repérés au radar mais pas encore chargés
    private static volatile List<Vec3d> farHuntPositions = new ArrayList<>();
    /** Barons repérés au radar (icône rouge « alpha ») : identifiant d'entité -> position du blip. */
    private static volatile Map<Integer, Vec3d> baronBlips = new HashMap<>();

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) {
                farShinyPositions = new ArrayList<>();
                farHuntPositions = new ArrayList<>();
                baronBlips = new HashMap<>();
                return;
            }
            if (++tick < POLL_INTERVAL_TICKS) return;
            tick = 0;
            try {
                poll(client);
            } catch (Throwable t) {
                if (!errorLogged) {
                    errorLogged = true;
                    TropiTrackerClient.LOGGER.warn("[Radar] Lecture du radar Tropimon impossible", t);
                }
            }
        });
    }

    /** Vrai si ce Pokémon (identifiant réseau) a déjà déclenché l'alerte shiny via le radar. */
    public static boolean wasAlerted(int entityId) {
        return alertedIds.contains(entityId);
    }

    public static List<Vec3d> getFarShinyPositions() {
        return farShinyPositions;
    }

    public static List<Vec3d> getFarHuntPositions() {
        return farHuntPositions;
    }

    /** Barons du radar (icône rouge) : juste un symbole rouge, ni alerte ni faisceau. */
    public static Map<Integer, Vec3d> getBaronBlips() {
        return baronBlips;
    }

    private static void poll(MinecraftClient client) throws Exception {
        if (!resolved) {
            resolved = true;
            try {
                Class<?> mgr = Class.forName(MANAGER_CLASS);
                shownField = mgr.getDeclaredField("shown");
                shownField.setAccessible(true);
                TropiTrackerClient.LOGGER.info("[Radar] Radar Tropimon branché : détection shiny longue portée active.");
            } catch (ClassNotFoundException | NoSuchFieldException e) {
                TropiTrackerClient.LOGGER.info("[Radar] Radar Tropimon introuvable : détection longue portée désactivée.");
                shownField = null;
            }
        }
        if (shownField == null) return;

        Object data = shownField.get(null);
        if (data == null) {
            farShinyPositions = new ArrayList<>();
            farHuntPositions = new ArrayList<>();
            baronBlips = new HashMap<>();
            return;
        }

        Object blipsObj = call(data, "blips");
        if (!(blipsObj instanceof List<?> blips)) return;

        List<Vec3d> far = new ArrayList<>();
        List<Vec3d> farHunt = new ArrayList<>();
        Set<Integer> stillPresent = new HashSet<>();
        // Les cachettes shiny apparaissent aussi au radar : elles ont leur propre alerte et faisceau
        Set<Integer> spotIds = RustlingDetector.currentRadarIds();
        Map<Integer, Vec3d> barons = new HashMap<>();

        for (Object blip : blips) {
            if (blip == null) continue;
            Object icon = call(blip, "icon");
            String iconId = icon == null ? "" : String.valueOf(call(icon, "id"));
            // Icône rouge « alpha » (et ses variantes legendary_alpha, shiny_alpha...) = baron
            if (iconId.contains("alpha")) {
                int bid = ((Number) call(blip, "entityId")).intValue();
                if (!spotIds.contains(bid)) {
                    barons.put(bid, new Vec3d(((Number) call(blip, "x")).intValue() + 0.5,
                        ((Number) call(blip, "y")).intValue(), ((Number) call(blip, "z")).intValue() + 0.5));
                }
            }
            boolean shiny = iconId.contains("shiny");
            boolean hunt = !shiny && iconId.equals("hunt");
            if (!shiny && !hunt) continue;
            // Capture auto en cours : pas d'alerte ni de faisceau pour les Pokémon de la chasse
            if (hunt && AutoCapture.isActive()) continue;

            int id = ((Number) call(blip, "entityId")).intValue();
            if (spotIds.contains(id)) continue;
            int x = ((Number) call(blip, "x")).intValue();
            int y = ((Number) call(blip, "y")).intValue();
            int z = ((Number) call(blip, "z")).intValue();
            stillPresent.add(id);

            Entity loaded = client.world.getEntityById(id);
            boolean isLoaded = loaded != null && !loaded.isRemoved();

            // Pas encore chargé : c'est au radar de fournir la position du faisceau
            if (!isLoaded) {
                Vec3d pos = new Vec3d(x + 0.5, y + 0.5, z + 0.5);
                if (shiny) far.add(pos); else farHunt.add(pos);
            }

            if (alertedIds.contains(id)) continue;
            // Déjà chargé ET déjà annoncé par la détection normale : rien à faire
            if (isLoaded && TropiTrackerClient.isAnnouncedEntity(loaded)) {
                alertedIds.add(id);
                continue;
            }

            alertedIds.add(id);
            double dist = client.player.getPos().distanceTo(new Vec3d(x + 0.5, y + 0.5, z + 0.5));
            if (shiny) {
                announce(client, iconId.contains("alpha"), (int) dist);
            } else {
                announceHunt(client, (int) dist);
            }
        }

        // On oublie les shiny qui ont quitté le radar (capturés, disparus...)
        alertedIds.retainAll(unionWithLoaded(client, stillPresent));
        farShinyPositions = far;
        farHuntPositions = farHunt;
        baronBlips = barons;
    }

    /**
     * Garde aussi les identifiants des shiny encore chargés côté client, même s'ils ne sont plus
     * dans la liste du radar, pour éviter une seconde alerte quand la détection normale les voit.
     */
    private static Set<Integer> unionWithLoaded(MinecraftClient client, Set<Integer> present) {
        Set<Integer> keep = new HashSet<>(present);
        for (Integer id : alertedIds) {
            Entity e = client.world.getEntityById(id);
            if (e != null && !e.isRemoved()) keep.add(id);
        }
        return keep;
    }

    private static void announce(MinecraftClient client, boolean alpha, int dist) {
        if (TropiTrackerClient.isPaused()) return; // pause : marqué comme vu, mais aucune alerte
        String what = alpha ? "§6✨ SHINY ALPHA ✨" : "§6✨ SHINY ✨";
        TropiTrackerClient.LOGGER.info("[Radar] Shiny détecté au radar à {} m (alpha={})", dist, alpha);
        client.execute(() -> {
            if (client.player == null) return;
            client.inGameHud.setTitle(Text.literal(what));
            client.inGameHud.setSubtitle(Text.literal("§eRadar : à " + dist + " m"));
            client.inGameHud.setTitleTicks(2, 22, 6);
            client.player.sendMessage(Text.literal("§6✨ Shiny repéré au radar à §e" + dist + " m §6✨"), false);
            TropiTrackerClient.playRadarShinySound();
        });
    }

    private static void announceHunt(MinecraftClient client, int dist) {
        if (TropiTrackerClient.isPaused()) return;
        TropiTrackerClient.LOGGER.info("[Radar] Pokémon de la chasse détecté au radar à {} m", dist);
        client.execute(() -> {
            if (client.player == null) return;
            client.player.sendMessage(Text.literal("§e🎯 Pokémon recherché repéré au radar à §f" + dist + " m"), false);
            TropiTrackerClient.playRadarTrackedSound();
        });
    }

    private static final Map<String, Method> methodCache = new HashMap<>();

    private static Object call(Object target, String name) throws Exception {
        if (target == null) return null;
        String key = target.getClass().getName() + "#" + name;
        Method m = methodCache.get(key);
        if (m == null) {
            m = target.getClass().getMethod(name);
            m.setAccessible(true);
            methodCache.put(key, m);
        }
        return m.invoke(target);
    }
}
