package net.tropimon.tropitracker;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.fabricmc.api.ClientModInitializer;
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

    // Structure pour mettre les Pokémon en attente de vérification
    private static class PendingEntity {
        int entityId;
        int ticksLeft;

        PendingEntity(int entityId, int ticksLeft) {
            this.entityId = entityId;
            this.ticksLeft = ticksLeft;
        }
    }

    private static final Set<Integer> processedEntities = new HashSet<>();
    private static final ArrayList<PendingEntity> pendingEntities = new ArrayList<>();
    
    private static int joinCooldownTicks = 100; // 5 secondes de sécurité à la connexion
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

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            // Si on change de monde ou qu'on se connecte
            if (client.world != lastWorld) {
                lastWorld = client.world;
                joinCooldownTicks = 100;
                processedEntities.clear();
                pendingEntities.clear();
            }

            // Gestion de la touche pour couper le son
            while (muteKey.wasPressed()) {
                muted = !muted;
                if (muted) {
                    client.player.sendMessage(Text.literal("§c[TropiTracker] Alertes coupées."), false);
                } else {
                    client.player.sendMessage(Text.literal("§a[TropiTracker] Alertes activées."), false);
                }
            }

            // Sécurité de connexion : on enregistre les Pokémon déjà là sans faire de bruit
            if (joinCooldownTicks > 0) {
                joinCooldownTicks--;
                for (Entity entity : client.world.getEntities()) {
                    if (entity instanceof PokemonEntity) {
                        processedEntities.add(entity.getId());
                    }
                }
                return;
            }

            // Scanner la zone pour trouver les nouveaux Pokémon
            for (Entity entity : client.world.getEntities()) {
                if (entity instanceof PokemonEntity) {
                    int id = entity.getId();
                    if (!processedEntities.contains(id) && !containsPending(id)) {
                        pendingEntities.add(new PendingEntity(id, 10)); // Attente de 10 ticks (0,5 seconde)
                    }
                }
            }

            // Vérification des Pokémon après le petit délai
            for (int i = pendingEntities.size() - 1; i >= 0; i--) {
                PendingEntity pending = pendingEntities.get(i);
                pending.ticksLeft--;

                if (pending.ticksLeft <= 0) {
                    pendingEntities.remove(i);
                    processedEntities.add(pending.entityId); // Marqué comme traité

                    Entity entity = client.world.getEntityById(pending.entityId);
                    if (entity instanceof PokemonEntity pe) {
                        Pokemon poke = pe.getPokemon();
                        if (poke != null) {
                            // Vérification finale : sauvage et sans propriétaire
                            if (poke.isWild() && poke.getOwnerUUID() == null) {
                                checkAndPlayAlert(client, poke);
                            }
                        }
                    }
                }
            }

            // Nettoyage de la mémoire si le Pokémon s'en va ou est rangé dans sa Pokéball
            processedEntities.removeIf(id -> client.world.getEntityById(id) == null);
        });
    }

    private static boolean containsPending(int id) {
        for (PendingEntity p : pendingEntities) {
            if (p.entityId == id) return true;
        }
        return false;
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

        if (sound != null && message != null && !muted) {
            SoundEvent finalSound = sound;
            String finalMessage = message;
            client.execute(() -> {
                if (client.player != null) {
                    client.player.sendMessage(Text.literal(finalMessage), false);
                    client.player.playSound(finalSound, 1.0f, 1.0f);
                }
            });
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
