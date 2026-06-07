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
    // UUID des entités déjà notifiées pour éviter les doublons
    private static final Set<java.util.UUID> seenEntities = new HashSet<>();

    private static final int LOOP_INTERVAL = 60;
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
            Pokemon poke = pokemonEntity.getPokemon();
            // Ignorer les Pokémon qui appartiennent à quelqu'un (joueur ou NPC)
            if (poke.getOwnerUUID() != null) return;
            // Ignorer les Pokémon en combat contre un dresseur NPC
            if (pokemonEntity.getBattleId() != null) return;
            // Ignorer si déjà notifié (rechargement de chunk)
            java.util.UUID entityUUID = pokemonEntity.getUuid();
            if (seenEntities.contains(entityUUID)) return;
            seenEntities.add(entityUUID);
            handleSpawn(poke);
        });

        ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (!(entity instanceof PokemonEntity)) return;
            seenEntities.remove(entity.getUuid());
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) return;

            // Vérifier si un Pokémon spécial est encore présent
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
            // Arrêter le son en boucle
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
