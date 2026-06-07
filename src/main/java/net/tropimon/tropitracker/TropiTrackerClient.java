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
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

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

    // Son en boucle : on rejoue toutes les X ticks si un Pokémon suivi est visible
    private static final int LOOP_INTERVAL = 60; // 3 secondes
    private static int loopTick = 0;
    private static SoundEvent activeLoopSound = null;
    private static boolean loopActive = false;

    private static final Set<String> LEGENDARY_LABELS   = Set.of("legendary");
    private static final Set<String> MYTHIC_LABELS      = Set.of("mythical");
    private static final Set<String> ULTRA_BEAST_LABELS = Set.of("ultra_beast");
    private static final Set<String> PARADOX_LABELS     = Set.of("paradox");

    @Override
    public void onInitializeClient() {
        LEGENDARY_SOUND = SoundEvent.of(Identifier.of("tropitracker", "legendary_spawn"));
        SHINY_SOUND     = SoundEvent.of(Identifier.of("tropitracker", "shiny_spawn"));
        PARADOX_SOUND   = SoundEvent.of(Identifier.of("tropitracker", "paradox_spawn"));
        INCLUDED_SOUND  = SoundEvent.of(Identifier.of("tropitracker", "included_spawn"));

        muteKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "TropiTracker Mute",
            InputUtil.Type.KEYSYM,
            96,
            "TropiTracker"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Touche mute
            while (muteKey.wasPressed()) {
                muted = !muted;
                if (client.player != null) {
                    client.player.sendMessage(
                        Text.literal(muted ? "§cTropiTracker : Son coupé 🔇" : "§aTropiTracker : Son activé 🔊"),
                        true
                    );
                }
            }

            // Boucle sonore
            if (loopActive && !muted && activeLoopSound != null && client.player != null) {
                loopTick++;
                if (loopTick >= LOOP_INTERVAL) {
                    loopTick = 0;
                    client.player.playSound(activeLoopSound, 1.0f, 1.0f);
                }
            }
        });

        // Détecter les spawns
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof PokemonEntity pokemonEntity) {
                handleSpawn(pokemonEntity.getPokemon());
            }
        });

        // Détecter quand un Pokémon disparaît
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof PokemonEntity) {
                // Vérifier si des Pokémon suivis sont encore présents
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.world == null) return;
                boolean stillPresent = client.world.getEntities()
                    .stream()
                    .anyMatch(e -> {
                        if (e == entity || !(e instanceof PokemonEntity pe)) return false;
                        String name = pe.getPokemon().getSpecies().getName().toLowerCase();
                        String fr = FrenchNames.get(name);
                        return trackedPokemons.contains(name) || 
                               (fr != null && trackedPokemons.contains(fr.toLowerCase())) ||
                               isSpecialPokemon(pe.getPokemon());
                    });
                if (!stillPresent) {
                    loopActive = false;
                    activeLoopSound = null;
                }
            }
        });

        // Commande track dans le chat
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

        // Pokémon suivi personnellement
        String frLower = frenchName.toLowerCase();
        if (!trackedPokemons.isEmpty() &&
            (trackedPokemons.contains(frLower) || trackedPokemons.contains(speciesName))) {
            sound = INCLUDED_SOUND;
            message = "§e🎯 Pokémon recherché apparu : §f" + frenchName + (isShiny ? " §6✨ SHINY ✨" : "");
        }
        // Catégories spéciales
        else if (isShiny && enableShiny) {
            sound = SHINY_SOUND;
            message = "§6✨ Pokémon Shiny apparu : §e" + frenchName + " §6✨";
        } else if (enableLegendary && hasLabel(labels, LEGENDARY_LABELS)) {
            sound = LEGENDARY_SOUND;
            message = "§c⚡ Légendaire apparu : §f" + frenchName + " §c⚡";
        } else if (enableMythic && hasLabel(labels, MYTHIC_LABELS)) {
            sound = LEGENDARY_SOUND;
            message = "§d✦ Mystique apparu : §f" + frenchName + " §d✦";
        } else if (enableUltraBeast && hasLabel(labels, ULTRA_BEAST_LABELS)) {
            sound = INCLUDED_SOUND;
            message = "§b◆ Ultra-Chimère apparu : §f" + frenchName + " §b◆";
        } else if (enableParadox && hasLabel(labels, PARADOX_LABELS)) {
            sound = PARADOX_SOUND;
            message = "§5⚔ Pokémon Paradoxe apparu : §f" + frenchName + " §5⚔";
        }

        if (sound != null && message != null) {
            final SoundEvent finalSound = sound;
            final String finalMessage = message;

            // Activer la boucle sonore
            activeLoopSound = finalSound;
            loopActive = true;
            loopTick = 0;

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
        return pokemon.getShiny() ||
               hasLabel(labels, LEGENDARY_LABELS) ||
               hasLabel(labels, MYTHIC_LABELS) ||
               hasLabel(labels, ULTRA_BEAST_LABELS) ||
               hasLabel(labels, PARADOX_LABELS);
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
