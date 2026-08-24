package com.manodigas.overgo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class PokemonRecord {
    final String name;
    final int cp;
    final String rawText;
    final long scannedAt;

    PokemonRecord(String name, int cp, String rawText, long scannedAt) {
        this.name = name;
        this.cp = cp;
        this.rawText = rawText;
        this.scannedAt = scannedAt;
    }
}

class CollectionStore {
    private static final String PREFS = "overgo_collection";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_ITEMS = 500;

    static synchronized void add(Context context, PokemonRecord record) {
        List<PokemonRecord> items = list(context);
        items.add(0, record);
        if (items.size() > MAX_ITEMS) {
            items = new ArrayList<>(items.subList(0, MAX_ITEMS));
        }
        save(context, items);
    }

    static synchronized List<PokemonRecord> list(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_ITEMS, "[]");
        List<PokemonRecord> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                result.add(new PokemonRecord(
                        obj.optString("name", "Pokémon"),
                        obj.optInt("cp", -1),
                        obj.optString("rawText", ""),
                        obj.optLong("scannedAt", 0L)
                ));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ITEMS, "[]")
                .apply();
    }

    private static void save(Context context, List<PokemonRecord> items) {
        JSONArray array = new JSONArray();
        for (PokemonRecord item : items) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("name", item.name);
                obj.put("cp", item.cp);
                obj.put("rawText", item.rawText);
                obj.put("scannedAt", item.scannedAt);
                array.put(obj);
            } catch (Exception ignored) {
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ITEMS, array.toString())
                .apply();
    }
}

class ScreenPokemonParser {
    private static final Pattern CP_PATTERN = Pattern.compile("(?i)\\b(?:CP|PC)\\s*([0-9]{1,5})\\b");

    static PokemonRecord parse(String rawText) {
        String safe = rawText == null ? "" : rawText.trim();
        int cp = -1;
        Matcher cpMatcher = CP_PATTERN.matcher(safe);
        if (cpMatcher.find()) {
            try {
                cp = Integer.parseInt(cpMatcher.group(1));
            } catch (Exception ignored) {
            }
        }

        String name = guessName(safe);
        String clipped = safe.length() > 1200 ? safe.substring(0, 1200) : safe;
        return new PokemonRecord(name, cp, clipped, System.currentTimeMillis());
    }

    static String summary(PokemonRecord record) {
        StringBuilder text = new StringBuilder();
        text.append("Detectado: ").append(record.name);
        if (record.cp >= 0) text.append(" • PC/CP ").append(record.cp);
        if (record.rawText.isEmpty()) text.append("\nNenhum texto reconhecido na tela.");
        else text.append("\nSalvo na coleção local.");
        return text.toString();
    }

    private static String guessName(String raw) {
        String[] lines = raw.split("\\r?\\n");
        for (String value : lines) {
            String line = value.trim();
            if (line.length() < 2 || line.length() > 28) continue;
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.matches(".*\\d.*")) continue;
            if (upper.startsWith("CP") || upper.startsWith("PC")) continue;
            if (upper.contains("POKÉMON") || upper.contains("POKEMON")) continue;
            if (upper.contains("ATAQUE") || upper.contains("DEFESA") || upper.contains("ATTACK") || upper.contains("DEFENSE")) continue;
            if (upper.contains("FORTALECER") || upper.contains("POWER UP") || upper.contains("EVOLUIR") || upper.contains("EVOLVE")) continue;
            if (upper.contains("PESO") || upper.contains("ALTURA") || upper.contains("WEIGHT") || upper.contains("HEIGHT")) continue;
            if (upper.contains("DOCE") || upper.contains("CANDY") || upper.contains("POEIRA") || upper.contains("STARDUST")) continue;
            return line;
        }
        return "Pokémon não identificado";
    }
}

class LocalPokemonAssistant {
    static String answer(Context context, String question) {
        List<PokemonRecord> items = CollectionStore.list(context);
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT).trim();

        if (q.isEmpty()) {
            return "Pergunte algo sobre a coleção, por exemplo: ‘qual tem o maior CP?’";
        }

        if (items.isEmpty()) {
            return "Sua coleção local ainda está vazia. Inicie o scanner e registre alguns Pokémon primeiro.";
        }

        if (q.contains("quantos") || q.contains("quantidade")) {
            return "Tenho " + items.size() + " registros na sua coleção local.";
        }

        if (q.contains("maior cp") || q.contains("maior pc") || q.contains("mais forte")) {
            PokemonRecord best = Collections.max(items, Comparator.comparingInt(item -> item.cp));
            if (best.cp < 0) return "Ainda não consegui ler CP/PC dos Pokémon registrados.";
            return "O maior CP/PC que encontrei é " + best.name + " com " + best.cp + ".";
        }

        if (q.contains("último") || q.contains("ultimo") || q.contains("recente")) {
            PokemonRecord latest = items.get(0);
            return "O registro mais recente é " + latest.name + (latest.cp >= 0 ? " com CP/PC " + latest.cp : "") + ".";
        }

        return "Já consigo usar sua coleção como contexto. Neste MVP eu respondo sobre quantidade, maior CP/PC e registros recentes; a próxima etapa é conectar o modelo de IA Pokémon completo.";
    }
}
