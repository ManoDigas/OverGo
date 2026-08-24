package com.manodigas.overgo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1002;

    private TextView statusView;
    private TextView pokedexStatusView;
    private LinearLayout collectionContainer;
    private EditText questionInput;
    private TextView answerView;
    private Button askButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        requestNotificationPermissionIfNeeded();
        refreshCollection();
        preloadPokedex();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null && Settings.canDrawOverlays(this)) {
            statusView.setText("Sobreposição autorizada. Abra Avaliar no Pokémon GO e inicie o scanner.");
        }
        refreshCollection();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            statusView.setText("Captura de tela cancelada.");
            return;
        }

        Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
        serviceIntent.setAction(ScreenCaptureService.ACTION_START);
        serviceIntent.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
        serviceIntent.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent);
        else startService(serviceIntent);

        statusView.setText("Scanner ativo. No Pokémon GO: abra Avaliar/Appraise e toque na bolha SCAN.");
    }

    private ScrollView buildUi() {
        int padding = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("OverGo");
        title.setTextSize(30f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("Scanner de Pokémon GO + coleção local + assistente Pokémon.");
        subtitle.setTextSize(15f);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(4), 0, dp(14));
        root.addView(subtitle, matchWrap());

        pokedexStatusView = new TextView(this);
        pokedexStatusView.setText("Pokédex: carregando lista de espécies...");
        pokedexStatusView.setTextSize(13f);
        pokedexStatusView.setGravity(Gravity.CENTER_HORIZONTAL);
        pokedexStatusView.setPadding(0, 0, 0, dp(12));
        root.addView(pokedexStatusView, matchWrap());

        Button overlayButton = new Button(this);
        overlayButton.setText("1. Autorizar sobreposição");
        overlayButton.setOnClickListener(v -> requestOverlayPermission());
        root.addView(overlayButton, matchWrap());

        Button startButton = new Button(this);
        startButton.setText("2. Iniciar scanner");
        startButton.setOnClickListener(v -> requestScreenCapture());
        root.addView(startButton, matchWrap());

        Button stopButton = new Button(this);
        stopButton.setText("Parar scanner");
        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, ScreenCaptureService.class));
            statusView.setText("Scanner parado.");
            refreshCollection();
        });
        root.addView(stopButton, matchWrap());

        statusView = new TextView(this);
        statusView.setText(Settings.canDrawOverlays(this)
                ? "Sobreposição autorizada."
                : "Autorize a sobreposição para começar.");
        statusView.setTextSize(14f);
        statusView.setPadding(0, dp(8), 0, dp(18));
        root.addView(statusView, matchWrap());

        TextView collectionTitle = new TextView(this);
        collectionTitle.setText("Minhas fichas");
        collectionTitle.setTextSize(22f);
        root.addView(collectionTitle, matchWrap());

        TextView collectionHelp = new TextView(this);
        collectionHelp.setText("Cada ficha salva contém somente Pokémon, CP e IV.");
        collectionHelp.setTextSize(13f);
        collectionHelp.setPadding(0, 0, 0, dp(8));
        root.addView(collectionHelp, matchWrap());

        collectionContainer = new LinearLayout(this);
        collectionContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(collectionContainer, matchWrap());

        Button refreshButton = new Button(this);
        refreshButton.setText("Atualizar fichas");
        refreshButton.setOnClickListener(v -> refreshCollection());
        root.addView(refreshButton, matchWrap());

        Button clearButton = new Button(this);
        clearButton.setText("Limpar fichas");
        clearButton.setOnClickListener(v -> {
            CollectionStore.clear(this);
            refreshCollection();
            answerView.setText("Fichas locais apagadas.");
        });
        root.addView(clearButton, matchWrap());

        TextView assistantTitle = new TextView(this);
        assistantTitle.setText("IA Pokémon");
        assistantTitle.setTextSize(22f);
        assistantTitle.setPadding(0, dp(18), 0, dp(4));
        root.addView(assistantTitle, matchWrap());

        TextView assistantHelp = new TextView(this);
        assistantHelp.setText("Pergunte sobre sua coleção ou sobre Pokémon: tipos, fraquezas, stats, habilidades, movimentos e comparações.");
        assistantHelp.setTextSize(13f);
        assistantHelp.setPadding(0, 0, 0, dp(6));
        root.addView(assistantHelp, matchWrap());

        questionInput = new EditText(this);
        questionInput.setHint("Ex.: fraquezas do Garchomp? / qual meu maior IV?");
        questionInput.setSingleLine(false);
        questionInput.setMinLines(2);
        root.addView(questionInput, matchWrap());

        askButton = new Button(this);
        askButton.setText("Perguntar");
        askButton.setOnClickListener(v -> askAssistant());
        root.addView(askButton, matchWrap());

        answerView = new TextView(this);
        answerView.setText("A IA usa suas fichas locais como contexto e consulta dados Pokémon quando necessário.");
        answerView.setTextSize(15f);
        answerView.setPadding(dp(12), dp(12), dp(12), dp(12));
        answerView.setBackground(roundedBackground(Color.rgb(242, 244, 247), dp(12), Color.rgb(220, 224, 230)));
        root.addView(answerView, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void preloadPokedex() {
        PokemonSpeciesIndex.preload(this, new PokemonSpeciesIndex.ReadyCallback() {
            @Override
            public void onReady(int count) {
                runOnUiThread(() -> pokedexStatusView.setText("Pokédex pronta: " + count + " espécies para validar o scan."));
            }

            @Override
            public void onError() {
                runOnUiThread(() -> pokedexStatusView.setText("Pokédex offline. Conecte à internet uma vez para carregar a lista de espécies."));
            }
        });
    }

    private void askAssistant() {
        String question = questionInput.getText().toString().trim();
        askButton.setEnabled(false);
        askButton.setText("Pensando...");
        answerView.setText("Analisando sua pergunta...");
        PokemonAssistant.ask(this, question, answer -> runOnUiThread(() -> {
            answerView.setText(answer);
            askButton.setEnabled(true);
            askButton.setText("Perguntar");
        }));
    }

    private void requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            statusView.setText("Sobreposição já autorizada.");
            return;
        }
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
        );
        startActivity(intent);
    }

    @SuppressWarnings("deprecation")
    private void requestScreenCapture() {
        if (!Settings.canDrawOverlays(this)) {
            statusView.setText("Primeiro autorize a sobreposição.");
            requestOverlayPermission();
            return;
        }
        if (PokemonSpeciesIndex.getNames(this).isEmpty()) {
            statusView.setText("Aguarde a Pokédex terminar de carregar antes do primeiro scan.");
            preloadPokedex();
            return;
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private void refreshCollection() {
        if (collectionContainer == null) return;
        collectionContainer.removeAllViews();
        List<PokemonRecord> items = CollectionStore.list(this);
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Nenhuma ficha salva. Abra um Pokémon, toque em Avaliar e faça o scan.");
            empty.setTextSize(14f);
            empty.setPadding(dp(4), dp(8), dp(4), dp(12));
            collectionContainer.addView(empty, matchWrap());
            return;
        }

        int limit = Math.min(items.size(), 30);
        for (int i = 0; i < limit; i++) {
            collectionContainer.addView(buildPokemonCard(items.get(i)), matchWrap());
        }
        if (items.size() > limit) {
            TextView more = new TextView(this);
            more.setText("+ " + (items.size() - limit) + " fichas não exibidas nesta tela.");
            more.setGravity(Gravity.CENTER_HORIZONTAL);
            collectionContainer.addView(more, matchWrap());
        }
    }

    private LinearLayout buildPokemonCard(PokemonRecord item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(roundedBackground(Color.WHITE, dp(14), Color.rgb(218, 222, 228)));

        TextView name = new TextView(this);
        name.setText(item.name);
        name.setTextSize(19f);
        name.setTextColor(Color.rgb(25, 30, 38));
        card.addView(name, matchWrap());

        LinearLayout values = new LinearLayout(this);
        values.setOrientation(LinearLayout.HORIZONTAL);
        values.setGravity(Gravity.CENTER_VERTICAL);

        TextView cp = new TextView(this);
        cp.setText("CP  " + item.cp);
        cp.setTextSize(16f);
        cp.setPadding(0, 0, dp(24), 0);
        values.addView(cp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView iv = new TextView(this);
        iv.setText("IV  " + item.iv + "%");
        iv.setTextSize(16f);
        iv.setGravity(Gravity.END);
        values.addView(iv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(values, matchWrap());
        return card;
    }

    private GradientDrawable roundedBackground(int fillColor, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(8);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
