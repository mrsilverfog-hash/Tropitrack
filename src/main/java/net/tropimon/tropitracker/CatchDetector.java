package net.tropimon.tropitracker;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Détecte la capture d'un Pokémon du tableau de chasse et le retire du tracking automatique.
 *
 * Depuis que le serveur n'écrit plus « Chasse complétée » dans le tchat, le texte est
 * écouté sur TOUS les canaux d'affichage, pas seulement le tchat :
 *   - tchat (messages système)
 *   - barre d'action (au-dessus de la hotbar)
 *   - titre et sous-titre (gros texte au centre de l'écran)
 *   - notifications « toast » (en haut à droite)
 * Les trois derniers arrivent via InGameHudMixin et ToastManagerMixin.
 *
 * Trois sources permettent de savoir QUEL Pokémon a été capturé, par ordre de priorité :
 *   1. le message « équipe pleine -> [nom] ajouté à votre PC » (n'importe quel canal)
 *   2. un nom de Pokémon du tableau présent dans un titre / sous-titre / toast / barre d'action
 *   3. un Pokémon sauvage du tableau qui disparaît à moins de 16 blocs du joueur
 *      (une capture fait disparaître l'entité)
 * S'il ne reste qu'une seule cible au tableau, la confirmation de chasse suffit.
 *
 * Commandes tapées dans le tchat (jamais envoyées au serveur) :
 *   untrack <nom>  -> retire à la main un Pokémon du tableau (nom français ou anglais)
 *   huntdebug      -> affiche dans le tchat tout ce qui passe en titre / barre d'action / toast
 */
public class CatchDetector {

    private static final long PENDING_EXPIRY_MS = 15_000L;
    private static final long MATCH_COOLDOWN_MS = 3_000L;
    private static final long DEDUPE_MS = 1_000L;
    private static final double DISAPPEAR_RADIUS_SQ = 16.0 * 16.0;

    // Espèce identifiée (PC, texte HUD ou disparition) en attente de la confirmation de chasse
    private static String pendingCatchSpecies = null;
    private static long pendingCatchTime = 0L;

    // Confirmation de chasse vue, en attente de savoir quel Pokémon c'était
    private static long pendingCompletionTime = -1L;

    private static long lastMatchTime = 0L;

    // Anti-doublon : un même texte peut arriver par deux chemins (événement tchat + HUD)
    private static String lastTextFlat = null;
    private static long lastTextTime = 0L;

    // Pokémon du tableau disparus récemment près du joueur (plus récent en tête)
    private static final Deque<long[]> goneTimes = new ArrayDeque<>();
    private static final Deque<String> goneSpecies = new ArrayDeque<>();

    private static boolean debugHud = false;

    // Testés sur le texte mis à plat (minuscules, sans accents ni codes couleur)
    // Calé sur : "Votre équipe est pleine ! Abo a été ajouté à votre PC."
    private static final Pattern PC_REGEX = Pattern.compile(
        "(?:equipe est pleine\\s*!?\\s*)?([\\p{L}0-9 .'\\-:♀♂]+?) a ete ajoute(?:e)? (?:a|dans) (?:votre|ton) pc"
    );

    // Calé sur : "Chasse complétée (+100₽ et +50₽ pour ta ville)" — tolérant aux variantes
    private static final Pattern COMPLETION_REGEX = Pattern.compile(
        "chasse\\s+(?:complete|terminee|reussie|validee)"
    );

    public static void register() {
        // 1. Tchat classique + barre d'action quand elle passe par l'événement Fabric
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            try {
                if (message == null) return;
                onText(message.getString(), overlay ? "actionbar" : "chat");
            } catch (Exception e) {
                // Ne JAMAIS laisser une exception remonter dans la pile réseau :
                // ça provoquerait une déconnexion du serveur ("Internal Exception")
                TropiTrackerClient.LOGGER.warn("[Catch] Erreur interceptée", e);
            }
        });

        // 2. Disparition d'un Pokémon sauvage du tableau près du joueur
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            try {
                if (!(entity instanceof PokemonEntity pe)) return;
                onPokemonGone(pe);
            } catch (Exception e) {
                TropiTrackerClient.LOGGER.warn("[Catch] Erreur disparition", e);
            }
        });

        // 3. Commandes locales (enregistré avant celui de TropiTrackerClient, donc prioritaire)
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String trimmed = message.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.equals("huntdebug")) {
                debugHud = !debugHud;
                say(debugHud
                    ? "§eTropiTracker : debug chasse §aactivé§e — titres, barre d'action et toasts copiés dans le tchat."
                    : "§eTropiTracker : debug chasse §cdésactivé§e.");
                return false;
            }
            if (lower.startsWith("untrack ")) {
                handleUntrack(trimmed.substring(8).trim());
                return false;
            }
            return true;
        });

        TropiTrackerClient.LOGGER.info("CatchDetector enregistré (tchat, barre d'action, titres, toasts, disparitions)");
    }

    // ------------------------------------------------------------------
    // Points d'entrée appelés par les mixins
    // ------------------------------------------------------------------

    /** Appelé par InGameHudMixin (title / subtitle / actionbar) et ToastManagerMixin (toast). */
    public static void onHudText(Text text, String source) {
        try {
            if (text == null) return;
            onHudString(text.getString(), source);
        } catch (Exception e) {
            TropiTrackerClient.LOGGER.warn("[Catch] Erreur texte HUD", e);
        }
    }

    public static void onHudString(String raw, String source) {
        try {
            if (raw == null || raw.isBlank()) return;
            if (debugHud && !raw.startsWith("TropiTracker") && !raw.contains("TropiTracker :")) {
                say("§7[" + source + "] §f" + raw.replace('\n', ' '));
            }
            onText(raw, source);
        } catch (Exception e) {
            TropiTrackerClient.LOGGER.warn("[Catch] Erreur texte HUD", e);
        }
    }

    // ------------------------------------------------------------------
    // Analyse commune de tous les textes
    // ------------------------------------------------------------------

    private static void onText(String raw, String source) {
        if (raw == null || raw.isBlank()) return;
        if (TropiTrackerClient.getBoardTrackedCount() == 0) return;

        String flat = flatten(raw);
        long now = System.currentTimeMillis();

        if (flat.equals(lastTextFlat) && now - lastTextTime < DEDUPE_MS) return;
        lastTextFlat = flat;
        lastTextTime = now;

        // a) Message « ajouté à votre PC » : identifie le Pokémon
        Matcher pc = PC_REGEX.matcher(flat);
        if (pc.find()) {
            String species = resolveSpecies(pc.group(1));
            if (species != null) {
                TropiTrackerClient.LOGGER.info("[Catch] ({}) envoyé au PC : {}", source, species);
                onSpeciesIdentified(species);
            } else {
                TropiTrackerClient.LOGGER.warn("[Catch] ({}) Pokémon '{}' envoyé au PC, espèce non résolue.", source, pc.group(1));
            }
        }

        boolean completion = COMPLETION_REGEX.matcher(flat).find();

        // b) Hors tchat (titre, toast...) : le nom du Pokémon peut figurer dans le même texte
        if (!source.equals("chat")) {
            String species = findBoardSpeciesInText(flat);
            if (species != null) {
                TropiTrackerClient.LOGGER.info("[Catch] ({}) nom du tableau repéré : {}", source, species);
                onSpeciesIdentified(species);
            }
        }

        if (completion) {
            TropiTrackerClient.LOGGER.info("[Catch] Chasse complétée vue via {}", source);
            onCompletionMessage();
        }
    }

    private static void onPokemonGone(PokemonEntity pe) {
        if (TropiTrackerClient.getBoardTrackedCount() == 0) return;
        if (pe.getOwnerUuid() != null) return;
        if (pe.getPokemon() == null || pe.getPokemon().getOwnerUUID() != null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (client.player.squaredDistanceTo(pe) > DISAPPEAR_RADIUS_SQ) return;

        String species = pe.getPokemon().getSpecies().getName().toLowerCase(Locale.ROOT);
        if (!TropiTrackerClient.isBoardTracked(species)) return;

        long now = System.currentTimeMillis();
        goneTimes.addFirst(new long[]{now});
        goneSpecies.addFirst(species);
        while (goneTimes.size() > 8) {
            goneTimes.removeLast();
            goneSpecies.removeLast();
        }
        TropiTrackerClient.LOGGER.info("[Catch] Pokémon du tableau disparu près du joueur : {}", species);

        // Confirmation déjà reçue et toujours sans identité : c'est très probablement lui
        if (pendingCompletionTime != -1L && now - pendingCompletionTime < PENDING_EXPIRY_MS) {
            pendingCompletionTime = -1L;
            confirmMatch(species);
        }
    }

    // ------------------------------------------------------------------
    // Recoupement des signaux
    // ------------------------------------------------------------------

    private static void onSpeciesIdentified(String speciesName) {
        if (!TropiTrackerClient.isBoardTracked(speciesName)) return;

        long now = System.currentTimeMillis();

        if (pendingCompletionTime != -1L && now - pendingCompletionTime < PENDING_EXPIRY_MS) {
            pendingCompletionTime = -1L;
            confirmMatch(speciesName);
            return;
        }

        pendingCatchSpecies = speciesName;
        pendingCatchTime = now;
        TropiTrackerClient.LOGGER.info("[Catch] Capture en attente : {} (attente de la confirmation de chasse)", speciesName);
    }

    private static void onCompletionMessage() {
        long now = System.currentTimeMillis();
        if (now - lastMatchTime < MATCH_COOLDOWN_MS) return;

        // 1. Espèce déjà identifiée (PC ou texte HUD)
        if (pendingCatchSpecies != null && now - pendingCatchTime < PENDING_EXPIRY_MS) {
            String species = pendingCatchSpecies;
            pendingCatchSpecies = null;
            confirmMatch(species);
            return;
        }

        // 2. Une seule cible restante au tableau : c'est forcément elle
        String unique = TropiTrackerClient.getBoardTrackedSpeciesIfUnique();
        if (unique != null) {
            confirmMatch(unique);
            return;
        }

        // 3. Un Pokémon du tableau vient de disparaître à côté du joueur
        String gone = takeRecentGone(now);
        if (gone != null) {
            confirmMatch(gone);
            return;
        }

        pendingCompletionTime = now;
        TropiTrackerClient.LOGGER.info("[Catch] Chasse complétée, Pokémon pas encore identifié (attente 15 s)");
    }

    private static String takeRecentGone(long now) {
        Iterator<long[]> it = goneTimes.iterator();
        Iterator<String> its = goneSpecies.iterator();
        while (it.hasNext()) {
            long t = it.next()[0];
            String s = its.next();
            if (now - t < PENDING_EXPIRY_MS && TropiTrackerClient.isBoardTracked(s)) {
                it.remove();
                its.remove();
                return s;
            }
        }
        return null;
    }

    private static void confirmMatch(String speciesName) {
        long now = System.currentTimeMillis();
        if (now - lastMatchTime < MATCH_COOLDOWN_MS) return;
        if (!TropiTrackerClient.isBoardTracked(speciesName)) return;

        TropiTrackerClient.removeBoardTarget(speciesName);
        lastMatchTime = now;
        pendingCatchSpecies = null;
        pendingCompletionTime = -1L;
    }

    // ------------------------------------------------------------------
    // Commande manuelle
    // ------------------------------------------------------------------

    private static void handleUntrack(String name) {
        if (name.isEmpty()) {
            say("§cTropiTracker : utilisation §funtrack <nom du Pokémon>");
            return;
        }
        String species = resolveSpecies(name);
        if (species == null) species = name.toLowerCase(Locale.ROOT);

        if (TropiTrackerClient.isBoardTracked(species)) {
            TropiTrackerClient.removeBoardTarget(species);
        } else {
            say("§cTropiTracker : §f" + name + " §cn'est pas dans le tableau de chasse tracké.");
        }
    }

    // ------------------------------------------------------------------
    // Résolution des noms (insensible aux accents et à la casse)
    // ------------------------------------------------------------------

    private static Map<String, String> flatNameToSpecies = null;

    /** Nom français ou anglais, avec ou sans accents -> nom d'espèce anglais en minuscule. */
    private static String resolveSpecies(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return null;

        String direct = FrenchNames.getEnglishName(trimmed);
        if (direct != null) return direct;

        String flat = flatten(trimmed);
        return getNameMap().get(flat);
    }

    /** Cherche dans un texte (déjà mis à plat) un Pokémon actuellement au tableau. */
    private static String findBoardSpeciesInText(String flatText) {
        String best = null;
        int bestLen = 0;
        for (Map.Entry<String, String> e : getNameMap().entrySet()) {
            String key = e.getKey();
            if (key.length() < 3 || key.length() <= bestLen) continue;
            if (!TropiTrackerClient.isBoardTracked(e.getValue())) continue;
            if (containsWord(flatText, key)) {
                best = e.getValue();
                bestLen = key.length();
            }
        }
        return best;
    }

    private static boolean containsWord(String text, String word) {
        int from = 0;
        while (true) {
            int i = text.indexOf(word, from);
            if (i < 0) return false;
            int end = i + word.length();
            boolean startOk = i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));
            boolean endOk = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (startOk && endOk) return true;
            from = i + 1;
        }
    }

    /**
     * Construit une seule fois la table « nom mis à plat -> espèce » à partir de toutes
     * les espèces Cobblemon (noms anglais + traduction française du jeu).
     * Lu par réflexion pour ne pas dépendre de la forme exacte de l'API Cobblemon.
     */
    private static Map<String, String> getNameMap() {
        if (flatNameToSpecies != null) return flatNameToSpecies;
        Map<String, String> map = new HashMap<>();
        try {
            Class<?> cls = Class.forName("com.cobblemon.mod.common.api.pokemon.PokemonSpecies");
            Object instance = cls.getField("INSTANCE").get(null);
            Object all = null;
            for (String getter : new String[]{"getImplemented", "getSpecies"}) {
                try {
                    all = cls.getMethod(getter).invoke(instance);
                    if (all != null) break;
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (all instanceof Iterable<?> iterable) {
                for (Object sp : iterable) {
                    if (!(sp instanceof com.cobblemon.mod.common.pokemon.Species species)) continue;
                    String name = species.getName().toLowerCase(Locale.ROOT);
                    map.putIfAbsent(flatten(name), name);
                    String key = "cobblemon.species." + name + ".name";
                    String fr = net.minecraft.client.resource.language.I18n.translate(key);
                    if (!fr.equals(key)) map.putIfAbsent(flatten(fr), name);
                    String fr2 = FrenchNames.get(name);
                    if (fr2 != null) map.putIfAbsent(flatten(fr2), name);
                }
            }
        } catch (Throwable t) {
            TropiTrackerClient.LOGGER.warn("[Catch] Liste des espèces Cobblemon indisponible", t);
        }
        // Ne garder le cache que s'il est rempli (les traductions peuvent ne pas être prêtes trop tôt)
        if (!map.isEmpty()) flatNameToSpecies = map;
        return map;
    }

    // ------------------------------------------------------------------
    // Utilitaires
    // ------------------------------------------------------------------

    /** Minuscules, sans accents ni codes couleur Minecraft, espaces normalisés. */
    private static String flatten(String s) {
        String stripped = s.replaceAll("§.", "");
        String noAccents = java.text.Normalizer
            .normalize(stripped, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "");
        return noAccents.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static void say(String msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal(msg), false);
            }
        });
    }
}
