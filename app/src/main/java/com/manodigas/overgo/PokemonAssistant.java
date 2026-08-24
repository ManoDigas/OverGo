package com.manodigas.overgo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PokemonAssistant {
    interface Callback {
        void onAnswer(String answer);
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final String PREFS = "overgo_ai";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_BACKEND_URL = "backend_url";
    private static final int MAX_HISTORY_ITEMS = 16;
    private static final int MAX_COLLECTION_ITEMS = 250;

    private PokemonAssistant() {}

    static void ask(Context context, String question, Callback callback) {
        Context app = context.getApplicationContext();
        String cleanQuestion = question == null ? "" : question.trim();
        if (cleanQuestion.isEmpty()) {
            callback.onAnswer("Escreva uma pergunta sobre Pokémon ou sobre suas fichas.");
            return;
        }

        String backendUrl = getBackendUrl(app);
        if (backendUrl.isEmpty()) {
            callback.onAnswer("A IA generativa do OverGo ainda não está conectada ao servidor. O código do modelo já está pronto, mas o backend precisa ser publicado e ligado ao app antes desta versão final.");
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                JSONArray history = loadHistory(app);
                JSONObject payload = new JSONObject();
                payload.put("question", cleanQuestion);
                payload.put("collection", collectionJson(app));
                payload.put("history", history);

                JSONObject response = postJson(backendUrl, payload);
                String answer = response.optString("answer", "").trim();
                if (answer.isEmpty()) {
                    callback.onAnswer("A IA respondeu sem texto. Tente reformular a pergunta.");
                    return;
                }

                appendHistory(app, "user", cleanQuestion);
                appendHistory(app, "assistant", answer);
                callback.onAnswer(answer);
            } catch (AiHttpException e) {
                if (e.status == 503) {
                    callback.onAnswer("O servidor da IA existe, mas ainda não recebeu a chave do modelo.");
                } else {
                    callback.onAnswer("A IA do OverGo não conseguiu responder agora. Erro do servidor: " + e.status + ".");
                }
            } catch (Exception e) {
                callback.onAnswer("Não consegui conectar à IA do OverGo. Verifique a conexão e tente novamente.");
            }
        });
    }

    static void clearHistory(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_HISTORY, "[]")
                .apply();
    }

    static void setBackendUrl(Context context, String url) {
        String value = url == null ? "" : url.trim();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_BACKEND_URL, value)
                .apply();
    }

    static String getBackendUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String override = prefs.getString(KEY_BACKEND_URL, "");
        if (override != null && !override.trim().isEmpty()) return override.trim();
        return BuildConfig.OVERGO_AI_URL == null ? "" : BuildConfig.OVERGO_AI_URL.trim();
    }

    private static JSONArray collectionJson(Context context) {
        JSONArray array = new JSONArray();
        List<PokemonRecord> items = CollectionStore.list(context);
        int limit = Math.min(items.size(), MAX_COLLECTION_ITEMS);
        for (int i = 0; i < limit; i++) {
            PokemonRecord item = items.get(i);
            try {
                JSONObject row = new JSONObject();
                row.put("pokemon", item.name);
                row.put("cp", item.cp);
                row.put("iv", item.iv);
                array.put(row);
            } catch (Exception ignored) {
            }
        }
        return array;
    }

    private static JSONObject postJson(String endpoint, JSONObject payload) throws Exception {
        URL url = new URL(endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");

        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(bytes);
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String body = readAll(stream);
        connection.disconnect();

        if (status < 200 || status >= 300) throw new AiHttpException(status, body);
        return new JSONObject(body);
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private static synchronized JSONArray loadHistory(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_HISTORY, "[]");
        try {
            JSONArray source = new JSONArray(raw == null ? "[]" : raw);
            JSONArray trimmed = new JSONArray();
            int start = Math.max(0, source.length() - MAX_HISTORY_ITEMS);
            for (int i = start; i < source.length(); i++) trimmed.put(source.get(i));
            return trimmed;
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static synchronized void appendHistory(Context context, String role, String content) {
        try {
            JSONArray history = loadHistory(context);
            JSONObject item = new JSONObject();
            item.put("role", role);
            item.put("content", content.length() > 5000 ? content.substring(0, 5000) : content);
            history.put(item);

            JSONArray trimmed = new JSONArray();
            int start = Math.max(0, history.length() - MAX_HISTORY_ITEMS);
            for (int i = start; i < history.length(); i++) trimmed.put(history.get(i));

            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_HISTORY, trimmed.toString())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private static final class AiHttpException extends Exception {
        final int status;
        AiHttpException(int status, String body) {
            super(body);
            this.status = status;
        }
    }
}
