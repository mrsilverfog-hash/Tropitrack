package net.tropimon.tropitracker;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
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
 * Détection des « rustling spots » (mod Cobblemon Rustling Spots, id « rustlingspots »).
 *
 * Le serveur envoie au client chaque cachette active (paquet spot_spawn), que le mod range dans
 * RustlingSpotClientHandler.ACTIVE : identifiant, position, famille (herbe, sable, neige...) et
 * indicateur shiny. On lit cette liste par réflexion (aucune dépendance de compilation) :
 *  - nouvelle cachette -> titre « Cachette détectée », message avec la distance et un son ;
 *  - tant qu'elle existe -> faisceau blanc à contour noir vers elle (dessiné par ShinyBeamRenderer).
 * La pause de TropiTracker (touche « * ») coupe tout, sans rien oublier.
 * Si le mod n'est pas installé, la détection reste simplement inactive.
 */
public class RustlingDetector {

    private static final String HANDLER_CLASS = "net.levelscraft7.rustlingspots.client.RustlingSpotClientHandler";
    private static final int POLL_INTERVAL_TICKS = 10;

    private static boolean resolved = false;
    private static boolean errorLogged = false;
    private static Method activeSpots = null;
    private static final Map<Class<?>, Field[]> fieldCache = new HashMap<>();

    private static int tick = 0;
    private static final Set<Object> announcedIds = new HashSet<>();

    /** Une cachette visible : centre du bloc, famille traduite, shiny. */
    public record Spot(Vec3d pos, String family, boolean shiny) {}

    private static volatile List<Spot> spots = new ArrayList<>();

    public static List<Spot> getSpots() {
        return spots;
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) {
                spots = new ArrayList<>();
                return;
            }
            if (++tick < POLL_INTERVAL_TICKS) return;
            tick = 0;
            try {
                poll(client);
            } catch (Throwable t) {
                if (!errorLogged) {
                    errorLogged = true;
                    TropiTrackerClient.LOGGER.warn("[Cachette] Lecture des rustling spots impossible", t);
                }
            }
        });
    }

    private static void poll(MinecraftClient client) throws Exception {
        if (!resolved) {
            resolved = true;
            try {
                activeSpots = Class.forName(HANDLER_CLASS).getMethod("activeSpots");
                activeSpots.setAccessible(true);
                TropiTrackerClient.LOGGER.info("[Cachette] Rustling Spots détecté : détection des cachettes active.");
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                TropiTrackerClient.LOGGER.info("[Cachette] Mod Rustling Spots absent : détection des cachettes inactive.");
                activeSpots = null;
            }
        }
        if (activeSpots == null) return;

        Object result = activeSpots.invoke(null);
        if (!(result instanceof Iterable<?> iterable)) return;

        // Copie d'abord : la liste du mod peut changer pendant qu'on la lit
        List<Object> raw = new ArrayList<>();
        for (Object o : iterable) raw.add(o);

        List<Spot> found = new ArrayList<>();
        Set<Object> present = new HashSet<>();

        for (Object spot : raw) {
            if (spot == null) continue;
            Object id = field(spot, "id");
            Object posObj = field(spot, "pos");
            if (!(posObj instanceof BlockPos pos)) continue;
            Object familyObj = field(spot, "family");
            Object shinyObj = field(spot, "shiny");
            boolean shiny = shinyObj instanceof Boolean b && b;
            String family = familyName(familyObj == null ? "" : familyObj.toString());

            Vec3d center = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            found.add(new Spot(center, family, shiny));

            Object key = id != null ? id : pos;
            present.add(key);
            if (announcedIds.add(key)) {
                int dist = (int) client.player.getPos().distanceTo(center);
                announce(client, family, shiny, dist);
            }
        }

        announcedIds.retainAll(present);
        spots = found;
    }

    private static void announce(MinecraftClient client, String family, boolean shiny, int dist) {
        TropiTrackerClient.LOGGER.info("[Cachette] Cachette {} détectée à {} m (shiny={})", family, dist, shiny);
        if (TropiTrackerClient.isPaused()) return; // pause : marquée comme vue, aucune alerte
        client.execute(() -> {
            if (client.player == null) return;
            String title = shiny ? "§f✨ Cachette shiny détectée ✨" : "§fCachette détectée";
            client.inGameHud.setTitle(Text.literal(title));
            client.inGameHud.setSubtitle(Text.literal("§7" + family + " · " + dist + " m"));
            client.inGameHud.setTitleTicks(2, 22, 6);
            client.player.sendMessage(Text.literal("§f🌿 Cachette détectée §7(" + family + ") à §f" + dist + " m"
                + (shiny ? " §d✨ shiny" : "")), false);
            // Deux notes de carillon, bien distinctes des sons Pokémon
            client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.2f, 1.0f));
            client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.6f, 1.0f));
        });
    }

    private static String familyName(String enumName) {
        return switch (enumName.toUpperCase()) {
            case "GRASS" -> "Herbe";
            case "SAND" -> "Sable";
            case "WATER" -> "Eau";
            case "SNOW" -> "Neige";
            case "LEAVES" -> "Feuillage";
            case "CAVE" -> "Grotte";
            case "NETHERFLAMME" -> "Flamme du Nether";
            case "SOULFLAME" -> "Flamme des âmes";
            case "FLYING" -> "Ciel";
            case "UNDERWATER" -> "Sous l'eau";
            case "END" -> "End";
            case "GEODE" -> "Géode";
            default -> enumName.isEmpty() ? "?" : enumName;
        };
    }

    /** Lit un champ du ClientSpot du mod (classe package-private, champs privés). */
    private static Object field(Object target, String name) throws IllegalAccessException {
        Field[] fields = fieldCache.computeIfAbsent(target.getClass(), c -> {
            Field[] fs = c.getDeclaredFields();
            for (Field f : fs) {
                try { f.setAccessible(true); } catch (Throwable ignored) { }
            }
            return fs;
        });
        for (Field f : fields) {
            if (f.getName().equals(name)) return f.get(target);
        }
        return null;
    }
}
