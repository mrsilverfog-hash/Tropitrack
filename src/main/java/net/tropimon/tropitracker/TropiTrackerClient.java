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

    public static SoundEvent SHINY_SOUND;
    public static SoundEvent INCLUDED_SOUND;

    private static final Set<String> trackedPokemons = new HashSet<>();
    // UUID des entités déjà notifiées pour éviter les doublons
    private static final Set<java.util.UUID> seenEntities = new HashSet<>();
    // Entités en attente de vérification (délai de 2 secondes)
    private static final java.util.Map<java.util.UUID, PokemonEntity> pendingEntities = new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID, Integer> pendingTicks = new java.util.HashMap<>();
    private static final int CHECK_DELAY = 40; // 2 secondes

    private static final int LOOP_INTERVAL = 60;
    private static int loopTick = 0;
    private static SoundEvent activeLoopSound = null;
    private static boolean loopActive = false;

    @Override
    public void onInitializeClient() {
        SHINY_SOUND     = SoundEvent.of(Identifier.of("tropitracker", "shiny_spawn"));
        INCLUDED_SOUND  = SoundEvent.of(Identifier.of("tropitracker", "included_spawn"));

        muteKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "TropiTracker Mute",
            InputUtil.Type.KEYSYM,
            186, // ù sur clavier AZERTY
            "TropiTracker"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (muteKey.wasPressed()) {
                muted = !muted;
                if (client.player != null) {
                    client.player.sendMessage(
                        Text.literal(muted ? "§cTropiTracker : Son coupé 🔇" : "§aTropiTracker : Son activé 🔊"),
                        true
                    );
                }
            }

            // Vérifier les entités en attente
            if (!pendingEntities.isEmpty() && client.world != null) {
                java.util.List<java.util.UUID> toRemove = new java.util.ArrayList<>();
                for (java.util.Map.Entry<java.util.UUID, PokemonEntity> entry : pendingEntities.entrySet()) {
                    int ticks = pendingTicks.getOrDefault(entry.getKey(), 0) + 1;
                    pendingTicks.put(entry.getKey(), ticks);
                    if (ticks >= CHECK_DELAY) {
                        toRemove.add(entry.getKey());
                        PokemonEntity pe = entry.getValue();
                        handleSpawn(pe);
                    }
                }
                for (java.util.UUID uuid : toRemove) {
                    pendingEntities.remove(uuid);
                    pendingTicks.remove(uuid);
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
            if (seenEntities.contains(entityUUID)) return;
            seenEntities.add(entityUUID);
            // Ajouter à la file d'attente pour vérification différée
            pendingEntities.put(entityUUID, pokemonEntity);
        });

        ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (!(entity instanceof PokemonEntity)) return;
            seenEntities.remove(entity.getUuid());
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) return;

            // Vérifier si un Pokémon suivi ou un Shiny sauvage est encore présent autour de nous
            boolean stillPresent = false;
            for (Entity e : client.world.getEntities()) {
                if (e == entity || !(e instanceof PokemonEntity pe)) continue;
                
                // SÉCURITÉ : On ignore les Pokémon qui ont un maître, un dresseur d'origine ou en combat
                if (pe.getPokemon().getOwnerUUID() != null || 
                    pe.getPokemon().getOriginalTrainer() != null || 
                    pe.getBattleId() != null) continue;

                String name = pe.getPokemon().getSpecies().getName().toLowerCase();
                String fr = FrenchNames.get(name);
                
                if (trackedPokemons.contains(name) || 
                    (fr != null && trackedPokemons.contains(fr.toLowerCase())) ||
                    pe.getPokemon().getShiny()) {
                    stillPresent = true;
                    break;
                }
            }
            if (!notStillPresent(stillPresent)) {
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

    private static boolean notStillPresent(boolean stillPresent) {
        return stillPresent;
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

    private void handleSpawn(PokemonEntity pe) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        Pokemon pokemon = pe.getPokemon();
        
        // SÉCURITÉ MAXIMUM : 
        // - pokemon.getOwnerUUID() != null -> C'est ton Pokémon
        // - pokemon.getOriginalTrainer() != null -> Déjà capturé par un joueur (donc pas sauvage)
        // - pe.getBattleId() != null -> Déjà en combat
        if (pokemon.getOwnerUUID() != null || pokemon.getOriginalTrainer() != null || pe.getBattleId() != null) return;

        String speciesName = pokemon.getSpecies().getName().toLowerCase();
        String frenchName = FrenchNames.get(speciesName);
        if (frenchName == null) frenchName = pokemon.getSpecies().getName();

        SoundEvent sound = null;
        String message = null;

        String frLower = frenchName.toLowerCase();
        if (!trackedPokemons.isEmpty() &&
            (trackedPokemons.contains(frLower) || trackedPokemons.contains(speciesName))) {
            sound = INCLUDED_SOUND;
            message = "§e🎯 Pokémon recherché apparu : §f" + frenchName + (pokemon.getShiny() ? " §6✨ SHINY ✨" : "");
        } else if (pokemon.getShiny()) {
            // Déclenchement uniquement si le Shiny est 100% sauvage et libre
            sound = SHINY_SOUND;
            message = "§6✨ Pokémon Shiny SAUVAGE apparu : §e" + frenchName + " §6✨";
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

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
