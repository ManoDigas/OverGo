package com.manodigas.overgo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {
    public static final String ACTION_START = "com.manodigas.overgo.action.START";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "overgo_capture";
    private static final int NOTIFICATION_ID = 42;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object bitmapLock = new Object();

    private HandlerThread captureThread;
    private Handler captureHandler;
    private WindowManager windowManager;
    private TextRecognizer recognizer;

    private MediaProjection mediaProjection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Bitmap latestBitmap;

    private LinearLayout overlayView;
    private TextView resultView;

    @Override
    public void onCreate() {
        super.onCreate();
        captureThread = new HandlerThread("OverGoCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            startProjection(intent);
        }
        return START_NOT_STICKY;
    }

    private void startProjection(Intent intent) {
        if (mediaProjection != null) return;

        createNotificationChannel();
        startProjectionForeground();

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Integer.MIN_VALUE);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            //noinspection deprecation
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }

        if (resultCode == Integer.MIN_VALUE || resultData == null) {
            stopSelf();
            return;
        }

        MediaProjectionManager projectionManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MediaProjection projection = projectionManager.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            stopSelf();
            return;
        }
        mediaProjection = projection;

        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                mainHandler.post(() -> stopSelf());
            }
        };
        projection.registerCallback(projectionCallback, mainHandler);

        int width;
        int height;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
            width = Math.max(1, bounds.width());
            height = Math.max(1, bounds.height());
        } else {
            DisplayMetrics metrics = new DisplayMetrics();
            //noinspection deprecation
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            width = Math.max(1, metrics.widthPixels);
            height = Math.max(1, metrics.heightPixels);
        }

        int densityDpi = getResources().getConfiguration().densityDpi;
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();
            if (image == null) return;
            try {
                Bitmap bitmap = imageToBitmap(image);
                synchronized (bitmapLock) {
                    if (latestBitmap != null && !latestBitmap.isRecycled()) latestBitmap.recycle();
                    latestBitmap = bitmap;
                }
            } finally {
                image.close();
            }
        }, captureHandler);

        virtualDisplay = projection.createVirtualDisplay(
                "OverGoProjection",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler
        );

        showOverlay();
    }

    private void startProjectionForeground() {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("OverGo ativo")
                .setContentText("Toque na bolha SCAN para registrar o Pokémon visível.")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Scanner do OverGo",
                NotificationManager.IMPORTANCE_LOW
        );
        manager.createNotificationChannel(channel);
    }

    private void showOverlay() {
        if (!Settings.canDrawOverlays(this) || overlayView != null) {
            if (!Settings.canDrawOverlays(this)) stopSelf();
            return;
        }

        TextView scanButton = new TextView(this);
        scanButton.setText("SCAN");
        scanButton.setTextSize(15f);
        scanButton.setTextColor(Color.WHITE);
        scanButton.setGravity(Gravity.CENTER);
        scanButton.setPadding(dp(16), dp(12), dp(16), dp(12));
        scanButton.setBackgroundColor(Color.rgb(34, 108, 190));

        resultView = new TextView(this);
        resultView.setText("Abra a ficha de um Pokémon e toque em SCAN.");
        resultView.setTextSize(12f);
        resultView.setTextColor(Color.WHITE);
        resultView.setPadding(dp(10), dp(8), dp(10), dp(8));
        resultView.setBackgroundColor(Color.argb(225, 20, 20, 20));
        resultView.setMaxWidth(dp(280));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(scanButton);
        container.addView(resultView);
        overlayView = container;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(12);
        params.y = dp(170);

        final int[] startX = {0};
        final int[] startY = {0};
        final float[] touchX = {0f};
        final float[] touchY = {0f};
        final boolean[] moved = {false};

        scanButton.setOnTouchListener((View v, MotionEvent event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX[0] = params.x;
                    startY[0] = params.y;
                    touchX[0] = event.getRawX();
                    touchY[0] = event.getRawY();
                    moved[0] = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - touchX[0]);
                    int dy = (int) (event.getRawY() - touchY[0]);
                    if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved[0] = true;
                    params.x = startX[0] + dx;
                    params.y = startY[0] + dy;
                    try {
                        windowManager.updateViewLayout(container, params);
                    } catch (Exception ignored) {
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!moved[0]) analyzeLatestFrame();
                    return true;

                default:
                    return false;
            }
        });

        try {
            windowManager.addView(container, params);
        } catch (Exception error) {
            stopSelf();
        }
    }

    private void analyzeLatestFrame() {
        Bitmap frame;
        synchronized (bitmapLock) {
            frame = latestBitmap == null || latestBitmap.isRecycled()
                    ? null
                    : latestBitmap.copy(Bitmap.Config.ARGB_8888, false);
        }

        if (frame == null) {
            if (resultView != null) resultView.setText("Ainda não recebi a imagem. Tente novamente.");
            return;
        }

        if (resultView != null) resultView.setText("Analisando...");
        InputImage input = InputImage.fromBitmap(frame, 0);
        recognizer.process(input)
                .addOnSuccessListener(text -> {
                    PokemonRecord record = ScreenPokemonParser.parse(text.getText());
                    CollectionStore.add(getApplicationContext(), record);
                    if (resultView != null) resultView.setText(ScreenPokemonParser.summary(record));
                })
                .addOnFailureListener(error -> {
                    if (resultView != null) {
                        resultView.setText("Não consegui ler esta tela: " + error.getClass().getSimpleName());
                    }
                })
                .addOnCompleteListener(task -> {
                    if (!frame.isRecycled()) frame.recycle();
                });
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        buffer.rewind();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        int paddedWidth = image.getWidth() + rowPadding / pixelStride;

        Bitmap padded = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
        if (cropped != padded && !padded.isRecycled()) padded.recycle();
        return cropped;
    }

    @Override
    public void onDestroy() {
        if (overlayView != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
            }
            overlayView = null;
            resultView = null;
        }

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (mediaProjection != null && projectionCallback != null) {
            try {
                mediaProjection.unregisterCallback(projectionCallback);
            } catch (Exception ignored) {
            }
        }
        projectionCallback = null;

        if (mediaProjection != null) {
            try {
                mediaProjection.stop();
            } catch (Exception ignored) {
            }
            mediaProjection = null;
        }

        synchronized (bitmapLock) {
            if (latestBitmap != null && !latestBitmap.isRecycled()) latestBitmap.recycle();
            latestBitmap = null;
        }

        if (recognizer != null) recognizer.close();
        if (captureThread != null) captureThread.quitSafely();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
