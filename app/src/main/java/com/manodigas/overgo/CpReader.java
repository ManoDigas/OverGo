package com.manodigas.overgo;

import com.google.mlkit.vision.text.Text;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CpReader {
    private static final Pattern CP_PATTERN = Pattern.compile(
            "(?i)(?:^|\\s)(?:C\\s*P|P\\s*C)\\s*[:.]?\\s*([0-9OIlLS\\s]{1,8})(?:\\s|$)"
    );

    private CpReader() {}

    static int extract(Text text) {
        if (text == null) return -1;

        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                int cp = extractFromString(line.getText());
                if (cp >= 10) return cp;
            }
        }
        return extractFromString(text.getText());
    }

    private static int extractFromString(String source) {
        if (source == null || source.isEmpty()) return -1;
        String prepared = " " + source.replace('\n', ' ') + " ";
        Matcher matcher = CP_PATTERN.matcher(prepared);
        while (matcher.find()) {
            String digits = normalizeDigits(matcher.group(1));
            if (digits.length() < 2 || digits.length() > 5) continue;
            try {
                int value = Integer.parseInt(digits);
                if (value >= 10 && value <= 99999) return value;
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    private static String normalizeDigits(String raw) {
        String s = raw == null ? "" : raw.toUpperCase(Locale.ROOT);
        s = s.replace("O", "0")
                .replace("I", "1")
                .replace("L", "1")
                .replace("S", "5")
                .replaceAll("\\s+", "")
                .replaceAll("[^0-9]", "");
        return s;
    }
}
