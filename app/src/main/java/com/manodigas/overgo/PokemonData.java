package com.manodigas.overgo;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.mlkit.vision.text.Text;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

class PokemonRecord {
    final String name;
    final int cp;
    final int iv;

    PokemonRecord(String name, int cp, int iv) {
        this.name = name;
        this.cp = cp;
        this.iv = iv;
    }
}

class CollectionStore {
    private static final String PREFS = "overgo_collection";
    private static final String KEY_ITEMS = "items";
    private static final int MAX_ITEMS = 500;

    static synchronized boolean add(Context context, PokemonRecord record) {
        List<PokemonRecord> items = list(context);
        for (PokemonRecord item : items) {
            if (item.name.equalsIgnoreCase(record.name) && item.cp == record.cp && item.iv == record.iv) {
                return false;
            }
        }
        items.add(0, record);
        if (items.size() > MAX_ITEMS) items = new ArrayList<>(items.subList(0, MAX_ITEMS));
        save(context, items);
        return true;
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
                String name = obj.optString("name", "").trim();
                int cp = obj.optInt("cp", -1);
                int iv = obj.optInt("iv", -1);
                if (!name.isEmpty() && cp >= 10 && iv >= 0 && iv <= 100) {
                    result.add(new PokemonRecord(name, cp, iv));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ITEMS, "[]").apply();
    }

    private static void save(Context context, List<PokemonRecord> items) {
        JSONArray array = new JSONArray();
        for (PokemonRecord item : items) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("name", item.name);
                obj.put("cp", item.cp);
                obj.put("iv", item.iv);
                array.put(obj);
            } catch (Exception ignored) {
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ITEMS, array.toString()).apply();
    }
}

class ScreenPokemonParser {
    static ParseResult parse(
            Context context,
            Text recognizedText,
            int screenHeight,
            IvResult ivResult,
            int focusedCp
    ) {
        if (recognizedText == null) return ParseResult.error("Não consegui ler o texto da tela.");

        int cp = focusedCp >= 10 ? focusedCp : CpReader.extract(recognizedText);
        if (cp < 10) {
            return ParseResult.error("CP não encontrado. Deixe o CP visível e tente de novo.");
        }

        String name = PokemonSpeciesIndex.resolve(context, recognizedText, screenHeight);
        if (name == null) {
            if (PokemonSpeciesIndex.getNames(context).isEmpty()) {
                return ParseResult.error("A Pokédex ainda está carregando. Volte ao OverGo e aguarde a lista de espécies.");
            }
            return ParseResult.error("Pokémon não identificado com confiança. Não salvei uma ficha errada.");
        }

        if (ivResult == null) {
            return ParseResult.error("IV não detectado. Abra Avaliar/Appraise para mostrar as 3 barras e faça o scan novamente.");
        }

        return ParseResult.success(new PokemonRecord(name, cp, ivResult.percent), ivResult);
    }
}

class ParseResult {
    final PokemonRecord record;
    final IvResult ivResult;
    final String error;

    private ParseResult(PokemonRecord record, IvResult ivResult, String error) {
        this.record = record;
        this.ivResult = ivResult;
        this.error = error;
    }

    static ParseResult success(PokemonRecord record, IvResult ivResult) {
        return new ParseResult(record, ivResult, null);
    }

    static ParseResult error(String error) {
        return new ParseResult(null, null, error);
    }

    boolean isSuccess() {
        return record != null;
    }

    String summary(boolean inserted) {
        if (!isSuccess()) return error;
        return record.name + "\nCP " + record.cp + " • IV " + record.iv + "%"
                + (inserted ? "\nFicha salva." : "\nEssa ficha já estava salva.");
    }
}
