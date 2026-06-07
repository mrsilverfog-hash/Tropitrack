package net.tropimon.tropitracker;

import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.Map;
import java.util.Set;

public class TropiTrackerClient implements ClientModInitializer {

    // Touche mute
    private static KeyBinding muteKey;
    private static boolean muted = false;

    // Sons
    public static SoundEvent LEGENDARY_SOUND;
    public static SoundEvent SHINY_SOUND;
    public static SoundEvent PARADOX_SOUND;
    public static SoundEvent INCLUDED_SOUND;

    // Catégories activées
    public static boolean enableLegendary = true;
    public static boolean enableMythic = true;
    public static boolean enableUltraBeast = true;
    public static boolean enableParadox = true;
    public static boolean enableShiny = true;

    // Labels légendaires (depuis Cobblemon)
    private static final Set<String> LEGENDARY_LABELS = Set.of("legendary");
    private static final Set<String> MYTHIC_LABELS = Set.of("mythical");
    private static final Set<String> ULTRA_BEAST_LABELS = Set.of("ultra_beast");
    private static final Set<String> PARADOX_LABELS = Set.of("paradox");

    @Override
    public void onInitializeClient() {
        // Enregistrer les sons
        LEGENDARY_SOUND = SoundEvent.of(Identifier.of("tropitracker", "legendary_spawn"));
        SHINY_SOUND     = SoundEvent.of(Identifier.of("tropitracker", "shiny_spawn"));
        PARADOX_SOUND   = SoundEvent.of(Identifier.of("tropitracker", "paradox_spawn"));
        INCLUDED_SOUND  = SoundEvent.of(Identifier.of("tropitracker", "included_spawn"));

        // Touche mute : M
        muteKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "TropiTracker Mute",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
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
        });

        // Écouter les spawns Cobblemon
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(event -> {
            Pokemon pokemon = event.getEntity().getPokemon();
            handleSpawn(pokemon);
            return kotlin.Unit.INSTANCE;
        });
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

        // Shiny en priorité
        if (isShiny && enableShiny) {
            sound = SHINY_SOUND;
            message = "§6✨ Pokémon Shiny apparu : §e" + frenchName + " §6✨";
        }
        // Légendaire
        else if (enableLegendary && hasLabel(labels, LEGENDARY_LABELS)) {
            sound = LEGENDARY_SOUND;
            message = "§c⚡ Légendaire apparu : §f" + frenchName + " §c⚡";
        }
        // Mystique
        else if (enableMythic && hasLabel(labels, MYTHIC_LABELS)) {
            sound = LEGENDARY_SOUND;
            message = "§d✦ Mystique apparu : §f" + frenchName + " §d✦";
        }
        // Ultra-Beast
        else if (enableUltraBeast && hasLabel(labels, ULTRA_BEAST_LABELS)) {
            sound = INCLUDED_SOUND;
            message = "§b◆ Ultra-Chimère apparu : §f" + frenchName + " §b◆";
        }
        // Paradoxe
        else if (enableParadox && hasLabel(labels, PARADOX_LABELS)) {
            sound = PARADOX_SOUND;
            message = "§5⚔ Pokémon Paradoxe apparu : §f" + frenchName + " §5⚔";
        }

        if (sound != null && message != null && !muted) {
            final SoundEvent finalSound = sound;
            final String finalMessage = message;
            client.execute(() -> {
                client.player.sendMessage(Text.literal(finalMessage), false);
                client.player.playSound(finalSound, 1.0f, 1.0f);
            });
        }
    }

    private boolean hasLabel(Set<String> labels, Set<String> targets) {
        for (String label : labels) {
            if (targets.contains(label.toLowerCase())) return true;
        }
        return false;
    }
}
