package com.manodigas.overgo;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.mlkit.vision.text.Text;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PokemonSpeciesIndex {
    private static final String PREFS = "overgo_species";
    private static final String KEY_NAMES = "names";
    private static final String SPECIES_URL = "https://pokeapi.co/api/v2/pokemon-species?limit=2000&offset=0";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile List<String> cachedNames;
    private static volatile Map<String, String> normalizedToApiName;

    interface ReadyCallback {
        void onReady(int count);
        void onError();
    }

    static void preload(Context context, ReadyCallback callback) {
        List<String> existing = getNames(context);
        if (existing.size() > 500) {
            if (callback != null) callback.onReady(existing.size());
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(SPECIES_URL).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("Accept", "application/json");
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
                JSONObject root = new JSONObject(body.toString());
                JSONArray results = root.optJSONArray("results");
                if (results == null) throw new IllegalStateException("missing results");
                List<String> names = new ArrayList<>();
                for (int i = 0; i < results.length(); i++) {
                    JSONObject item = results.optJSONObject(i);
                    if (item == null) continue;
                    String name = item.optString("name", "").trim();
                    if (!name.isEmpty()) names.add(name);
                }
                Collections.sort(names);
                save(context.getApplicationContext(), names);
                setCache(names);
                if (callback != null) callback.onReady(names.size());
            } catch (Exception ignored) {
                if (callback != null) callback.onError();
            }
        });
    }

    static List<String> getNames(Context context) {
        List<String> memory = cachedNames;
        if (memory != null) return memory;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_NAMES, "[]");
        List<String> names = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "");
                if (!value.isEmpty()) names.add(value);
            }
        } catch (Exception ignored) {
        }
        setCache(names);
        return cachedNames;
    }

    static String resolve(Context context, Text text, int screenHeight) {
        List<String> names = getNames(context);
        if (names.isEmpty() || text == null) return null;
        ensureMap(names);

        Candidate best = null;
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String visible = line.getText() == null ? "" : line.getText().trim();
                if (visible.length() < 2 || visible.length() > 35) continue;
                String normalized = normalize(visible);
                if (normalized.length() < 2) continue;
                if (isUiWord(normalized)) continue;

                float yPenalty = 0f;
                if (line.getBoundingBox() != null && screenHeight > 0) {
                    float centerY = line.getBoundingBox().centerY() / (float) screenHeight;
                    if (centerY > 0.72f) yPenalty = 0.20f;
                    else if (centerY < 0.10f) yPenalty = 0.08f;
                }

                String exact = normalizedToApiName.get(normalized);
                if (exact != null) {
                    float score = 1.0f - yPenalty;
                    if (best == null || score > best.score) best = new Candidate(exact, score);
                    continue;
                }

                String fuzzyName = null;
                float fuzzyScore = 0f;
                for (String apiName : names) {
                    String species = normalizeSpecies(apiName);
                    int maxLen = Math.max(normalized.length(), species.length());
                    if (Math.abs(normalized.length() - species.length()) > 2) continue;
                    int distance = levenshtein(normalized, species);
                    int allowed = maxLen <= 5 ? 1 : (maxLen <= 10 ? 2 : 3);
                    if (distance > allowed) continue;
                    float score = 1f - (distance / (float) maxLen) - yPenalty;
                    if (score > fuzzyScore) {
                        fuzzyScore = score;
                        fuzzyName = apiName;
                    }
                }
                if (fuzzyName != null && fuzzyScore >= 0.78f && (best == null || fuzzyScore > best.score)) {
                    best = new Candidate(fuzzyName, fuzzyScore);
                }
            }
        }
        return best == null ? null : pretty(best.apiName);
    }

    static String pretty(String apiName) {
        if (apiName == null || apiName.isEmpty()) return "Pokémon";
        switch (apiName) {
            case "mr-mime": return "Mr. Mime";
            case "mime-jr": return "Mime Jr.";
            case "farfetchd": return "Farfetch'd";
            case "sirfetchd": return "Sirfetch'd";
            case "nidoran-f": return "Nidoran♀";
            case "nidoran-m": return "Nidoran♂";
            case "type-null": return "Type: Null";
            case "flabebe": return "Flabébé";
            default:
                String[] parts = apiName.split("-");
                StringBuilder out = new StringBuilder();
                for (String part : parts) {
                    if (part.isEmpty()) continue;
                    if (out.length() > 0) out.append('-');
                    out.append(Character.toUpperCase(part.charAt(0)));
                    if (part.length() > 1) out.append(part.substring(1));
                }
                return out.toString();
        }
    }

    static String toApiName(String displayName) {
        if (displayName == null) return "";
        String n = normalize(displayName);
        Map<String, String> map = normalizedToApiName;
        if (map != null && map.containsKey(n)) return map.get(n);
        return displayName.toLowerCase(Locale.ROOT)
                .replace("♀", "-f")
                .replace("♂", "-m")
                .replace("'", "")
                .replace(".", "")
                .replace(":", "")
                .replace(" ", "-");
    }

    private static void save(Context context, List<String> names) {
        JSONArray array = new JSONArray();
        for (String name : names) array.put(name);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_NAMES, array.toString()).apply();
    }

    private static synchronized void setCache(List<String> names) {
        cachedNames = Collections.unmodifiableList(new ArrayList<>(names));
        normalizedToApiName = null;
    }

    private static synchronized void ensureMap(List<String> names) {
        if (normalizedToApiName != null) return;
        Map<String, String> map = new HashMap<>();
        for (String name : names) map.put(normalizeSpecies(name), name);
        map.put("mrmime", "mr-mime");
        map.put("mimejr", "mime-jr");
        map.put("farfetchd", "farfetchd");
        map.put("sirfetchd", "sirfetchd");
        map.put("nidoran", "nidoran-f");
        normalizedToApiName = map;
    }

    private static String normalizeSpecies(String name) {
        return normalize(name.replace("-f", "").replace("-m", ""));
    }

    private static String normalize(String value) {
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        return ascii.replaceAll("[^a-z0-9]", "");
    }

    private static boolean isUiWord(String value) {
        String[] blocked = {
                "attack", "ataque", "defense", "defesa", "hp", "ps", "appraise", "avaliar",
                "weight", "peso", "height", "altura", "powerup", "fortalecer", "evolve", "evoluir",
                "candy", "doce", "stardust", "poeiraestelar", "leve", "heavy", "light", "pokemon",
                "transfer", "transferir", "favorite", "favorito", "newattack", "novoataque"
        };
        for (String item : blocked) if (value.equals(item)) return true;
        return false;
    }

    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static final class Candidate {
        final String apiName;
        final float score;
        Candidate(String apiName, float score) {
            this.apiName = apiName;
            this.score = score;
        }
    }
}
