package net.tropimon.tropitracker;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Détecte la capture confirmée d'un Pokémon du tableau de chasse en croisant deux signaux
 * de messages de chat (l'équipe étant toujours pleine, chaque capture envoie au PC) :
 *   1. Le message "équipe pleine -> [nom] envoyé au PC" (indique QUEL pokémon a été attrapé)
 *   2. Le message de confirmation "Chasse complétée" / gains d'argent (confirme que c'était une chasse)
 *
 * Les deux signaux peuvent arriver dans n'importe quel ordre avec un léger délai,
 * donc chacun est gardé "en attente" jusqu'à 15 secondes pour être recoupé avec l'autre.
 * Une fois les deux confirmés, le Pokémon est retiré du tracking automatique
 * (TropiTrackerClient.removeBoardTarget), sans toucher au tracking manuel via /track.
 */
public class CatchDetector {

    private static final long PENDING_EXPIRY_MS = 15_000L;
    private static final long MATCH_COOLDOWN_MS = 3_000L;

    // Capture détectée (via message PC) mais pas encore de message de confirmation de chasse
    private static String pendingCatchSpecies = null;
    private static long pendingCatchTime = 0L;

    // Message de confirmation de chasse vu mais pas encore de capture détectée
    private static long pendingCompletionTime = -1L;

    private static long lastMatchTime = 0L;

    // Calé sur : "Votre équipe est pleine ! Abo a été ajouté à votre PC."
    private static final Pattern PC_FR_REGEX = Pattern.compile(
        "Votre équipe est pleine ! (.+) a été ajouté(?:e)? à votre PC\\."
    );

    // Calé sur : "Chasse complétée (+100₽ et +50₽ pour ta ville) (+70 points d'événement)"
    // Les montants varient selon la difficulté (100/50, 200/100, 300/150...), mais "Chasse complétée" reste fixe
    private static final Pattern COMPLETION_REGEX = Pattern.compile(
        "Chasse complétée"
    );

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            try {
                if (overlay) return;
                if (TropiTrackerClient.getBoardTrackedCount() == 0) return;

                String text = message.getString();

                Matcher pcMatch = PC_FR_REGEX.matcher(text);
                if (pcMatch.find()) {
                    String pokemonName = pcMatch.group(1).trim();
                    String speciesName = findSpeciesNameByLocalizedName(pokemonName);
                    if (speciesName != null) {
                        onCatchDetected(speciesName);
                    } else {
                        TropiTrackerClient.LOGGER.warn("[Catch] Pokémon '{}' détecté envoyé au PC, mais espèce non résolue.", pokemonName);
                    }
                    return;
                }

                if (COMPLETION_REGEX.matcher(text).find()) {
                    onCompletionMessage();
                }
            } catch (Exception e) {
                // Ne JAMAIS laisser une exception remonter dans la pile réseau :
                // ça provoquerait une déconnexion du serveur ("Internal Exception")
                // Le logger imprime deja la stack trace complete
                TropiTrackerClient.LOGGER.warn("[Catch] Erreur interceptée", e);
            }
        });

        TropiTrackerClient.LOGGER.info("CatchDetector enregistré (basé uniquement sur les messages de chat)");
    }

    /**
     * Appelé quand on détecte qu'un Pokémon a été envoyé au PC (donc capturé).
     * Si un message de confirmation de chasse est déjà en attente, on valide immédiatement.
     * Sinon, on garde la capture en attente du message de confirmation.
     */
    private static void onCatchDetected(String speciesName) {
        if (!TropiTrackerClient.isBoardTracked(speciesName)) return;

        long now = System.currentTimeMillis();

        if (pendingCompletionTime != -1L && now - pendingCompletionTime < PENDING_EXPIRY_MS) {
            pendingCompletionTime = -1L;
            confirmMatch(speciesName);
            return;
        }

        pendingCatchSpecies = speciesName;
        pendingCatchTime = now;
        TropiTrackerClient.LOGGER.info("[Catch] Capture en attente : {} (attente du message de chasse complétée)", speciesName);
    }

    /**
     * Appelé quand on reçoit un message de confirmation de chasse complétée.
     * Si une capture (message PC) est déjà en attente, on valide immédiatement.
     * Sinon (cas le plus courant si une seule cible reste trackée), on valide directement celle-ci.
     */
    private static void onCompletionMessage() {
        long now = System.currentTimeMillis();

        if (now - lastMatchTime < MATCH_COOLDOWN_MS) return;

        if (pendingCatchSpecies != null && now - pendingCatchTime < PENDING_EXPIRY_MS) {
            String species = pendingCatchSpecies;
            pendingCatchSpecies = null;
            confirmMatch(species);
            return;
        }

        // Fallback : s'il ne reste qu'une seule cible trackée depuis le tableau, c'est forcément elle
        String unique = TropiTrackerClient.getBoardTrackedSpeciesIfUnique();
        if (unique != null) {
            TropiTrackerClient.removeBoardTarget(unique);
            lastMatchTime = now;
            return;
        }

        pendingCompletionTime = now;
        TropiTrackerClient.LOGGER.info("[Catch] Chasse complétée vue, en attente du message PC pour identifier le pokémon");
    }

    /** Les deux signaux sont confirmés : on retire effectivement la cible du tracking. */
    private static void confirmMatch(String speciesName) {
        long now = System.currentTimeMillis();
        if (now - lastMatchTime < MATCH_COOLDOWN_MS) return;
        if (!TropiTrackerClient.isBoardTracked(speciesName)) return;

        TropiTrackerClient.removeBoardTarget(speciesName);
        lastMatchTime = now;
    }

    /** Résout un nom localisé (ex: "Crabicoque") vers le nom d'espèce anglais en minuscule (ex: "dwebble"). */
    private static String findSpeciesNameByLocalizedName(String localizedName) {
        return FrenchNames.getEnglishName(localizedName);
    }
}
