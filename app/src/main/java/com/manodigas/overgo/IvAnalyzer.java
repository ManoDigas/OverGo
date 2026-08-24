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

        int minY = (int) (height * 0.34f);
        int maxY = (int) (height * 0.94f);
        int minX = (int) (width * 0.02f);
        int maxX = (int) (width * 0.86f);
        int minRun = Math.max(26, (int) (width * 0.055f));

        List<RowRun> runs = new ArrayList<>();
        for (int y = minY; y < maxY; y += 2) {
            RowRun best = longestIvSpan(bitmap, y, minX, maxX, width);
            if (best != null && best.length() >= minRun) runs.add(best);
        }
        if (runs.size() < 6) return null;

        List<BarBand> bands = clusterRuns(runs, width);
        if (bands.size() < 3) return null;

        bands.sort(Comparator.comparingInt(b -> b.centerY));
        List<BarBand> chosen = chooseThreeBars(bands, height);
        if (chosen == null) return null;

        List<BarProfile> profiles = new ArrayList<>();
        for (BarBand band : chosen) {
            BarProfile profile = profile(bitmap, band.centerY, minX, maxX, width);
            if (profile == null) return null;
            profiles.add(profile);
        }

        float trackWidth = estimateTrackWidthFromTicks(profiles, width);
        float confidence;

        if (trackWidth > 0f) {
            confidence = geometryConfidence(profiles, trackWidth, width);
        } else {
            float[] spans = new float[]{
                    profiles.get(0).span(),
                    profiles.get(1).span(),
                    profiles.get(2).span()
            };
            TrackSolution fallback = solveTrackWidth(spans, width);
            if (fallback == null) return null;
            trackWidth = fallback.trackWidth;
            confidence = fallback.confidence * 0.84f;
        }

        if (trackWidth < width * 0.16f || trackWidth > width * 0.55f) return null;

        int attack = valueFromProfile(profiles.get(0), trackWidth);
        int defense = valueFromProfile(profiles.get(1), trackWidth);
        int hp = valueFromProfile(profiles.get(2), trackWidth);

        // A 15/15/15 appraisal is a special case that previously failed because
        // the two divider ticks split each colored bar into three independent runs.
        // If all three bars show all three segments and reach the inferred end cap,
        // treat them explicitly as full bars.
        boolean allThreeSegmented = true;
        boolean allReachEnd = true;
        for (BarProfile p : profiles) {
            allThreeSegmented &= p.clusters.size() >= 3;
            allReachEnd &= p.span() >= trackWidth * 0.965f;
        }
        if (allThreeSegmented && allReachEnd) {
            attack = 15;
            defense = 15;
            hp = 15;
            confidence = Math.max(confidence, 0.94f);
        }

        if (attack == 0 && defense == 0 && hp == 0) return null;
        if (confidence < 0.42f) return null;
        return new IvResult(attack, defense, hp, confidence);
    }

    private static int valueFromProfile(BarProfile profile, float trackWidth) {
        float raw = profile.span() / trackWidth * 15f;
        return clampIv(Math.round(raw));
    }

    private static RowRun longestIvSpan(Bitmap bitmap, int y, int minX, int maxX, int width) {
        int bestStart = -1;
        int bestEnd = -1;
        int start = -1;
        int lastColorX = -1;
        int gap = 0;
        int maxGap = Math.max(10, (int) (width * 0.026f));

        for (int x = minX; x < maxX; x += 2) {
            if (isIvFill(bitmap.getPixel(x, y))) {
                if (start < 0) start = x;
                lastColorX = x;
                gap = 0;
            } else if (start >= 0) {
                gap += 2;
                if (gap > maxGap) {
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

    private static BarProfile profile(Bitmap bitmap, int centerY, int minX, int maxX, int width) {
        int yRadius = Math.max(2, (int) (bitmap.getHeight() * 0.004f));
        List<BarProfile> candidates = new ArrayList<>();
        for (int y = Math.max(0, centerY - yRadius); y <= Math.min(bitmap.getHeight() - 1, centerY + yRadius); y++) {
            BarProfile p = profileAtRow(bitmap, y, minX, maxX, width);
            if (p != null) candidates.add(p);
        }
        if (candidates.isEmpty()) return null;
        candidates.sort((a, b) -> Integer.compare(b.span(), a.span()));
        return candidates.get(candidates.size() / 3);
    }

    private static BarProfile profileAtRow(Bitmap bitmap, int y, int minX, int maxX, int width) {
        int smallGap = Math.max(2, (int) (width * 0.0035f));
        int minCluster = Math.max(5, (int) (width * 0.008f));
        List<ColorCluster> clusters = new ArrayList<>();

        int start = -1;
        int last = -1;
        int gap = 0;
        for (int x = minX; x < maxX; x++) {
            if (isIvFill(bitmap.getPixel(x, y))) {
                if (start < 0) start = x;
                last = x;
                gap = 0;
            } else if (start >= 0) {
                gap++;
                if (gap > smallGap) {
                    if (last - start + 1 >= minCluster) clusters.add(new ColorCluster(start, last));
                    start = -1;
                    last = -1;
                    gap = 0;
                }
            }
        }
        if (start >= 0 && last - start + 1 >= minCluster) clusters.add(new ColorCluster(start, last));
        if (clusters.isEmpty()) return null;

        // Keep only the group belonging to the appraisal bar. Divider gaps are
        // small; unrelated orange/red UI elements are normally much farther away.
        List<ColorCluster> group = new ArrayList<>();
        group.add(clusters.get(0));
        int maxDividerGap = Math.max(14, (int) (width * 0.04f));
        for (int i = 1; i < clusters.size(); i++) {
            ColorCluster previous = group.get(group.size() - 1);
            ColorCluster next = clusters.get(i);
            int between = next.start - previous.end;
            if (between <= maxDividerGap) group.add(next);
            else if (group.size() < 2) {
                group.clear();
                group.add(next);
            } else {
                break;
            }
        }
        if (group.isEmpty()) return null;
        return new BarProfile(group);
    }

    private static float estimateTrackWidthFromTicks(List<BarProfile> profiles, int width) {
        List<Float> candidates = new ArrayList<>();
        for (BarProfile profile : profiles) {
            if (profile.clusters.size() < 2) continue;
            int max = Math.min(3, profile.clusters.size());
            for (int i = 0; i < max - 1; i++) {
                float pitch = profile.clusters.get(i + 1).start - profile.clusters.get(i).start;
                float candidate = pitch * 3f;
                if (candidate >= width * 0.17f && candidate <= width * 0.55f) {
                    candidates.add(candidate);
                }
            }
        }
        if (candidates.isEmpty()) return -1f;
        Collections.sort(candidates);
        float median = candidates.get(candidates.size() / 2);

        // Reject wildly inconsistent tick measurements.
        float error = 0f;
        for (float c : candidates) error += Math.abs(c - median) / Math.max(1f, median);
        error /= candidates.size();
        return error <= 0.18f ? median : -1f;
    }

    private static float geometryConfidence(List<BarProfile> profiles, float trackWidth, int width) {
        float startMean = 0f;
        for (BarProfile p : profiles) startMean += p.start();
        startMean /= profiles.size();

        float startError = 0f;
        for (BarProfile p : profiles) startError += Math.abs(p.start() - startMean) / Math.max(1f, width);
        startError /= profiles.size();

        float tickBonus = 0f;
        for (BarProfile p : profiles) {
            if (p.clusters.size() >= 2) tickBonus += 0.08f;
            if (p.clusters.size() >= 3) tickBonus += 0.05f;
        }
        tickBonus = Math.min(0.22f, tickBonus);

        float trackPrior = Math.abs(trackWidth / width - 0.30f);
        float confidence = 0.82f - Math.min(0.30f, startError * 5f) - Math.min(0.18f, trackPrior * 0.7f) + tickBonus;
        return Math.max(0f, Math.min(1f, confidence));
    }

    private static boolean isIvFill(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int saturation = max - min;

        boolean warm = r >= 155
                && r > g + 24
                && r > b + 30
                && g >= 35 && g <= 195
                && b <= 155
                && saturation >= 55;
        boolean brightOrange = r >= 195 && g >= 70 && g <= 205 && b <= 145;
        return warm || brightOrange;
    }

    private static List<BarBand> clusterRuns(List<RowRun> rows, int width) {
        List<BarBand> bands = new ArrayList<>();
        for (RowRun row : rows) {
            BarBand target = null;
            for (BarBand band : bands) {
                if (Math.abs(row.y - band.lastY) <= 7
                        && Math.abs(row.start - band.medianStart()) < width * 0.09f) {
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
                    if (d1 < height * 0.024f || d2 < height * 0.024f) continue;
                    if (d1 > height * 0.19f || d2 > height * 0.19f) continue;
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
        return bestScore <= 0.72f ? best : null;
    }

    private static TrackSolution solveTrackWidth(float[] spans, int width) {
        float maxLen = Math.max(spans[0], Math.max(spans[1], spans[2]));
        if (maxLen <= 0f) return null;
        TrackSolution best = null;
        float secondError = Float.MAX_VALUE;

        for (int maxIv = 5; maxIv <= 15; maxIv++) {
            float track = maxLen * 15f / maxIv;
            if (track < width * 0.16f || track > width * 0.55f) continue;
            float error = 0f;
            for (float len : spans) {
                float raw = len / track * 15f;
                float rounded = Math.round(raw);
                if (rounded < 0 || rounded > 15) error += 3f;
                else error += Math.abs(raw - rounded);
            }
            float prior = Math.abs((track / width) - 0.30f) * 0.42f;
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
        confidence = Math.min(1f, confidence * 0.80f + Math.min(0.18f, separation * 0.20f));
        return new TrackSolution(best.trackWidth, best.error, confidence);
    }

    private static int clampIv(int value) {
        return Math.max(0, Math.min(15, value));
    }

    private static final class ColorCluster {
        final int start;
        final int end;
        ColorCluster(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class BarProfile {
        final List<ColorCluster> clusters;
        BarProfile(List<ColorCluster> clusters) {
            this.clusters = clusters;
        }
        int start() { return clusters.get(0).start; }
        int end() { return clusters.get(clusters.size() - 1).end; }
        int span() { return Math.max(0, end() - start() + 1); }
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
