package net.tropimon.tropitracker;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class TropiTrackerClient implements ClientModInitializer {

    /**
     * Logger partagé du mod. Le nom "tropitracker" apparaît déjà dans chaque
     * ligne de log de Minecraft, d'où l'absence de préfixe [TropiTracker] dans
     * les messages : il serait dupliqué. Les traces peuvent désormais être
     * filtrées par niveau, ce que la sortie standard ne permettait pas.
     */
    public static final Logger LOGGER = LoggerFactory.getLogger("tropitracker");

    private static KeyBinding muteKey;
    private static boolean muted = false;

    /**
     * Coupe uniquement les sons Shiny et Baron. Les faisceaux, titres et
     * messages restent affichés, et les autres alertes continuent de sonner.
     */
    private static KeyBinding muteRareKey;
    private static boolean rareMuted = false;

    public static SoundEvent LEGENDARY_SOUND;
    public static SoundEvent SHINY_SOUND;
    public static SoundEvent PARADOX_SOUND;
    public static SoundEvent INCLUDED_SOUND;
    public static SoundEvent BARON_SOUND;

    public static boolean enableLegendary  = true;
    public static boolean enableMythic     = true;
    public static boolean enableUltraBeast = true;
    public static boolean enableParadox    = true;
    public static boolean enableShiny      = true;
    public static boolean enableBaron      = true;

    /**
     * Motif recherché dans le nom affiché du Pokémon. Tropimon nomme ces spawns
     * « Roucool le baron », « Roucool l'ancien baron », etc. La comparaison se
     * fait sur un nom mis à plat (codes couleur §x retirés, accents supprimés,
     * minuscules), donc une simple sous-chaîne suffit et attrape aussi les
     * variantes au pluriel ou au féminin.
     */
    private static final String BARON_PATTERN = "baron";

    private static final Set<String> trackedPokemons = new HashSet<>();
    private static final Set<String> boardTrackedPokemons = new HashSet<>();
    private static final Set<String> boardSpeciesCanonical = new HashSet<>();
    private static final Set<java.util.UUID> seenEntities = new HashSet<>();
    private static final Set<java.util.UUID> announcedEntities = new HashSet<>();

    private static final Set<PokemonEntity> activeShinyEntities = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<PokemonEntity> activeBaronEntities = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static Set<PokemonEntity> getActiveShinyEntities() {
        return activeShinyEntities;
    }

    public static Set<PokemonEntity> getActiveBaronEntities() {
        return activeBaronEntities;
    }

    private static class TrackedPending {
        PokemonEntity entity;
        int ticksLeft;
        TrackedPending(PokemonEntity entity, int ticksLeft) {
            this.entity = entity;
            this.ticksLeft = ticksLeft;
        }
    }

    private static final java.util.Map<java.util.UUID, TrackedPending> pendingEntities = new java.util.HashMap<>();

    private static int scanTick = 0;
    private static int soundPlaybackTick = 0;
    private static SoundEvent activeLoopSound = null;
    private static float activeLoopVolume = 1.0f;
    private static boolean loopActive = false;

    private static final float SHINY_VOLUME = 3.0f;
    private static final float BARON_VOLUME = 0.25f;
    private static final float TRACKED_VOLUME = 2.0f;

    private static final Set<String> LEGENDARY_LABELS   = Set.of("legendary");
    private static final Set<String> MYTHIC_LABELS      = Set.of("mythical");
    private static final Set<String> ULTRA_BEAST_LABELS = Set.of("ultra_beast");
    private static final Set<String> PARADOX_LABELS     = Set.of("paradox");

    private static int teleportCooldown = 0;
    private static double lastX = 0;
    private static double lastY = 0;
    private static double lastZ = 0;
    private static net.minecraft.client.world.ClientWorld lastWorld = null;

    @Override
    public void onInitializeClient() {
        LEGENDARY_SOUND = SoundEvent.of(Identifier.of("tropitracker", "legendary_spawn"));
        SHINY_SOUND     = SoundEvent.of(Identifier.of("tropitracker", "shiny_spawn"));
        PARADOX_SOUND   = SoundEvent.of(Identifier.of("tropitracker", "paradox_spawn"));
        INCLUDED_SOUND  = SoundEvent.of(Identifier.of("tropitracker", "included_spawn"));
        BARON_SOUND     = SoundEvent.of(Identifier.of("tropitracker", "baron_spawn"));

        muteKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "TropiTracker Mute",
            InputUtil.Type.KEYSYM,
            186,
            "TropiTracker"
        ));

        muteRareKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "TropiTracker Mute Shiny/Baron",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            "TropiTracker"
        ));

        WorldRenderEvents.LAST.register(ShinyBeamRenderer::render);
        BoardDetector.register();
        CatchDetector.register();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            if (client.world != lastWorld) {
                lastWorld = client.world;
                teleportCooldown = 60;
                pendingEntities.clear();
                seenEntities.clear();
                announcedEntities.clear();
                activeShinyEntities.clear();
                activeBaronEntities.clear();
                loopActive = false;
                activeLoopSound = null;
                activeLoopVolume = 1.0f;
                scanTick = 0;
                soundPlaybackTick = 0;
                lastX = client.player.getX();
                lastY = client.player.getY();
                lastZ = client.player.getZ();
            }

            double currentX = client.player.getX();
            double currentY = client.player.getY();
            double currentZ = client.player.getZ();
            if (lastX != 0 || lastY != 0 || lastZ != 0) {
                double distSq = (currentX - lastX) * (currentX - lastX) +
                                (currentY - lastY) * (currentY - lastY) +
                                (currentZ - lastZ) * (currentZ - lastZ);
                if (distSq > 64) {
                    teleportCooldown = 40;
                }
            }
            lastX = currentX;
            lastY = currentY;
            lastZ = currentZ;

            if (teleportCooldown > 0) {
                teleportCooldown--;
            }

            while (muteRareKey.wasPressed()) {
                rareMuted = !rareMuted;
                if (client.player != null) {
                    client.player.sendMessage(
                        Text.literal(rareMuted
                            ? "§cTropiTracker : Son Shiny/Baron coupé 🔇"
                            : "§aTropiTracker : Son Shiny/Baron activé 🔊"),
                        true
                    );
                }
            }

            while (muteKey.wasPressed()) {
                muted = !muted;
                if (client.player != null) {
                    client.player.sendMessage(
                        Text.literal(muted ? "§cTropiTracker : Son coupé 🔇" : "§aTropiTracker : Son activé 🔊"),
                        true
                    );
                }
            }

            if (!pendingEntities.isEmpty()) {
                java.util.List<java.util.UUID> toRemove = new java.util.ArrayList<>();

                for (java.util.Map.Entry<java.util.UUID, TrackedPending> entry : pendingEntities.entrySet()) {
                    java.util.UUID uuid = entry.getKey();
                    TrackedPending pending = entry.getValue();
                    PokemonEntity pe = pending.entity;

                    // Pokémon du joueur, ou d'un dresseur et non Baron : jamais alerté
                    if (!isAlertable(pe)) {
                        toRemove.add(uuid);
                        seenEntities.add(uuid);
                        continue;
                    }

                    pending.ticksLeft--;

                    if (pending.ticksLeft <= 0) {
                        toRemove.add(uuid);
                        seenEntities.add(uuid);

                        // Uniquement les Pokémon sauvages hors combat
                        if (pe.getBattleId() == null) {
                            handleSpawn(pe);
                        }
                    }
                }

                for (java.util.UUID uuid : toRemove) {
                    pendingEntities.remove(uuid);
                }
            }

            // Scan toutes les secondes : uniquement Pokémon sauvages hors combat
            scanTick++;
            if (scanTick >= 20) {
                scanTick = 0;
                boolean specialFound = false;
                SoundEvent foundSound = null;
                float foundVolume = 1.0f;
                Set<PokemonEntity> currentShinies = new HashSet<>();
                Set<PokemonEntity> currentBarons = new HashSet<>();

                for (Entity e : client.world.getEntities()) {
                    if (!(e instanceof PokemonEntity pe)) continue;
                    if (!isAlertable(pe)) continue;
                    if (pe.getBattleId() != null) continue; // ignoré si en combat

                    if (enableShiny && pe.getPokemon().getShiny()) {
                        currentShinies.add(pe);
                    }

                    if (isBaron(pe)) {
                        currentBarons.add(pe);
                    }

                    SoundEvent detectedSound = getSpecialSound(pe);

                    if (detectedSound != null && !announcedEntities.contains(pe.getUuid())) {
                        handleSpawn(pe);
                    }

                    if (!specialFound && detectedSound != null && !isSilencedRare(detectedSound)) {
                        specialFound = true;
                        foundSound = detectedSound;
                        boolean shinyMatch = pe.getPokemon().getShiny() && enableShiny;
                        boolean baronMatch = isBaron(pe);
                        boolean trackedMatch = isTrackedMatch(pe.getPokemon());
                        foundVolume = shinyMatch ? SHINY_VOLUME
                                    : (baronMatch ? BARON_VOLUME
                                    : (trackedMatch ? TRACKED_VOLUME : 1.0f));
                    }
                }

                activeShinyEntities.clear();
                activeShinyEntities.addAll(currentShinies);

                activeBaronEntities.clear();
                activeBaronEntities.addAll(currentBarons);

                if (specialFound) {
                    activeLoopSound = foundSound;
                    activeLoopVolume = foundVolume;
                    loopActive = true;
                } else {
                    loopActive = false;
                    activeLoopSound = null;
                    activeLoopVolume = 1.0f;
                }
            }

            if (loopActive && !muted && activeLoopSound != null) {
                soundPlaybackTick++;
                if (soundPlaybackTick >= 60) {
                    soundPlaybackTick = 0;
                    client.player.playSound(activeLoopSound, activeLoopVolume, 1.0f);
                }
            } else {
                soundPlaybackTick = 0;
            }
        });

        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!(entity instanceof PokemonEntity pokemonEntity)) return;

            java.util.UUID entityUUID = pokemonEntity.getUuid();
            if (seenEntities.contains(entityUUID) || pendingEntities.containsKey(entityUUID)) return;

            int delay = (teleportCooldown > 0) ? 60 : 20;
            pendingEntities.put(entityUUID, new TrackedPending(pokemonEntity, delay));
        });

        ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (!(entity instanceof PokemonEntity)) return;
            java.util.UUID uuid = entity.getUuid();
            seenEntities.remove(uuid);
            pendingEntities.remove(uuid);
            announcedEntities.remove(uuid);
            activeShinyEntities.remove(entity);
            activeBaronEntities.remove(entity);
        });

        net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (message.trim().equalsIgnoreCase("barondebug")) {
                dumpNearbyNames();
                return false;
            }
            if (message.toLowerCase().startsWith("track ")) {
                String pokemonName = message.substring(6).trim().toLowerCase();
                handleTrackCommand(pokemonName);
                return false;
            }
            return true;
        });
    }

    private void handleTrackCommand(String name) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        if (trackedPokemons.contains(name)) {
            trackedPokemons.remove(name);
            client.player.sendMessage(
                Text.literal("§cTropiTracker : §f" + capitalize(name) + " §cretiré de la liste."),
                false
            );
        } else {
            trackedPokemons.add(name);
            client.player.sendMessage(
                Text.literal("§aTropiTracker : §f" + capitalize(name) + " §aajouté à la liste !"),
                false
            );
        }
    }

    public static void setBoardTargets(Set<String> speciesNames) {
        Set<String> newSet = new HashSet<>();
        for (String speciesName : speciesNames) {
            String lower = speciesName.toLowerCase();
            newSet.add(lower);
            String frenchName = net.minecraft.client.resource.language.I18n.translate("cobblemon.species." + lower + ".name");
            if (!frenchName.equals("cobblemon.species." + lower + ".name")) {
                newSet.add(frenchName.toLowerCase());
            }
        }
        boardTrackedPokemons.clear();
        boardTrackedPokemons.addAll(newSet);

        boardSpeciesCanonical.clear();
        for (String speciesName : speciesNames) {
            boardSpeciesCanonical.add(speciesName.toLowerCase());
        }

        LOGGER.info("Tableau de chasse : {} pokémon trackés automatiquement.", speciesNames.size());
    }

    public static void removeBoardTarget(String speciesName) {
        String lower = speciesName.toLowerCase();
        if (!boardSpeciesCanonical.remove(lower)) return;

        boardTrackedPokemons.remove(lower);
        String frenchName = net.minecraft.client.resource.language.I18n.translate("cobblemon.species." + lower + ".name");
        if (!frenchName.equals("cobblemon.species." + lower + ".name")) {
            boardTrackedPokemons.remove(frenchName.toLowerCase());
        }

        LOGGER.info("Capture confirmée, retiré du tracking automatique : {}", lower);

        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(
                    Text.literal("§aTropiTracker : §f" + capitalize(lower) + " §acapturé, retiré du tableau de chasse."),
                    false
                );
            }
        });
    }

    public static boolean isBoardTracked(String speciesNameLower) {
        return boardSpeciesCanonical.contains(speciesNameLower);
    }

    public static int getBoardTrackedCount() {
        return boardSpeciesCanonical.size();
    }

    public static String getBoardTrackedSpeciesIfUnique() {
        if (boardSpeciesCanonical.size() == 1) {
            return boardSpeciesCanonical.iterator().next();
        }
        return null;
    }

    private static boolean isTracked(String frLower, String speciesName) {
        return trackedPokemons.contains(frLower) || trackedPokemons.contains(speciesName)
            || boardTrackedPokemons.contains(frLower) || boardTrackedPokemons.contains(speciesName);
    }

    public static void recheckAfterBoardUpdate() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        for (Entity e : client.world.getEntities()) {
            if (!(e instanceof PokemonEntity pe)) continue;
            if (!isAlertable(pe)) continue;
            if (pe.getBattleId() != null) continue;
            if (announcedEntities.contains(pe.getUuid())) continue;
            if (!isTrackedMatch(pe.getPokemon())) continue;

            handleSpawn(pe);
        }
    }

    // ------------------------------------------------------------------
    // Détection « baron »
    // ------------------------------------------------------------------

    /**
     * Vrai si le nom affiché du Pokémon contient « baron ». Le surnom posé par
     * le serveur peut arriver soit comme customName (renommage d'entité), soit
     * directement dans le displayName construit par Cobblemon : les deux sont
     * testés, un seul suffit à déclencher l'alerte.
     */
    public static boolean isBaron(PokemonEntity pe) {
        if (!enableBaron || pe == null) return false;

        // Source réelle du libellé sur Tropimon : le « titled name » de
        // Cobblemon, qui porte « Rattata l'Ancien Baron » là où customName,
        // displayName et le nickname ne contiennent que l'espèce.
        String titled = titledNameOf(pe);
        if (titled != null && flatten(titled).contains(BARON_PATTERN)) return true;

        // Les trois autres sources restent testées : elles ne coûtent rien et
        // couvrent le cas où le serveur changerait de méthode de nommage.
        if (nameMatchesBaron(pe.getCustomName())) return true;
        if (nameMatchesBaron(pe.getDisplayName())) return true;
        String nick = nicknameOf(pe.getPokemon());
        return nick != null && flatten(nick).contains(BARON_PATTERN);
    }

    /**
     * Seuls les Pokémon sauvages sont alertés. Les Barons en font partie : ils
     * sont sauvages et capturables, d'où le maintien du filtre strict — un
     * Baron déjà capturé par un autre joueur ne doit pas déclencher l'alerte.
     */
    private static boolean isAlertable(PokemonEntity pe) {
        if (pe == null) return false;
        if (pe.getOwnerUuid() != null) return false;
        return pe.getPokemon().getOwnerUUID() == null;
    }

    /**
     * Lu par réflexion plutôt qu'en appel direct : getTitledName() appartient à
     * Cobblemon, pas à Minecraft, et une évolution du mod ne doit pas empêcher
     * TropiTracker de démarrer. La méthode est résolue une seule fois.
     */
    private static java.lang.reflect.Method titledNameMethod = null;
    private static boolean titledNameResolved = false;

    public static String titledNameOf(PokemonEntity pe) {
        if (pe == null) return null;
        if (!titledNameResolved) {
            titledNameResolved = true;
            try {
                titledNameMethod = pe.getClass().getMethod("getTitledName");
                LOGGER.info("getTitledName() disponible : détection baron active.");
            } catch (Throwable t) {
                LOGGER.info("getTitledName() indisponible, repli sur les noms d'entité.");
                titledNameMethod = null;
            }
        }
        if (titledNameMethod == null) return null;
        try {
            Object v = titledNameMethod.invoke(pe);
            if (v instanceof Text text) return text.getString();
            if (v != null) return String.valueOf(v);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Le surnom Cobblemon est lu par réflexion : selon la version du mod la
     * méthode getNickname() peut manquer ou changer de type de retour, et une
     * absence ne doit pas empêcher le reste de la détection de fonctionner.
     */
    private static java.lang.reflect.Method nicknameMethod = null;
    private static boolean nicknameMethodResolved = false;

    private static String nicknameOf(Pokemon pokemon) {
        if (pokemon == null) return null;
        if (!nicknameMethodResolved) {
            nicknameMethodResolved = true;
            try {
                nicknameMethod = pokemon.getClass().getMethod("getNickname");
            } catch (Throwable t) {
                LOGGER.info("getNickname() indisponible sur Pokemon, détection baron basée sur le nom d'entité seul.");
                nicknameMethod = null;
            }
        }
        if (nicknameMethod == null) return null;
        try {
            Object value = nicknameMethod.invoke(pokemon);
            if (value instanceof Text text) return text.getString();
            if (value != null) return value.toString();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Diagnostic. Le libellé n'est ni dans customName, ni dans displayName, ni
     * dans le nickname : on ratisse donc beaucoup plus large. Trois angles morts
     * de la version précédente sont corrigés ici — le DataTracker est lu sur
     * TOUTES les entités (une entité d'affichage porte son texte là, pas dans
     * son nom), plus aucun plafond sur le nombre de Pokémon inspectés, et la
     * recherche porte aussi sur « ancien ».
     */
    private static final String[] BARON_HINTS = { "baron", "ancien" };

    private static void dumpNearbyNames() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;

        java.util.List<String> hits = new java.util.ArrayList<>();
        double px = client.player.getX(), py = client.player.getY(), pz = client.player.getZ();

        java.util.List<Entity> near = new java.util.ArrayList<>();
        java.util.Map<String, Integer> typeCount = new java.util.TreeMap<>();

        for (Entity e : client.world.getEntities()) {
            double dx = e.getX() - px, dy = e.getY() - py, dz = e.getZ() - pz;
            if (dx * dx + dy * dy + dz * dz > 64 * 64) continue;
            near.add(e);
            typeCount.merge(String.valueOf(e.getType()), 1, Integer::sum);
        }

        LOGGER.info("BARONDEBUG {} entites dans 64 blocs, par type : {}", near.size(), typeCount);

        PokemonEntity closest = null;
        double closestDist = Double.MAX_VALUE;
        int pokemonCount = 0;
        java.util.List<String> speciesSeen = new java.util.ArrayList<>();

        for (Entity e : near) {
            String type = String.valueOf(e.getType());

            check(hits, type + ".customName", e.getCustomName());
            check(hits, type + ".displayName", e.getDisplayName());
            check(hits, type + ".name", e.getName());

            // DataTracker de TOUTE entité : c'est là que vit le texte d'un display
            scanTracker(hits, type + ".datatracker", e);

            if (e instanceof PokemonEntity pe) {
                pokemonCount++;
                speciesSeen.add(pe.getPokemon().getSpecies().getName());

                java.util.UUID owner = pe.getOwnerUuid() != null
                    ? pe.getOwnerUuid() : pe.getPokemon().getOwnerUUID();
                LOGGER.info("BARONDEBUG {} | titled='{}' | owner={} | battle={} | isBaron={} | alertable={}",
                    pe.getPokemon().getSpecies().getName(),
                    String.valueOf(titledNameOf(pe)),
                    String.valueOf(owner),
                    String.valueOf(pe.getBattleId()),
                    isBaron(pe), isAlertable(pe));

                String tag = pe.getPokemon().getSpecies().getName();
                scanObject(hits, "Pokemon[" + tag + "]", pe.getPokemon());
                scanObject(hits, "PokemonEntity[" + tag + "]", pe);

                double dx = pe.getX() - px, dy = pe.getY() - py, dz = pe.getZ() - pz;
                double d = dx * dx + dy * dy + dz * dz;
                if (d < closestDist) { closestDist = d; closest = pe; }
            }
        }

        LOGGER.info("BARONDEBUG {} Pokemon inspectes : {}", pokemonCount, speciesSeen);

        for (String h : hits) {
            LOGGER.info("BARONDEBUG TROUVE >>> {}", h);
        }

        if (!hits.isEmpty()) {
            client.player.sendMessage(Text.literal("§a" + hits.size() + " piste(s) :"), false);
            int shown = 0;
            for (String h : hits) {
                client.player.sendMessage(Text.literal("§c👑 §f" + h), false);
                if (++shown >= 10) break;
            }
        } else {
            client.player.sendMessage(Text.literal(
                "§cRien trouvé (§f" + near.size() + "§c entités, §f" + pokemonCount + "§c Pokémon : §f"
                + String.join(", ", speciesSeen) + "§c)."), false);

            // Vidage intégral du Pokémon le plus proche vers les logs, pour
            // identifier le champ porteur même sans correspondance textuelle.
            if (closest != null) {
                LOGGER.info("BARONDEBUG === VIDAGE COMPLET de {} ===",
                    closest.getPokemon().getSpecies().getName());
                scanObject(hits, "DUMP.Pokemon", closest.getPokemon(), true);
                scanObject(hits, "DUMP.PokemonEntity", closest);
                scanTracker(hits, "DUMP.datatracker", closest, true);
                client.player.sendMessage(Text.literal(
                    "§7Vidage complet de §f" + closest.getPokemon().getSpecies().getName()
                    + " §7écrit dans les logs."), false);
            }
        }
        client.player.sendMessage(Text.literal("§7Logs : filtre BARONDEBUG."), false);
    }

    private static void scanObject(java.util.List<String> hits, String prefix, Object target) {
        scanObject(hits, prefix, target, false);
    }

    /**
     * Invoque tous les accesseurs publics sans argument de l'objet et examine
     * le résultat. logAll force l'écriture de chaque valeur dans les logs, même
     * sans correspondance : utile quand on ignore encore quel champ chercher.
     */
    private static void scanObject(java.util.List<String> hits, String prefix, Object target, boolean logAll) {
        if (target == null) return;
        for (java.lang.reflect.Method m : target.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            String n = m.getName();
            if (n.equals("getClass") || n.equals("hashCode") || n.equals("notify") || n.equals("notifyAll")) continue;
            if (!n.startsWith("get") && !n.startsWith("is") && !n.equals("toString")) continue;
            try {
                Object v = m.invoke(target);
                if (v == null) continue;
                String str = stringify(v);
                if (str.isEmpty()) continue;
                if (logAll) LOGGER.info("BARONDEBUG   {}.{} = {}", prefix, n,
                    str.length() > 300 ? str.substring(0, 300) + "…" : str);
                check(hits, prefix + "." + n, str);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void scanTracker(java.util.List<String> hits, String prefix, Entity e) {
        scanTracker(hits, prefix, e, false);
    }

    /**
     * Les noms de méthodes du DataTracker sont remappés à l'exécution, donc on
     * invoque tout ce qui ne prend pas d'argument et on inspecte le contenu.
     */
    private static void scanTracker(java.util.List<String> hits, String prefix, Entity e, boolean logAll) {
        try {
            Object dt = e.getDataTracker();
            if (dt == null) return;
            for (java.lang.reflect.Method m : dt.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (m.getName().equals("getClass")) continue;
                try {
                    Object v = m.invoke(dt);
                    if (v == null) continue;
                    if (v instanceof Iterable<?> it) {
                        for (Object o : it) {
                            String str = stringify(o);
                            if (logAll) LOGGER.info("BARONDEBUG   {} = {}", prefix, str);
                            check(hits, prefix, str);
                        }
                    } else {
                        String str = stringify(v);
                        if (logAll) LOGGER.info("BARONDEBUG   {} = {}", prefix, str);
                        check(hits, prefix, str);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            LOGGER.info("BARONDEBUG datatracker illisible sur {} : {}", prefix, String.valueOf(t));
        }
    }

    private static String stringify(Object v) {
        if (v == null) return "";
        if (v instanceof Text text) return text.getString();
        if (v instanceof java.util.Optional<?> opt) return opt.isPresent() ? stringify(opt.get()) : "";
        String s = String.valueOf(v);
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    /** Retient la source si sa valeur contient un des indices recherchés. */
    private static void check(java.util.List<String> hits, String source, Object value) {
        if (value == null) return;
        String str = stringify(value);
        if (str.isEmpty()) return;
        String flat = flatten(str);
        boolean match = false;
        for (String hint : BARON_HINTS) {
            if (flat.contains(hint)) { match = true; break; }
        }
        if (!match) return;
        String clean = str.replaceAll("§.", "").trim();
        if (clean.length() > 200) clean = clean.substring(0, 200) + "…";
        String line = source + " = \"" + clean + "\"";
        if (!hits.contains(line)) hits.add(line);
    }

    private static boolean nameMatchesBaron(Text text) {
        if (text == null) return false;
        String raw = text.getString();
        if (raw == null || raw.isEmpty()) return false;
        return flatten(raw).contains(BARON_PATTERN);
    }

    /** Nom lisible du Pokémon, codes couleur retirés, pour l'affichage. */
    public static String getDisplayLabel(PokemonEntity pe) {
        String titled = titledNameOf(pe);
        if (titled != null && !titled.isEmpty()) {
            return titled.replaceAll("§.", "").trim();
        }
        Text name = pe.getCustomName() != null ? pe.getCustomName() : pe.getDisplayName();
        if (name == null) return "?";
        return name.getString().replaceAll("§.", "").trim();
    }

    /** Minuscules, sans accents ni codes couleur Minecraft. */
    private static String flatten(String s) {
        String stripped = s.replaceAll("§.", "");
        String noAccents = java.text.Normalizer
            .normalize(stripped, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
        return noAccents.toLowerCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------------

    private static void handleSpawn(PokemonEntity pe) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (announcedEntities.contains(pe.getUuid())) return;

        Pokemon pokemon = pe.getPokemon();

        if (!isAlertable(pe)) {
            return;
        }

        SoundEvent sound = getSpecialSound(pe);
        if (sound == null) {
            return;
        }

        announcedEntities.add(pe.getUuid());

        String speciesName = pokemon.getSpecies().getName().toLowerCase();
        String frenchName = net.minecraft.client.resource.language.I18n.translate("cobblemon.species." + speciesName + ".name");
        if (frenchName.equals("cobblemon.species." + speciesName + ".name")) {
            frenchName = pokemon.getSpecies().getName();
        }

        Set<String> labels = pokemon.getSpecies().getLabels();
        boolean isShiny = pokemon.getShiny();
        boolean baron = isBaron(pe);
        String message = "";

        // Mention ajoutée aux alertes plus prioritaires, pour ne pas perdre l'info
        String baronSuffix = baron ? " §c👑 BARON" : "";

        String frLower = frenchName.toLowerCase();
        if (isTracked(frLower, speciesName)) {
            message = "§e🎯 Pokémon recherché apparu : §f" + frenchName + (isShiny ? " §6✨ SHINY ✨" : "") + baronSuffix;
        } else if (isShiny && enableShiny) {
            message = "§6✨ Pokémon Shiny sauvage apparu : §e" + frenchName + " §6✨" + baronSuffix;
        } else if (baron) {
            message = "§c👑 Baron sauvage apparu : §f" + getDisplayLabel(pe) + " §c👑";
        } else if (enableLegendary && hasLabel(labels, LEGENDARY_LABELS)) {
            message = "§c⚡ Légendaire sauvage apparu : §f" + frenchName + " §c⚡";
        } else if (enableMythic && hasLabel(labels, MYTHIC_LABELS)) {
            message = "§d✦ Mystique sauvage apparu : §f" + frenchName + " §d✦";
        } else if (enableUltraBeast && hasLabel(labels, ULTRA_BEAST_LABELS)) {
            message = "§b◆ Ultra-Chimère sauvage apparu : §f" + frenchName + " §b◆";
        } else if (enableParadox && hasLabel(labels, PARADOX_LABELS)) {
            message = "§5⚔ Pokémon Paradoxe sauvage apparu : §f" + frenchName + " §5⚔";
        }

        if (!message.isEmpty() && !muted) {
            SoundEvent finalSound = sound;
            boolean bigAlert = isShiny && enableShiny;
            boolean baronAlert = baron && !bigAlert;
            boolean trackedAlert = isTrackedMatch(pokemon);
            String finalDisplayName = frenchName;
            String finalBaronLabel = getDisplayLabel(pe);
            String finalMessage = message;

            client.execute(() -> {
                if (bigAlert) {
                    client.inGameHud.setTitle(Text.literal("§6✨ SHINY ✨"));
                    client.inGameHud.setSubtitle(Text.literal("§e" + finalDisplayName));
                    client.inGameHud.setTitleTicks(5, 70, 20);
                    if (!isSilencedRare(finalSound)) {
                        client.player.playSound(finalSound, SHINY_VOLUME, 1.0f);
                    }
                } else if (baronAlert) {
                    client.inGameHud.setTitle(Text.literal("§c👑 BARON 👑"));
                    client.inGameHud.setSubtitle(Text.literal("§c" + finalBaronLabel));
                    client.inGameHud.setTitleTicks(5, 70, 20);
                    client.player.sendMessage(Text.literal(finalMessage), false);
                    if (!isSilencedRare(finalSound)) {
                        client.player.playSound(finalSound, BARON_VOLUME, 1.0f);
                    }
                } else {
                    float volume = trackedAlert ? TRACKED_VOLUME : 1.0f;
                    client.player.sendMessage(Text.literal(finalMessage), false);
                    if (!isSilencedRare(finalSound)) {
                        client.player.playSound(finalSound, volume, 1.0f);
                    }
                }
            });
        }
    }

    private static SoundEvent getSpecialSound(PokemonEntity pe) {
        Pokemon pokemon = pe.getPokemon();

        String speciesName = pokemon.getSpecies().getName().toLowerCase();
        String frenchName = net.minecraft.client.resource.language.I18n.translate("cobblemon.species." + speciesName + ".name");
        if (frenchName.equals("cobblemon.species." + speciesName + ".name")) {
            frenchName = pokemon.getSpecies().getName();
        }
        String frLower = frenchName.toLowerCase();

        Set<String> labels = pokemon.getSpecies().getLabels();
        boolean isShiny = pokemon.getShiny();

        if (isTracked(frLower, speciesName)) {
            return INCLUDED_SOUND;
        } else if (isShiny && enableShiny) {
            return SHINY_SOUND;
        } else if (isBaron(pe)) {
            return BARON_SOUND;
        } else if (enableLegendary && hasLabel(labels, LEGENDARY_LABELS)) {
            return LEGENDARY_SOUND;
        } else if (enableMythic && hasLabel(labels, MYTHIC_LABELS)) {
            return LEGENDARY_SOUND;
        } else if (enableUltraBeast && hasLabel(labels, ULTRA_BEAST_LABELS)) {
            return INCLUDED_SOUND;
        } else if (enableParadox && hasLabel(labels, PARADOX_LABELS)) {
            return PARADOX_SOUND;
        }
        return null;
    }

    /** Vrai si le son est celui d'un Shiny ou d'un Baron et que ces sons sont coupés. */
    private static boolean isSilencedRare(SoundEvent sound) {
        return rareMuted && sound != null && (sound == SHINY_SOUND || sound == BARON_SOUND);
    }

    private static boolean hasLabel(Set<String> labels, Set<String> targets) {
        for (String label : labels) {
            if (targets.contains(label.toLowerCase())) return true;
        }
        return false;
    }

    private static boolean isTrackedMatch(Pokemon pokemon) {
        if (trackedPokemons.isEmpty() && boardTrackedPokemons.isEmpty()) return false;
        String speciesName = pokemon.getSpecies().getName().toLowerCase();
        String frenchName = net.minecraft.client.resource.language.I18n.translate("cobblemon.species." + speciesName + ".name");
        if (frenchName.equals("cobblemon.species." + speciesName + ".name")) {
            frenchName = pokemon.getSpecies().getName();
        }
        String frLower = frenchName.toLowerCase();
        return isTracked(frLower, speciesName);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
