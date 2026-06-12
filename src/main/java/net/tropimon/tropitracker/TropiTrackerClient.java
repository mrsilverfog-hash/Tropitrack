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

    private static final Set<String> LEGENDARY_LABELS = Set.of("legendary");
    private static final Set<String> MYTHIC_LABELS = Set.of("mythic");
    private static final Set<String> ULTRA_BEAST_LABELS = Set.of("ultra_beast");
    private static final Set<String> PARADOX_LABELS = Set.of("paradox");
    private static final Set<String> trackedPokemons = new HashSet<>();

    private static SoundEvent activeLoopSound = null;
    private static boolean loopActive = false;
    private static int loopTick = 0;

    // Structure simple pour mettre en attente les vérifications
    private static class PendingEntity {
        int entityId;
        int ticksLeft;

        PendingEntity(int entityId, int ticksLeft) {
            this.entityId = entityId;
            this.ticksLeft = ticksLeft;
        }
    }

    private static final ArrayList<PendingEntity> pendingEntities = new ArrayList<>();
    private static int joinCooldownTicks = 100; // Désactive les sons pendant 5 secondes au début
    private static net.minecraft.client.world.ClientWorld lastWorld = null;

    @Override
    public void onInitializeClient() {
        LEGENDARY_SOUND = SoundEvent.of(Identifier.of("tropitracker", "legendary_spawn"));
        SHINY_SOUND = SoundEvent.of(Identifier.of("tropitracker", "shiny_spawn"));
        PARADOX_SOUND = SoundEvent.of(Identifier.of("tropitracker", "paradox_spawn"));
        INCLUDED_SOUND = SoundEvent.of(Identifier.of("tropitracker", "included_spawn"));

        muteKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.tropitracker.mute",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "category.tropitracker"
        ));

        // Détection de l'apparition d'une entité
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof PokemonEntity) {
                // Si la sécurité de connexion est active, on ne fait rien
                if (joinCooldownTicks > 0) {
                    return;
                }
                // On met l'entité en attente pendant 30 ticks (1,5 seconde)
                pendingEntities.add(new PendingEntity(entity.getId(), 30));
            }
        });

        // Gestion à chaque tick du jeu
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // Réinitialise la sécurité si on change de monde ou si on se connecte
            if (client.world != lastWorld) {
                lastWorld = client.world;
                joinCooldownTicks = 100;
                pendingEntities.clear();
            }

            if (joinCooldownTicks > 0) {
                joinCooldownTicks--;
            }

            while (muteKey.wasPressed()) {
                muted = !muted;
                if (muted) {
                    client.player.sendMessage(Text.literal("§c[TropiTracker] Alertes coupées."), false);
                } else {
                    client.player.sendMessage(Text.literal("§a[TropiTracker] Alertes activées."), false);
                }
            }

            // Vérification des Pokémon mis en attente
            for (int i = pendingEntities.size() - 1; i >= 0; i--) {
                PendingEntity pending = pendingEntities.get(i);
                pending.ticksLeft--;

                if (pending.ticksLeft <= 0) {
                    pendingEntities.remove(i);

                    if (client.world != null) {
                        Entity entity = client.world.getEntityById(pending.entityId);
                        if (entity instanceof PokemonEntity) {
                            PokemonEntity pe = (PokemonEntity) entity;
                            Pokemon poke = pe.getPokemon();

                            if (poke != null) {
                                // VÉRIFICATION STRICTE après délai : pas de propriétaire et doit être sauvage
                                if (poke.getOwnerUUID() == null && poke.isWild()) {
                                    checkAndPlayAlert(client, poke);
                                }
                            }
                        }
                    }
                }
            }
        });
    }

    private void checkAndPlayAlert(MinecraftClient client, Pokemon poke) {
        if (!isSpecialPokemon(poke)) return;

        SoundEvent sound = null;
        String message = null;

        if (poke.getShiny() && enableShiny) {
            sound = SHINY_SOUND;
            message = "§e[TropiTracker] Un Pokémon Shiny sauvage est proche : " + capitalize(poke.getSpecies().getName());
        } else if (hasLabel(poke.getSpecies().getLabels(), LEGENDARY_LABELS) && enableLegendary) {
            sound = LEGENDARY_SOUND;
            message = "§6[TropiTracker] Un Pokémon Légendaire sauvage est proche : " + capitalize(poke.getSpecies().getName());
        } else if (hasLabel(poke.getSpecies().getLabels(), PARADOX_LABELS) && enableParadox) {
            sound = PARADOX_SOUND;
            message = "§d[TropiTracker] Un Pokémon Paradoxe sauvage est proche : " + capitalize(poke.getSpecies().getName());
        }

        if (sound != null && message != null) {
            activeLoopSound = sound;
            loopActive = true;
            loopTick = 0;

            final SoundEvent finalSound = sound;
            final String finalMessage = message;
            if (!muted) {
                client.execute(() -> {
                    if (client.player != null) {
                        client.player.sendMessage(Text.literal(finalMessage), false);
                        client.player.playSound(finalSound, 1.0f, 1.0f);
                    }
                });
            }
        }
    }

    private boolean isSpecialPokemon(Pokemon pokemon) {
        Set<String> labels = pokemon.getSpecies().getLabels();
        String name = pokemon.getSpecies().getName().toLowerCase();
        return pokemon.getShiny() ||
               hasLabel(labels, LEGENDARY_LABELS) ||
               hasLabel(labels, MYTHIC_LABELS) ||
               hasLabel(labels, ULTRA_BEAST_LABELS) ||
               hasLabel(labels, PARADOX_LABELS) ||
               trackedPokemons.contains(name);
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
