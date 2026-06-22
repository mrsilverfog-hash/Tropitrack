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
    private static final Set<java.util.UUID> seenEntities = new HashSet<>();

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

        // Rendu du faisceau doré au-dessus des Pokémon shiny, visible à travers les blocs
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ShinyBeamRenderer::render);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            // Réinitialisation complète en cas de changement de monde
            if (client.world != lastWorld) {
                lastWorld = client.world;
                teleportCooldown = 60;
                pendingEntities.clear();
                seenEntities.clear();
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

                    if (!specialFound) {
                        SoundEvent detectedSound = getSpecialSound(pe.getPokemon());
                        if (detectedSound != null) {
                            specialFound = true;
                            foundSound = detectedSound;
                            foundVolume = (pe.getPokemon().getShiny() && enableShiny) ? 3.0f : 1.0f;
                        }
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

    private void handleSpawn(PokemonEntity pe) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        Pokemon pokemon = pe.getPokemon();
        if (pe.getOwnerUuid() != null || pokemon.getOwnerUUID() != null) return;

        SoundEvent sound = getSpecialSound(pokemon);
        if (sound == null) return;

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
        if (!trackedPokemons.isEmpty() && (trackedPokemons.contains(frLower) || trackedPokemons.contains(speciesName))) {
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
            String finalDisplayName = frenchName;
            String finalMessage = message;

            client.execute(() -> {
                if (bigAlert) {
                    // Alerte plein écran pour les shiny (équivalent de la commande /title)
                    client.inGameHud.getTitleRenderer().setTitle(Text.literal("§6✨ SHINY ✨"));
                    client.inGameHud.getTitleRenderer().setSubtitle(Text.literal("§e" + finalDisplayName));
                    client.inGameHud.getTitleRenderer().setTitleTicks(5, 70, 20); // fade-in, maintien, fade-out
                    client.player.playSound(finalSound, 3.0f, 1.0f); // volume x3
                } else {
                    client.player.sendMessage(Text.literal(finalMessage), false);
                    client.player.playSound(finalSound, 1.0f, 1.0f);
                }
            });
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

        if (!trackedPokemons.isEmpty() && (trackedPokemons.contains(frLower) || trackedPokemons.contains(speciesName))) {
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

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
