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
 * Détection des cachettes (« rustling spots »).
 *
 * Source principale : TropimodClient (0.18+). Le serveur Tropimon envoie chaque cachette
 * (RustlingSpotAddPayload / Remove / Sync), rangée dans ClientRustlingSpots.active() sous forme
 * de RustlingSpot(id, pos, family, shiny) ; chaque cachette a aussi un identifiant radar
 * (radarId), car les cachettes shiny apparaissent dans le radar avec l'icône shiny.
 * Source de secours : le mod public Cobblemon Rustling Spots (RustlingSpotClientHandler).
 *
 * Les listes sont lues par réflexion (aucune dépendance de compilation) :
 *  - nouvelle cachette -> titre « Cachette détectée », message avec la distance et un son ;
 *  - tant qu'elle existe -> faisceau blanc à contour noir vers elle (dessiné par ShinyBeamRenderer).
 * La pause de TropiTracker (touche « * ») coupe tout, sans rien oublier.
 * Si le mod n'est pas installé, la détection reste simplement inactive.
 */
public class RustlingDetector {

    private static final String TROPIMON_CLASS = "fr.erusel.tropimodclient.client.render.spot.ClientRustlingSpots";
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
        // « cachettedebug » dans le tchat : état de la détection (jamais envoyé au serveur)
        net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!message.trim().equalsIgnoreCase("cachettedebug")) return true;
            debug(MinecraftClient.getInstance());
            return false;
        });

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
        if (!resolved || (activeSpots == null && (resolveRetryTicks += POLL_INTERVAL_TICKS) >= 200)) {
            resolved = true;
            resolveRetryTicks = 0;
            activeSpots = resolveSource();
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
            Object id = value(spot, "id");
            Object posObj = value(spot, "pos");
            if (!(posObj instanceof BlockPos pos)) continue;
            Object familyObj = value(spot, "family");
            Object shinyObj = value(spot, "shiny");
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

    // Raison du dernier échec de recherche (affichée par cachettedebug)
    private static String lastResolveError = "";
    private static int resolveRetryTicks = 0;

    /** Cherche d'abord les cachettes de TropimodClient, puis celles du mod public. */
    private static Method resolveSource() {
        StringBuilder errors = new StringBuilder();
        Method m = tryResolve(TROPIMON_CLASS, "active", errors);
        if (m != null) {
            TropiTrackerClient.LOGGER.info("[Cachette] Cachettes Tropimon (TropimodClient) : détection active.");
            lastResolveError = "";
            return m;
        }
        m = tryResolve(HANDLER_CLASS, "activeSpots", errors);
        if (m != null) {
            TropiTrackerClient.LOGGER.info("[Cachette] Mod Rustling Spots : détection active.");
            lastResolveError = "";
            return m;
        }
        lastResolveError = errors.toString();
        TropiTrackerClient.LOGGER.info("[Cachette] Aucune source de cachettes : {}", lastResolveError);
        return null;
    }

    private static Method tryResolve(String className, String methodName, StringBuilder errors) {
        try {
            // Chargeur de TropiTracker puis chargeur du contexte (au cas où ils diffèrent)
            Class<?> c;
            try {
                c = Class.forName(className, true, RustlingDetector.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                c = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
            }
            Method m = c.getMethod(methodName);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            String simple = className.substring(className.lastIndexOf('.') + 1);
            errors.append(simple).append(" : ").append(t.getClass().getSimpleName())
                .append(t.getMessage() == null ? "" : " (" + t.getMessage() + ")").append(" ; ");
            return null;
        }
    }

    /**
     * Identifiants radar des cachettes actuelles (lus en direct), pour que le radar ne prenne pas
     * une cachette shiny pour un Pokémon shiny.
     */
    public static Set<Integer> currentRadarIds() {
        Set<Integer> ids = new HashSet<>();
        if (!resolved || activeSpots == null) return ids;
        try {
            Object result = activeSpots.invoke(null);
            if (!(result instanceof Iterable<?> iterable)) return ids;
            List<Object> raw = new ArrayList<>();
            for (Object o : iterable) raw.add(o);
            for (Object spot : raw) {
                Object rid = value(spot, "radarId");
                if (rid instanceof Number n) ids.add(n.intValue());
            }
        } catch (Throwable ignored) {
        }
        return ids;
    }

    private static void debug(MinecraftClient client) {
        if (client.player == null) return;
        if (activeSpots == null) {
            resolved = true;
            activeSpots = resolveSource(); // nouvelle tentative à chaque commande
        }
        String source = activeSpots == null ? "§caucune"
            : "§a" + activeSpots.getDeclaringClass().getSimpleName();
        client.player.sendMessage(Text.literal("§f[Cachettes] source : " + source), false);
        if (activeSpots == null) {
            client.player.sendMessage(Text.literal("§7[Cachettes] raison : " + lastResolveError), false);
            net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("tropimodclient").ifPresentOrElse(
                mod -> client.player.sendMessage(Text.literal("§7[Cachettes] TropimodClient version : "
                    + mod.getMetadata().getVersion().getFriendlyString()), false),
                () -> client.player.sendMessage(Text.literal("§7[Cachettes] TropimodClient non chargé"), false));
            return;
        }
        try {
            Object result = activeSpots.invoke(null);
            int n = 0;
            if (result instanceof Iterable<?> it) for (Object ignored : it) n++;
            client.player.sendMessage(Text.literal("§f[Cachettes] connues par TropimodClient : §e" + n
                + "§f — lues par TropiTracker : §e" + spots.size()
                + (TropiTrackerClient.isPaused() ? " §c(TropiTracker en pause)" : "")), false);
            for (Spot s : spots) {
                client.player.sendMessage(Text.literal("§7 - " + s.family() + (s.shiny() ? " shiny" : "") + " à "
                    + (int) client.player.getPos().distanceTo(s.pos()) + " m"), false);
            }
        } catch (Throwable t) {
            client.player.sendMessage(Text.literal("§c[Cachettes] erreur : " + t), false);
        }
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
            case "NETHERFLAMME", "NETHER_FLAME" -> "Flamme du Nether";
            case "SOULFLAME", "SOUL_FLAME" -> "Flamme des âmes";
            case "FLYING" -> "Ciel";
            case "UNDERWATER" -> "Sous l'eau";
            case "END" -> "End";
            case "GEODE" -> "Géode";
            default -> enumName.isEmpty() ? "?" : enumName;
        };
    }

    private static final Map<String, Method> accessorCache = new HashMap<>();

    /** Accesseur de record (RustlingSpot de Tropimon) sinon champ privé (ClientSpot du mod public). */
    private static Object value(Object target, String name) throws Exception {
        String key = target.getClass().getName() + "#" + name;
        Method m = accessorCache.get(key);
        if (m == null && !accessorCache.containsKey(key)) {
            try {
                m = target.getClass().getMethod(name);
                m.setAccessible(true);
            } catch (NoSuchMethodException e) {
                m = null;
            }
            accessorCache.put(key, m);
        }
        if (m != null) return m.invoke(target);
        return field(target, name);
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
