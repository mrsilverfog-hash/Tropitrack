package net.tropimon.tropitracker;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Détecte l'ouverture du tableau de chasse de TropimodClient (HunterBoardScreen)
 * et extrait par réflexion les Pokémon recherchés non encore capturés,
 * pour les ajouter automatiquement au tracking de TropiTracker.
 */
public class BoardDetector {

    private static final String HUNTER_BOARD_CLASS = "fr.erusel.tropimodclient.client.gui.hunt.HunterBoardScreen";
    private static final String POKEMON_WIDGET_CLASS = "fr.erusel.tropimodclient.client.gui.hunt.widgets.HunterBoardPokemonWidget";

    private static int tickCounter = 0;
    private static boolean alreadyParsed = false;

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            String className = screen.getClass().getName();

            if (className.equals(HUNTER_BOARD_CLASS) || className.contains("HunterBoard")) {
                tickCounter = 0;
                alreadyParsed = false;

                ScreenEvents.afterTick(screen).register(scr -> {
                    tickCounter++;
                    // On attend 10 ticks pour laisser le temps aux données du tableau de charger
                    if (tickCounter == 10 && !alreadyParsed) {
                        alreadyParsed = true;
                        parseHuntData(scr);
                    }
                });
            }
        });
    }

    private static void parseHuntData(Screen screen) {
        try {
            List<?> widgets = screen.children();
            if (widgets == null) {
                System.out.println("[TropiTracker][Board] Impossible de lire les widgets du tableau.");
                return;
            }

            Set<String> speciesNames = new HashSet<>();

            for (Object widget : widgets) {
                if (widget == null) continue;
                if (!widget.getClass().getName().equals(POKEMON_WIDGET_CLASS)) continue;

                Field huntPokemonField = widget.getClass().getDeclaredField("pokemon");
                huntPokemonField.setAccessible(true);
                Object huntPokemon = huntPokemonField.get(widget);
                if (huntPokemon == null) continue;

                String speciesName = extractSpeciesName(huntPokemon);
                if (speciesName != null) speciesNames.add(speciesName);
            }

            TropiTrackerClient.setBoardTargets(speciesNames);
            TropiTrackerClient.recheckAfterBoardUpdate();

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && !speciesNames.isEmpty()) {
                client.player.sendMessage(
                    Text.literal("§aTropiTracker : §f" + speciesNames.size() + " pokémon du tableau de chasse trackés automatiquement."),
                    false
                );
            }

        } catch (Exception e) {
            System.out.println("[TropiTracker][Board] Erreur lecture tableau : " + e.getMessage());
        }
    }

    /**
     * Extrait le nom d'espèce (anglais, en minuscule) d'un HunterBoardPokemon,
     * uniquement si le Pokémon n'est pas déjà capturé.
     */
    private static String extractSpeciesName(Object huntPokemon) {
        try {
            Class<?> clazz = huntPokemon.getClass();
            com.cobblemon.mod.common.pokemon.Pokemon pokemon = null;
            boolean captured = false;

            for (Field field : clazz.getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(huntPokemon);
                if (value == null) continue;

                switch (field.getName()) {
                    case "pokemon":
                        if (value instanceof com.cobblemon.mod.common.pokemon.Pokemon p) {
                            pokemon = p;
                        }
                        break;
                    case "captured":
                        if (value instanceof Boolean b) captured = b;
                        break;
                }
            }

            if (pokemon == null || captured) return null;
            return pokemon.getSpecies().getName().toLowerCase();

        } catch (Exception e) {
            return null;
        }
    }
}
