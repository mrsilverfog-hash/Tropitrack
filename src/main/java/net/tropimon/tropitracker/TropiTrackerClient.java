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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class TropiTrackerClient implements ClientModInitializer {

    private static KeyBinding muteKey;
    private static boolean muted = false;

    public static SoundEvent LEGENDARY_SOUND;
    public static SoundEvent SHINY_SOUND;
    public static SoundEvent PARADOX_SOUND;
    public static SoundEvent INCLUDED_SOUND;

    public static boolean enableLegendary  = true;
    public static boolean enableMythic     = true;
    public static boolean enableUltraBeast = true;
    public static boolean enableParadox    = true;
    public static boolean enableShiny      = true;

    private static final Set<String> trackedPokemons = new HashSet<>();
    // Pokémon trackés automatiquement depuis le tableau de chasse (rafraîchi à chaque ouverture du tableau)
    private static final Set<String> boardTrackedPokemons = new HashSet<>();
    // Version "canonique" (nom anglais uniquement) du set ci-dessus, utilisée par CatchDetector pour le comptage/retrait
    private static final Set<String> boardSpeciesCanonical = new HashSet<>();
    private static final Set<java.util.UUID> seenEntities = new HashSet<>();
    // Pokémon pour lesquels l'alerte (message + son ponctuel) a déjà été envoyée,
    // que ce soit via l'apparition fraîche (ENTITY_LOAD) ou via le scan périodique
    // (cas d'un pokémon déjà présent avant que le tracking ne se déclenche)
    private static final Set<java.util.UUID> announcedEntities = new HashSet<>();

    // Pokémon shiny actuellement chargés, utilisé par ShinyBeamRenderer pour dessiner le faisceau
    private static final Set<PokemonEntity> activeShinyEntities = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static Set<PokemonEntity> getActiveShinyEntities() {
        return activeShinyEntities;
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

    // Gestion de la boucle de rappel sonore
    private static int scanTick = 0;
    private static int soundPlaybackTick = 0;
    private static SoundEvent activeLoopSound = null;
    private static float activeLoopVolume = 1.0f;
    private static boolean loopActive = false;

    private static final float SHINY_VOLUME = 3.0f;
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

        muteKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "TropiTracker Mute",
            InputUtil.Type.KEYSYM,
            186, // Touche ù
            "TropiTracker"
        ));

        // Rendu du faisceau doré vers les Pokémon shiny, visible à travers les blocs
        WorldRenderEvents.LAST.register(ShinyBeamRenderer::render);

        // Détection automatique du tableau de chasse (TropimodClient)
        BoardDetector.register();

        // Détection de capture pour retirer automatiquement les cibles du tableau une fois capturées
        CatchDetector.register();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            // Réinitialisation complète en cas de changement de monde
            if (client.world != lastWorld) {
                lastWorld = client.world;
                teleportCooldown = 60;
                pendingEntities.clear();
                seenEntities.clear();
                announcedEntities.clear();
                activeShinyEntities.clear();
                loopActive = false;
                activeLoopSound = null;
                activeLoopVolume = 1.0f;
                scanTick = 0;
                soundPlaybackTick = 0;
                lastX = client.player.getX();
                lastY = client.player.getY();
                lastZ = client.player.getZ();
            }

            // Détection des téléportations
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

            while (muteKey.wasPressed()) {
                muted = !muted;
                if (client.player != null) {
                    client.player.sendMessage(
                        Text.literal(muted ? "§cTropiTracker : Son coupé 🔇" : "§aTropiTracker : Son activé 🔊"),
                        true
                    );
                }
            }

            // Gestion de la file d'attente avec vérification d'appartenance renforcée
            if (!pendingEntities.isEmpty()) {
                java.util.List<java.util.UUID> toRemove = new java.util.ArrayList<>();

                for (java.util.Map.Entry<java.util.UUID, TrackedPending> entry : pendingEntities.entrySet()) {
                    java.util.UUID uuid = entry.getKey();
                    TrackedPending pending = entry.getValue();
                    PokemonEntity pe = pending.entity;

                    if (pe.getOwnerUuid() != null || pe.getPokemon().getOwnerUUID() != null) {
                        // Pokémon d'un dresseur — déclencher uniquement si shiny en combat spectateur
                        if (!announcedEntities.contains(pe.getUuid())
                                && pe.getPokemon().getShiny()
                                && pe.getBattleId() != null
                                && isBattleScreenOpen()) {
                            handleBattleShiny(pe);
                        }
                        toRemove.add(uuid);
                        seenEntities.add(uuid);
                        continue;
                    }

                    pending.ticksLeft--;

                    if (pending.ticksLeft <= 0) {
                        toRemove.add(uuid);
                        seenEntities.add(uuid);

                        if (pe.getBattleId() == null) {
                            handleSpawn(pe);
                        }
                    }
                }

                for (java.util.UUID uuid : toRemove) {
                    pendingEntities.remove(uuid);
                }
            }

            // Scan du monde réel toutes les secondes (20 ticks) : met à jour la boucle sonore
            // et la liste des Pokémon shiny actifs (pour le faisceau)
            scanTick++;
            if (scanTick >= 20) {
                scanTick = 0;
                boolean specialFound = false;
                SoundEvent foundSound = null;
                float foundVolume = 1.0f;
                Set<PokemonEntity> currentShinies = new HashSet<>();

                for (Entity e : client.world.getEntities()) {
                    if (!(e instanceof PokemonEntity pe)) continue;
                    if (pe.getOwnerUuid() != null || pe.getPokemon().getOwnerUUID() != null) continue;

                    if (enableShiny && pe.getPokemon().getShiny()) {
                        currentShinies.add(pe);
                    }

                    SoundEvent detectedSound = getSpecialSound(pe.getPokemon());

                    // Annonce (message + son ponctuel) tout pokémon spécial/tracké pas encore signalé,
                    // y compris ceux déjà présents au moment où ils deviennent trackés (tableau de chasse)
                    if (detectedSound != null && !announcedEntities.contains(pe.getUuid())) {
                        handleSpawn(pe);
                    }

                    if (!specialFound && detectedSound != null) {
                        specialFound = true;
                        foundSound = detectedSound;
                        boolean shinyMatch = pe.getPokemon().getShiny() && enableShiny;
                        boolean trackedMatch = isTrackedMatch(pe.getPokemon());
                        foundVolume = shinyMatch ? SHINY_VOLUME : (trackedMatch ? TRACKED_VOLUME : 1.0f);
                    }
                }

                activeShinyEntities.clear();
                activeShinyEntities.addAll(currentShinies);

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

            // Répétition du son toutes les 3 secondes si le Pokémon est toujours là
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
        });

        net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (message.toLowerCase().startsWith("track ")) {
                String pokemonName = message.substring(6).trim().toLowerCase();
                handleTrackCommand(pokemonName);
                return false;
            }
            return true;
        });
    }

    /**
     * Retourne true si l'écran de combat Cobblemon est actuellement affiché
     * (le joueur regarde un combat en tant que spectateur ou participant).
     */
    private static boolean isBattleScreenOpen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen == null) return false;
        String screenClass = client.currentScreen.getClass().getName();
        return screenClass.contains("BattleScreen");
    }

    /**
     * Déclenche l'alerte shiny pour un Pokémon de dresseur envoyé en combat,
     * uniquement lorsque le joueur regarde ce combat (écran de combat ouvert).
     * Pas de faisceau ni de boucle sonore — uniquement le titre plein écran + son ponctuel.
     */
    private static void handleBattleShiny(PokemonEntity pe) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (announcedEntities.contains(pe.getUuid())) return;

        announcedEntities.add(pe.getUuid());

        Pokemon pokemon = pe.getPokemon();
        String speciesName = pokemon.getSpecies().getName().toLowerCase();
        String frenchName = net.minecraft.client.resource.language.I18n.translate("cobblemon.species." + speciesName + ".name");
        if (frenchName.equals("cobblemon.species." + speciesName + ".name")) {
            frenchName = pokemon.getSpecies().getName();
        }
        String finalDisplayName = frenchName;

        client.execute(() -> {
            if (client.player == null || muted) return;
            client.inGameHud.setTitle(Text.literal("§6✨ SHINY EN COMBAT ✨"));
            client.inGameHud.setSubtitle(Text.literal("§e" + finalDisplayName));
            client.inGameHud.setTitleTicks(5, 70, 20);
            client.player.playSound(SHINY_SOUND, SHINY_VOLUME, 1.0f);
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

    /**
     * Appelé par BoardDetector quand le tableau de chasse est lu.
     * Remplace entièrement la liste des pokémon trackés depuis le tableau
     * (les pokémon trackés manuellement via /track ne sont pas affectés).
     */
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

        System.out.println("[TropiTracker] Tableau de chasse : " + speciesNames.size() + " pokémon trackés automatiquement.");
    }

    /**
     * Retire un pokémon du tracking automatique (appelé par CatchDetector une fois la capture confirmée).
     * Ne touche pas aux pokémon trackés manuellement via /track.
     */
    public static void removeBoardTarget(String speciesName) {
        String lower = speciesName.toLowerCase();
        if (!boardSpeciesCanonical.remove(lower)) return;

        boardTrackedPokemons.remove(lower);
        String frenchName = net.minecraft.client.resource.language.I18n.translate("cobblemon.species." + lower + ".name");
        if (!frenchName.equals("cobblemon.species." + lower + ".name")) {
            boardTrackedPokemons.remove(frenchName.toLowerCase());
        }

        System.out.println("[TropiTracker] Capture confirmée, retiré du tracking automatique : " + lower);

        MinecraftClient client = MinecraftClient.getInstance();
        // client.execute() diffère l'envoi au prochain tick : on ne doit JAMAIS ajouter
        // un message au chat pendant le traitement d'un message entrant, sous peine de
        // ConcurrentModificationException -> déconnexion du serveur
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

    /** Retourne le nom de l'espèce si une seule cible reste trackée depuis le tableau, sinon null. */
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

    /**
     * Rescanne tous les Pokémon déjà présents dans le monde après une mise à jour du tableau de chasse.
     * Sans ça, un Pokémon déjà chargé AVANT que le tableau ne soit lu ne déclencherait jamais
     * l'alerte (ENTITY_LOAD ne se reproduit pas), même s'il devient une cible trackée :
     * seul le son de la boucle périodique se déclencherait, sans le message associé.
     */
    public static void recheckAfterBoardUpdate() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        for (Entity e : client.world.getEntities()) {
            if (!(e instanceof PokemonEntity pe)) continue;
            if (pe.getOwnerUuid() != null || pe.getPokemon().getOwnerUUID() != null) continue;
            if (pe.getBattleId() != null) continue;
            if (announcedEntities.contains(pe.getUuid())) continue;
            if (!isTrackedMatch(pe.getPokemon())) continue;

            handleSpawn(pe);
        }
    }

    private static void handleSpawn(PokemonEntity pe) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        // Garde anti-doublon : un pokémon déjà annoncé (par n'importe quel chemin :
        // file d'attente ENTITY_LOAD ou scan périodique) ne doit jamais l'être une 2e fois
        if (announcedEntities.contains(pe.getUuid())) return;

        Pokemon pokemon = pe.getPokemon();
        System.out.println("[DEBUG_SHINY] handleSpawn appelé pour : " + pokemon.getSpecies().getName()
            + " | shiny=" + pokemon.getShiny()
            + " | ownerEntity=" + pe.getOwnerUuid()
            + " | ownerPokemon=" + pokemon.getOwnerUUID());

        if (pe.getOwnerUuid() != null || pokemon.getOwnerUUID() != null) {
            System.out.println("[DEBUG_SHINY] Stoppé : Pokémon possédé");
            return;
        }

        SoundEvent sound = getSpecialSound(pokemon);
        System.out.println("[DEBUG_SHINY] enableShiny=" + enableShiny + " | sound=" + sound);
        if (sound == null) {
            System.out.println("[DEBUG_SHINY] Stoppé : sound est null");
            return;
        }

        // Marqué comme signalé avant tout traitement, pour que le scan périodique
        // ne ré-annonce pas la même entité juste après
        announcedEntities.add(pe.getUuid());

        String speciesName = pokemon.getSpecies().getName().toLowerCase();

        // Traduction automatique via le système officiel du jeu
        String frenchName = net.minecraft.client.resource.language.I18n.translate("cobblemon.species." + speciesName + ".name");
        if (frenchName.equals("cobblemon.species." + speciesName + ".name")) {
            frenchName = pokemon.getSpecies().getName();
        }

        Set<String> labels = pokemon.getSpecies().getLabels();
        boolean isShiny = pokemon.getShiny();
        String message = "";

        String frLower = frenchName.toLowerCase();
        if (isTracked(frLower, speciesName)) {
            message = "§e🎯 Pokémon recherché apparu : §f" + frenchName + (isShiny ? " §6✨ SHINY ✨" : "");
        } else if (isShiny && enableShiny) {
            message = "§6✨ Pokémon Shiny sauvage apparu : §e" + frenchName + " §6✨";
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
            boolean trackedAlert = isTrackedMatch(pokemon);
            String finalDisplayName = frenchName;
            String finalMessage = message;
            System.out.println("[DEBUG_SHINY] Envoi alerte : bigAlert=" + bigAlert + " | trackedAlert=" + trackedAlert + " | message=" + finalMessage);

            client.execute(() -> {
                if (bigAlert) {
                    // Alerte plein écran pour les shiny (équivalent de la commande /title)
                    client.inGameHud.setTitle(Text.literal("§6✨ SHINY ✨"));
                    client.inGameHud.setSubtitle(Text.literal("§e" + finalDisplayName));
                    client.inGameHud.setTitleTicks(5, 70, 20); // fade-in, maintien, fade-out
                    client.player.playSound(finalSound, SHINY_VOLUME, 1.0f);
                } else {
                    float volume = trackedAlert ? TRACKED_VOLUME : 1.0f;
                    client.player.sendMessage(Text.literal(finalMessage), false);
                    client.player.playSound(finalSound, volume, 1.0f);
                }
            });
        } else {
            System.out.println("[DEBUG_SHINY] Alerte bloquée : message vide=" + message.isEmpty() + " | muted=" + muted);
        }
    }

    private static SoundEvent getSpecialSound(Pokemon pokemon) {
        String speciesName = pokemon.getSpecies().getName().toLowerCase();

        // Traduction automatique pour garder la cohérence du scanner
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
