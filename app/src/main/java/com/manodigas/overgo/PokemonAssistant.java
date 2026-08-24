package com.manodigas.overgo;

import android.content.Context;

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PokemonAssistant {
    interface Callback {
        void onAnswer(String answer);
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);

    static void ask(Context context, String question, Callback callback) {
        Context app = context.getApplicationContext();
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) {
            callback.onAnswer("Pergunte sobre um Pokémon ou sobre sua coleção. Ex.: ‘qual meu maior IV?’, ‘fraquezas do Garchomp?’ ou ‘compare Milotic e Gyarados’. ");
            return;
        }

        String local = answerCollection(app, q);
        if (local != null) {
            callback.onAnswer(local);
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                List<String> species = findSpeciesInQuestion(app, q);
                if (species.size() >= 2 && containsAny(q, "compar", "melhor entre", "vs", "versus")) {
                    PokemonInfo a = fetchPokemon(species.get(0));
                    PokemonInfo b = fetchPokemon(species.get(1));
                    callback.onAnswer(compare(a, b));
                    return;
                }
                if (!species.isEmpty()) {
                    PokemonInfo info = fetchPokemon(species.get(0));
                    callback.onAnswer(answerSpeciesQuestion(q, info));
                    return;
                }
                callback.onAnswer("Não encontrei o nome de um Pokémon nessa pergunta. Posso responder sobre tipos, fraquezas, stats, habilidades, movimentos disponíveis, comparar dois Pokémon e analisar sua coleção salva.");
            } catch (Exception e) {
                callback.onAnswer("Não consegui consultar os dados Pokémon agora. Verifique sua internet e tente de novo.");
            }
        });
    }

    private static String answerCollection(Context context, String question) {
        List<PokemonRecord> items = CollectionStore.list(context);
        String q = normalize(question);
        boolean collectionIntent = containsAny(q, "meu", "minha", "colecao", "tenho", "salvos", "salvo", "capturados");

        if (containsAny(q, "quantos tenho", "quantidade", "quantos pokemon", "quantos pokemons")) {
            return items.isEmpty() ? "Sua coleção está vazia." : "Sua coleção tem " + items.size() + " fichas salvas.";
        }
        if (!collectionIntent && !containsAny(q, "maior iv", "maior cp", "maior pc", "100%", "perfeito")) return null;
        if (items.isEmpty()) return "Sua coleção ainda está vazia. Faça um scan na tela de Avaliar para criar a primeira ficha.";

        if (containsAny(q, "maior iv", "melhor iv", "iv mais alto", "melhor pokemon")) {
            PokemonRecord best = Collections.max(items, Comparator.comparingInt(item -> item.iv));
            return cardSentence("Maior IV", best);
        }
        if (containsAny(q, "maior cp", "maior pc", "cp mais alto", "pc mais alto", "mais forte")) {
            PokemonRecord best = Collections.max(items, Comparator.comparingInt(item -> item.cp));
            return cardSentence("Maior CP", best);
        }
        if (containsAny(q, "100%", "perfeito", "perfeitos", "hundo")) {
            List<PokemonRecord> perfect = new ArrayList<>();
            for (PokemonRecord item : items) if (item.iv == 100) perfect.add(item);
            if (perfect.isEmpty()) return "Você ainda não tem nenhuma ficha com IV 100% salva.";
            StringBuilder out = new StringBuilder("IV 100% salvos: ");
            for (int i = 0; i < perfect.size(); i++) {
                if (i > 0) out.append(", ");
                out.append(perfect.get(i).name).append(" (CP ").append(perfect.get(i).cp).append(')');
            }
            return out.toString() + ".";
        }
        if (containsAny(q, "evoluir", "investir", "upar", "fortalecer")) {
            PokemonRecord best = Collections.max(items, (a, b) -> {
                int scoreA = a.iv * 10000 + a.cp;
                int scoreB = b.iv * 10000 + b.cp;
                return Integer.compare(scoreA, scoreB);
            });
            return "Pelas fichas salvas, eu priorizaria " + best.name + " (CP " + best.cp + ", IV " + best.iv + "%). Isso considera IV primeiro e CP como desempate; para PvP, o melhor IV pode ser diferente.";
        }
        if (containsAny(q, "time", "equipe", "top 6", "seis melhores")) {
            List<PokemonRecord> copy = new ArrayList<>(items);
            copy.sort((a, b) -> {
                int scoreA = a.iv * 10000 + a.cp;
                int scoreB = b.iv * 10000 + b.cp;
                return Integer.compare(scoreB, scoreA);
            });
            StringBuilder out = new StringBuilder("Pelas fichas atuais, meus 6 destaques seriam: ");
            int count = Math.min(6, copy.size());
            for (int i = 0; i < count; i++) {
                PokemonRecord item = copy.get(i);
                if (i > 0) out.append("; ");
                out.append(item.name).append(" CP ").append(item.cp).append(" IV ").append(item.iv).append('%');
            }
            return out.append(". Para montar um time por cobertura de tipos, pergunte pelos Pokémon específicos que você quer usar.").toString();
        }
        return null;
    }

    private static String answerSpeciesQuestion(String question, PokemonInfo info) {
        String q = normalize(question);
        if (containsAny(q, "fraque", "counter", "contra", "mata", "super efetivo")) {
            Map<String, Float> weaknesses = combinedWeaknesses(info.types);
            List<Map.Entry<String, Float>> entries = new ArrayList<>(weaknesses.entrySet());
            entries.removeIf(e -> e.getValue() <= 1.01f);
            entries.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
            if (entries.isEmpty()) return info.displayName + " não tem fraquezas comuns detectadas pelos tipos atuais.";
            StringBuilder out = new StringBuilder(info.displayName).append(" é fraco contra: ");
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) out.append(", ");
                out.append(typePt(entries.get(i).getKey()));
                if (entries.get(i).getValue() >= 3.9f) out.append(" (4×)");
                else out.append(" (2×)");
            }
            return out.append('.').toString();
        }
        if (containsAny(q, "tipo", "tipagem")) {
            return info.displayName + " é do tipo " + joinTypes(info.types) + ".";
        }
        if (containsAny(q, "status", "stats", "atributos", "ataque", "defesa", "hp", "ps")) {
            return info.displayName + " — HP " + info.hp + ", Ataque " + info.attack + ", Defesa " + info.defense
                    + ", Atq. Esp. " + info.specialAttack + ", Def. Esp. " + info.specialDefense + ", Velocidade " + info.speed + ".";
        }
        if (containsAny(q, "habilidade", "ability", "abilities")) {
            return "Habilidades de " + info.displayName + ": " + String.join(", ", info.abilities) + ".";
        }
        if (containsAny(q, "movimento", "golpe", "move", "moves")) {
            StringBuilder out = new StringBuilder("Alguns movimentos que ").append(info.displayName).append(" pode aprender: ");
            int count = Math.min(12, info.moves.size());
            for (int i = 0; i < count; i++) {
                if (i > 0) out.append(", ");
                out.append(prettyToken(info.moves.get(i)));
            }
            return out.append(". Essa lista é de disponibilidade geral do Pokémon; não estou chamando esses golpes de ‘melhores’ para Pokémon GO.").toString();
        }
        return info.displayName + " é do tipo " + joinTypes(info.types) + ". Stats base: HP " + info.hp + ", Ataque " + info.attack
                + ", Defesa " + info.defense + ", Atq. Esp. " + info.specialAttack + ", Def. Esp. " + info.specialDefense
                + ", Velocidade " + info.speed + ". Você pode perguntar por fraquezas, tipos, stats, habilidades ou movimentos.";
    }

    private static String compare(PokemonInfo a, PokemonInfo b) {
        int totalA = a.totalStats();
        int totalB = b.totalStats();
        String winner = totalA == totalB ? "empatam em total de stats base" : (totalA > totalB ? a.displayName : b.displayName) + " tem maior total de stats base";
        return a.displayName + " (" + joinTypes(a.types) + ", total " + totalA + ") vs "
                + b.displayName + " (" + joinTypes(b.types) + ", total " + totalB + "): " + winner
                + ". Isso não substitui uma comparação específica de Pokémon GO/PvP, onde CP, nível, IVs e golpes mudam o resultado.";
    }

    private static PokemonInfo fetchPokemon(String displayName) throws Exception {
        String apiName = PokemonSpeciesIndex.toApiName(displayName);
        URL url = new URL("https://pokeapi.co/api/v2/pokemon/" + apiName);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
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
        PokemonInfo info = new PokemonInfo(PokemonSpeciesIndex.pretty(root.optString("name", apiName)));

        JSONArray types = root.optJSONArray("types");
        if (types != null) {
            for (int i = 0; i < types.length(); i++) {
                JSONObject type = types.optJSONObject(i);
                JSONObject typeObj = type == null ? null : type.optJSONObject("type");
                if (typeObj != null) info.types.add(typeObj.optString("name", ""));
            }
        }
        JSONArray stats = root.optJSONArray("stats");
        if (stats != null) {
            for (int i = 0; i < stats.length(); i++) {
                JSONObject statRow = stats.optJSONObject(i);
                if (statRow == null) continue;
                JSONObject statObj = statRow.optJSONObject("stat");
                String stat = statObj == null ? "" : statObj.optString("name", "");
                int value = statRow.optInt("base_stat", 0);
                switch (stat) {
                    case "hp": info.hp = value; break;
                    case "attack": info.attack = value; break;
                    case "defense": info.defense = value; break;
                    case "special-attack": info.specialAttack = value; break;
                    case "special-defense": info.specialDefense = value; break;
                    case "speed": info.speed = value; break;
                }
            }
        }
        JSONArray abilities = root.optJSONArray("abilities");
        if (abilities != null) {
            for (int i = 0; i < abilities.length(); i++) {
                JSONObject row = abilities.optJSONObject(i);
                JSONObject ability = row == null ? null : row.optJSONObject("ability");
                if (ability != null) info.abilities.add(prettyToken(ability.optString("name", "")));
            }
        }
        JSONArray moves = root.optJSONArray("moves");
        if (moves != null) {
            for (int i = 0; i < moves.length(); i++) {
                JSONObject row = moves.optJSONObject(i);
                JSONObject move = row == null ? null : row.optJSONObject("move");
                if (move != null) info.moves.add(move.optString("name", ""));
            }
        }
        return info;
    }

    private static List<String> findSpeciesInQuestion(Context context, String question) {
        String normalizedQuestion = normalize(question);
        List<String> found = new ArrayList<>();
        for (String apiName : PokemonSpeciesIndex.getNames(context)) {
            String display = PokemonSpeciesIndex.pretty(apiName);
            String n = normalize(display);
            if (n.length() >= 3 && normalizedQuestion.contains(n)) found.add(display);
        }
        found.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return found;
    }

    private static Map<String, Float> combinedWeaknesses(List<String> defendingTypes) {
        Map<String, Float> result = new LinkedHashMap<>();
        String[] types = {"normal","fire","water","electric","grass","ice","fighting","poison","ground","flying","psychic","bug","rock","ghost","dragon","dark","steel","fairy"};
        for (String attack : types) {
            float multiplier = 1f;
            for (String defend : defendingTypes) multiplier *= typeMultiplier(attack, defend);
            result.put(attack, multiplier);
        }
        return result;
    }

    private static float typeMultiplier(String a, String d) {
        String key = a + ">" + d;
        switch (key) {
            case "normal>rock": case "normal>steel": return 0.5f;
            case "normal>ghost": return 0f;
            case "fire>fire": case "fire>water": case "fire>rock": case "fire>dragon": return 0.5f;
            case "fire>grass": case "fire>ice": case "fire>bug": case "fire>steel": return 2f;
            case "water>water": case "water>grass": case "water>dragon": return 0.5f;
            case "water>fire": case "water>ground": case "water>rock": return 2f;
            case "electric>electric": case "electric>grass": case "electric>dragon": return 0.5f;
            case "electric>water": case "electric>flying": return 2f;
            case "electric>ground": return 0f;
            case "grass>fire": case "grass>grass": case "grass>poison": case "grass>flying": case "grass>bug": case "grass>dragon": case "grass>steel": return 0.5f;
            case "grass>water": case "grass>ground": case "grass>rock": return 2f;
            case "ice>fire": case "ice>water": case "ice>ice": case "ice>steel": return 0.5f;
            case "ice>grass": case "ice>ground": case "ice>flying": case "ice>dragon": return 2f;
            case "fighting>poison": case "fighting>flying": case "fighting>psychic": case "fighting>bug": case "fighting>fairy": return 0.5f;
            case "fighting>normal": case "fighting>ice": case "fighting>rock": case "fighting>dark": case "fighting>steel": return 2f;
            case "fighting>ghost": return 0f;
            case "poison>poison": case "poison>ground": case "poison>rock": case "poison>ghost": return 0.5f;
            case "poison>grass": case "poison>fairy": return 2f;
            case "poison>steel": return 0f;
            case "ground>grass": case "ground>bug": return 0.5f;
            case "ground>fire": case "ground>electric": case "ground>poison": case "ground>rock": case "ground>steel": return 2f;
            case "ground>flying": return 0f;
            case "flying>electric": case "flying>rock": case "flying>steel": return 0.5f;
            case "flying>grass": case "flying>fighting": case "flying>bug": return 2f;
            case "psychic>psychic": case "psychic>steel": return 0.5f;
            case "psychic>fighting": case "psychic>poison": return 2f;
            case "psychic>dark": return 0f;
            case "bug>fire": case "bug>fighting": case "bug>poison": case "bug>flying": case "bug>ghost": case "bug>steel": case "bug>fairy": return 0.5f;
            case "bug>grass": case "bug>psychic": case "bug>dark": return 2f;
            case "rock>fighting": case "rock>ground": case "rock>steel": return 0.5f;
            case "rock>fire": case "rock>ice": case "rock>flying": case "rock>bug": return 2f;
            case "ghost>dark": return 0.5f;
            case "ghost>psychic": case "ghost>ghost": return 2f;
            case "ghost>normal": return 0f;
            case "dragon>steel": return 0.5f;
            case "dragon>dragon": return 2f;
            case "dragon>fairy": return 0f;
            case "dark>fighting": case "dark>dark": case "dark>fairy": return 0.5f;
            case "dark>psychic": case "dark>ghost": return 2f;
            case "steel>fire": case "steel>water": case "steel>electric": case "steel>steel": return 0.5f;
            case "steel>ice": case "steel>rock": case "steel>fairy": return 2f;
            case "fairy>fire": case "fairy>poison": case "fairy>steel": return 0.5f;
            case "fairy>fighting": case "fairy>dragon": case "fairy>dark": return 2f;
            default: return 1f;
        }
    }

    private static String joinTypes(List<String> types) {
        List<String> pt = new ArrayList<>();
        for (String type : types) pt.add(typePt(type));
        return String.join(" / ", pt);
    }

    private static String typePt(String type) {
        Map<String, String> map = new HashMap<>();
        map.put("normal","Normal"); map.put("fire","Fogo"); map.put("water","Água"); map.put("electric","Elétrico");
        map.put("grass","Planta"); map.put("ice","Gelo"); map.put("fighting","Lutador"); map.put("poison","Veneno");
        map.put("ground","Terrestre"); map.put("flying","Voador"); map.put("psychic","Psíquico"); map.put("bug","Inseto");
        map.put("rock","Pedra"); map.put("ghost","Fantasma"); map.put("dragon","Dragão"); map.put("dark","Sombrio");
        map.put("steel","Aço"); map.put("fairy","Fada");
        return map.getOrDefault(type, prettyToken(type));
    }

    private static String cardSentence(String prefix, PokemonRecord record) {
        return prefix + ": " + record.name + " — CP " + record.cp + " — IV " + record.iv + "%.";
    }

    private static String prettyToken(String value) {
        if (value == null || value.isEmpty()) return value;
        String[] parts = value.split("-");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9% ]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean containsAny(String text, String... pieces) {
        String normalized = normalize(text);
        for (String piece : pieces) if (normalized.contains(normalize(piece))) return true;
        return false;
    }

    private static final class PokemonInfo {
        final String displayName;
        final List<String> types = new ArrayList<>();
        final List<String> abilities = new ArrayList<>();
        final List<String> moves = new ArrayList<>();
        int hp;
        int attack;
        int defense;
        int specialAttack;
        int specialDefense;
        int speed;

        PokemonInfo(String displayName) { this.displayName = displayName; }
        int totalStats() { return hp + attack + defense + specialAttack + specialDefense + speed; }
    }
}
