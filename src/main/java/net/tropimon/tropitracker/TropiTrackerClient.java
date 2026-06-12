package net.tropimon.tropitracker;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
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
    
    private static class TrackedPending {
        PokemonEntity entity;
        int ticksLeft;
        TrackedPending(PokemonEntity entity, int ticksLeft) {
            this.entity = entity;
            this.ticksLeft = ticksLeft;
        }
    }
    
    private static final java.util.Map<java.util.UUID, TrackedPending> pendingEntities = new java.util.HashMap<>();

    private static final int LOOP_INTERVAL = 60;
    private static int loopTick = 0;
    private static SoundEvent activeLoopSound = null;
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

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            if (client.world != lastWorld) {
                lastWorld = client.world;
                teleportCooldown = 60;
                pendingEntities.clear();
                seenEntities.clear();
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
                    Pokemon poke = pending.entity.getPokemon();
                    
                    if (poke.getOwnerUUID() != null) {
                        toRemove.add(uuid);
                        seenEntities.add(uuid);
                        continue;
                    }
                    
                    pending.ticksLeft--;
                    
                    if (pending.ticksLeft <= 0) {
                        toRemove.add(uuid);
                        seenEntities.add(uuid);
                        
                        if (pending.entity.getBattleId() == null) {
                            handleSpawn(poke);
                        }
                    }
                }
                
                for (java.util.UUID uuid : toRemove) {
                    pendingEntities.remove(uuid);
                }
            }

            if (loopActive && !muted && activeLoopSound != null && client.player != null) {
                loopTick++;
                if (loopTick >= LOOP_INTERVAL) {
                    loopTick = 0;
                    client.player.playSound(activeLoopSound, 1.0f, 1.0f);
                }
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
            
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) return;

            boolean stillPresent = false;
            for (Entity e : client.world.getEntities()) {
                if (e == entity || !(e instanceof PokemonEntity pe)) continue;
                if (pe.getPokemon().getOwnerUUID() != null) continue;
                String name = pe.getPokemon().getSpecies().getName().toLowerCase();
                String fr = FrenchNames.get(name);
                if (trackedPokemons.contains(name) ||
                    (fr != null && trackedPokemons.contains(fr.toLowerCase())) ||
                    isSpecialPokemon(pe.getPokemon())) {
                    stillPresent = true;
                    break;
                }
            }
            if (!stillPresent) {
                loopActive = false;
                activeLoopSound = null;
            }
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
            loopActive = false;
            activeLoopSound = null;
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

        if (!trackedPokemons.isEmpty()) {
            client.player.sendMessage(
                Text.literal("§6Pokémon suivis : §f" + String.join(", ", trackedPokemons)),
                false
            );
        }
    }

    private void handleSpawn(Pokemon pokemon) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        String speciesName = pokemon.getSpecies().getName().toLowerCase();
        String frenchName = FrenchNames.get(speciesName);
        if (frenchName == null) frenchName = pokemon.getSpecies().getName();

        Set<String> labels = pokemon.getSpecies().getLabels();
        boolean isShiny = pokemon.getShiny();

        SoundEvent sound = null;
        String message = null;

        String frLower = frenchName.toLowerCase();
        if (!trackedPokemons.isEmpty() &&
            (trackedPokemons.contains(frLower) || trackedPokemons.contains(speciesName))) {
            sound = INCLUDED_SOUND;
            message = "§e🎯 Pokémon recherché apparu : §f" + frenchName + (isShiny ? " §6✨ SHINY ✨" : "");
        } else if (isShiny && enableShiny) {
            sound = SHINY_SOUND;
            message = "§6✨ Pokémon Shiny sauvage apparu : §e" + frenchName + " §6✨";
        } else if (enableLegendary && hasLabel(labels, LEGENDARY_LABELS)) {
            sound = LEGENDARY_SOUND;
            message = "§c⚡ Légendaire sauvage apparu : §f" + frenchName + " §c⚡";
        } else if (enableMythic && hasLabel(labels, MYTHIC_LABELS)) {
            sound = LEGENDARY_SOUND;
            message = "§d✦ Mystique sauvage apparu : §f" + frenchName + " §d✦";
        } else if (enableUltraBeast && hasLabel(labels, ULTRA_BEAST_LABELS)) {
            sound = INCLUDED_SOUND;
            message = "§b◆ Ultra-Chimère sauvage apparu : §f" + frenchName + " §b◆";
        } else if (enableParadox && hasLabel(labels, PARADOX_LABELS)) {
            sound = PARADOX_SOUND;
            message = "§5⚔ Pokémon Paradoxe sauvage apparu : §f" + frenchName + " §5⚔";
        }

        if (sound != null && message != null) {
            activeLoopSound = sound;
            loopActive = true;
            loopTick = 0;

            final SoundEvent finalSound = sound;
            final String finalMessage = message;
            if (!muted) {
                client.execute(() -> {
                    client.player.sendMessage(Text.literal(finalMessage), false);
                    client.player.playSound(finalSound, 1.0f, 1.0f);
                });
            }
        }
    }

    private boolean isSpecialPokemon(Pokemon pokemon) {
        Set<String> labels = pokemon.getSpecies().getLabels();
        String name = pokemon.getSpecies().getName().toLowerCase();
        String fr = FrenchNames.get(name);
        return pokemon.getShiny() ||
               hasLabel(labels, LEGENDARY_LABELS) ||
               hasLabel(labels, MYTHIC_LABELS) ||
               hasLabel(labels, ULTRA_BEAST_LABELS) ||
               hasLabel(labels, PARADOX_LABELS) ||
               trackedPokemons.contains(name) ||
               (fr != null && trackedPokemons.contains(fr.toLowerCase()));
    }

    private boolean hasLabel(Set<String> labels, Set<String> targets) {
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
