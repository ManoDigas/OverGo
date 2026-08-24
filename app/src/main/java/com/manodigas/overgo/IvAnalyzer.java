package com.manodigas.overgo;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class IvResult {
    final int attack;
    final int defense;
    final int hp;
    final int percent;
    final float confidence;

    IvResult(int attack, int defense, int hp, float confidence) {
        this.attack = attack;
        this.defense = defense;
        this.hp = hp;
        this.percent = Math.round((attack + defense + hp) * 100f / 45f);
        this.confidence = confidence;
    }
}

final class IvAnalyzer {
    private IvAnalyzer() {}

    static IvResult analyze(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return null;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width < 200 || height < 300) return null;

        int minY = (int) (height * 0.35f);
        int maxY = (int) (height * 0.92f);
        int minX = (int) (width * 0.03f);
        int maxX = (int) (width * 0.78f);
        int minRun = Math.max(24, (int) (width * 0.055f));

        List<RowRun> runs = new ArrayList<>();
        for (int y = minY; y < maxY; y += 2) {
            RowRun best = longestIvRun(bitmap, y, minX, maxX);
            if (best != null && best.length() >= minRun) runs.add(best);
        }
        if (runs.size() < 6) return null;

        List<BarBand> bands = clusterRuns(runs, width);
        if (bands.size() < 3) return null;

        bands.sort(Comparator.comparingInt(b -> b.centerY));
        List<BarBand> chosen = chooseThreeBars(bands, height);
        if (chosen == null) return null;

        float[] lengths = new float[3];
        for (int i = 0; i < 3; i++) lengths[i] = chosen.get(i).medianLength();

        TrackSolution solution = solveTrackWidth(lengths, width);
        if (solution == null || solution.confidence < 0.52f) return null;

        int attack = clampIv(Math.round(lengths[0] / solution.trackWidth * 15f));
        int defense = clampIv(Math.round(lengths[1] / solution.trackWidth * 15f));
        int hp = clampIv(Math.round(lengths[2] / solution.trackWidth * 15f));

