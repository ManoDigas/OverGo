package com.manodigas.overgo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
    private TextView collectionView;
    private EditText questionInput;
    private TextView answerView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        requestNotificationPermissionIfNeeded();
        refreshCollection();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null && Settings.canDrawOverlays(this)) {
            statusView.setText("Sobreposição autorizada. O scanner pode ser iniciado.");
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        statusView.setText("Scanner ativo. Abra o Pokémon GO e toque na bolha SCAN.");
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
        subtitle.setText("Assistente Pokémon com coleção local criada a partir da tela que você autorizar.");
        subtitle.setTextSize(15f);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle, matchWrap());

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
        });
        root.addView(stopButton, matchWrap());

        statusView = new TextView(this);
        statusView.setText(Settings.canDrawOverlays(this)
                ? "Sobreposição autorizada."
                : "Autorize a sobreposição para começar.");
        statusView.setTextSize(14f);
        statusView.setPadding(0, dp(10), 0, dp(18));
        root.addView(statusView, matchWrap());

        TextView collectionTitle = new TextView(this);
        collectionTitle.setText("Minha coleção local");
        collectionTitle.setTextSize(21f);
        root.addView(collectionTitle, matchWrap());

        collectionView = new TextView(this);
        collectionView.setTextSize(14f);
        collectionView.setPadding(0, dp(6), 0, dp(8));
        root.addView(collectionView, matchWrap());

        Button refreshButton = new Button(this);
        refreshButton.setText("Atualizar coleção");
        refreshButton.setOnClickListener(v -> refreshCollection());
        root.addView(refreshButton, matchWrap());

        Button clearButton = new Button(this);
        clearButton.setText("Limpar coleção local");
        clearButton.setOnClickListener(v -> {
            CollectionStore.clear(this);
            refreshCollection();
            answerView.setText("Coleção local apagada.");
        });
        root.addView(clearButton, matchWrap());

        TextView assistantTitle = new TextView(this);
        assistantTitle.setText("Assistente OverGo");
        assistantTitle.setTextSize(21f);
        assistantTitle.setPadding(0, dp(18), 0, dp(4));
        root.addView(assistantTitle, matchWrap());

        questionInput = new EditText(this);
        questionInput.setHint("Ex.: qual dos meus Pokémon tem o maior CP?");
        questionInput.setSingleLine(false);
        root.addView(questionInput, matchWrap());

        Button askButton = new Button(this);
        askButton.setText("Perguntar");
        askButton.setOnClickListener(v -> {
            String answer = LocalPokemonAssistant.answer(this, questionInput.getText().toString());
            answerView.setText(answer);
        });
        root.addView(askButton, matchWrap());

        answerView = new TextView(this);
        answerView.setText("O assistente usa a sua coleção local como contexto.");
        answerView.setTextSize(15f);
        answerView.setPadding(0, dp(8), 0, dp(20));
        root.addView(answerView, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
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
        if (collectionView == null) return;
        List<PokemonRecord> items = CollectionStore.list(this);
        if (items.isEmpty()) {
            collectionView.setText("Nenhum Pokémon registrado ainda.");
            return;
        }

        StringBuilder text = new StringBuilder();
        int limit = Math.min(items.size(), 12);
        for (int i = 0; i < limit; i++) {
            PokemonRecord item = items.get(i);
            text.append("• ").append(item.name);
            if (item.cp >= 0) text.append(" — CP/PC ").append(item.cp);
            text.append('\n');
        }
        if (items.size() > limit) {
            text.append("… +").append(items.size() - limit).append(" registros");
        }
        collectionView.setText(text.toString().trim());
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(7);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