        if (attack == 0 && defense == 0 && hp == 0) return null;
        return new IvResult(attack, defense, hp, solution.confidence);
    }

    private static RowRun longestIvRun(Bitmap bitmap, int y, int minX, int maxX) {
        int bestStart = -1;
        int bestEnd = -1;
        int start = -1;
        int lastColorX = -1;
        int gap = 0;

        for (int x = minX; x < maxX; x += 2) {
            boolean iv = isIvFill(bitmap.getPixel(x, y));
            if (iv) {
                if (start < 0) start = x;
                lastColorX = x;
                gap = 0;
            } else if (start >= 0) {
                gap += 2;
                if (gap > 8) {
                    if (lastColorX - start > bestEnd - bestStart) {
                        bestStart = start;
                        bestEnd = lastColorX;
                    }
                    start = -1;
                    lastColorX = -1;
                    gap = 0;
                }
            }
        }
        if (start >= 0 && lastColorX - start > bestEnd - bestStart) {
            bestStart = start;
            bestEnd = lastColorX;
        }
        return bestStart < 0 ? null : new RowRun(y, bestStart, bestEnd);
    }

    private static boolean isIvFill(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int saturation = max - min;
        boolean orange = r >= 185 && g >= 65 && g <= 190 && b <= 125 && r > g + 35;
        boolean redPerfect = r >= 175 && g <= 105 && b <= 105 && saturation >= 95;
        return orange || redPerfect;
    }

    private static List<BarBand> clusterRuns(List<RowRun> rows, int width) {
        List<BarBand> bands = new ArrayList<>();
        for (RowRun row : rows) {
            BarBand target = null;
            for (BarBand band : bands) {
                if (Math.abs(row.y - band.lastY) <= 6
                        && Math.abs(row.start - band.medianStart()) < width * 0.08f) {
                    target = band;
                    break;
                }
            }
            if (target == null) {
                target = new BarBand();
                bands.add(target);
            }
            target.add(row);
        }
        List<BarBand> filtered = new ArrayList<>();
        for (BarBand band : bands) {
            if (band.rows.size() >= 2 && band.medianLength() >= width * 0.055f) filtered.add(band);
        }
        return filtered;
    }

    private static List<BarBand> chooseThreeBars(List<BarBand> bands, int height) {
        List<BarBand> best = null;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < bands.size(); i++) {
            for (int j = i + 1; j < bands.size(); j++) {
                for (int k = j + 1; k < bands.size(); k++) {
                    BarBand a = bands.get(i);
                    BarBand b = bands.get(j);
                    BarBand c = bands.get(k);
                    int d1 = b.centerY - a.centerY;
                    int d2 = c.centerY - b.centerY;
                    if (d1 < height * 0.025f || d2 < height * 0.025f) continue;
                    if (d1 > height * 0.18f || d2 > height * 0.18f) continue;
                    float spacingError = Math.abs(d1 - d2) / (float) Math.max(d1, d2);
                    float startMean = (a.medianStart() + b.medianStart() + c.medianStart()) / 3f;
                    float startError = (Math.abs(a.medianStart() - startMean)
                            + Math.abs(b.medianStart() - startMean)
                            + Math.abs(c.medianStart() - startMean)) / Math.max(1f, startMean);
                    float score = spacingError * 2f + startError;
                    if (score < bestScore) {
                        bestScore = score;
                        best = new ArrayList<>();
                        best.add(a);
                        best.add(b);
                        best.add(c);
                    }
                }
            }
        }
        return bestScore <= 0.65f ? best : null;
    }

    private static TrackSolution solveTrackWidth(float[] lengths, int width) {
        float maxLen = Math.max(lengths[0], Math.max(lengths[1], lengths[2]));
        if (maxLen <= 0f) return null;
        TrackSolution best = null;
        float secondError = Float.MAX_VALUE;

        for (int maxIv = 5; maxIv <= 15; maxIv++) {
            float track = maxLen * 15f / maxIv;
            if (track < width * 0.14f || track > width * 0.52f) continue;
            float error = 0f;
            for (float len : lengths) {
                float raw = len / track * 15f;
                float rounded = Math.round(raw);
                if (rounded < 0 || rounded > 15) {
                    error += 3f;
                } else {
                    error += Math.abs(raw - rounded);
                }
            }
            float prior = Math.abs((track / width) - 0.30f) * 0.45f;
            error += prior;
            if (best == null || error < best.error) {
                if (best != null) secondError = best.error;
                best = new TrackSolution(track, error, 0f);
            } else if (error < secondError) {
                secondError = error;
            }
        }
        if (best == null) return null;
        float separation = secondError == Float.MAX_VALUE ? 0.5f : Math.max(0f, secondError - best.error);
        float confidence = 1f - Math.min(1f, best.error / 1.35f);
        confidence = Math.min(1f, confidence * 0.82f + Math.min(0.18f, separation * 0.20f));
        return new TrackSolution(best.trackWidth, best.error, confidence);
    }

    private static int clampIv(int value) {
        return Math.max(0, Math.min(15, value));
    }

    private static final class RowRun {
        final int y;
        final int start;
        final int end;
        RowRun(int y, int start, int end) {
            this.y = y;
            this.start = start;
            this.end = end;
        }
        int length() { return Math.max(0, end - start); }
    }

    private static final class BarBand {
        final List<RowRun> rows = new ArrayList<>();
        int lastY;
        int centerY;
        void add(RowRun row) {
            rows.add(row);
            lastY = row.y;
            int total = 0;
            for (RowRun r : rows) total += r.y;
            centerY = total / rows.size();
        }
        int medianStart() {
            List<Integer> values = new ArrayList<>();
            for (RowRun row : rows) values.add(row.start);
            Collections.sort(values);
            return values.get(values.size() / 2);
        }
        float medianLength() {
            List<Integer> values = new ArrayList<>();
            for (RowRun row : rows) values.add(row.length());
            Collections.sort(values);
            return values.get(values.size() / 2);
        }
    }

    private static final class TrackSolution {
        final float trackWidth;
        final float error;
        final float confidence;
        TrackSolution(float trackWidth, float error, float confidence) {
            this.trackWidth = trackWidth;
            this.error = error;
            this.confidence = confidence;
        }
    }
}
