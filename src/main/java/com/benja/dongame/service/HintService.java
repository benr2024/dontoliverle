package com.benja.dongame.service;

import com.benja.dongame.db.TrackRow;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class HintService {

    public static class FeatureHint {
        public final String code;          
        public final String overlapDisplay;  
        public FeatureHint(String code, String overlapDisplay) {
            this.code = code;
            this.overlapDisplay = overlapDisplay;
        }
    }

    public Map<String, String> makeHints(TrackRow guess, TrackRow answer) {
        Map<String, String> hints = new LinkedHashMap<>();

        hints.put("album", safeEq(guess.albumId, answer.albumId) ? "EQ" : "NO");
        hints.put("year", compareInts(guess.releaseYear, answer.releaseYear));
        hints.put("trackNumber", compareInts(guess.trackNumber, answer.trackNumber));
        hints.put("duration", compareInts(guess.durationMs, answer.durationMs));

        FeatureHint fh = compareFeatures(guess.features, answer.features);
        hints.put("features", fh.code); // <— NEW

        return hints;
    }

    public FeatureHint compareFeatures(String guessFeatures, String answerFeatures) {
        Set<String> g = normalizeFeatures(guessFeatures);
        Set<String> a = normalizeFeatures(answerFeatures);

        if (g.isEmpty() || a.isEmpty()) return new FeatureHint("NA", "");

        Set<String> overlap = new LinkedHashSet<>(g);
        overlap.retainAll(a);

        if (overlap.isEmpty()) return new FeatureHint("NO", "");

        if (overlap.size() == g.size() && overlap.size() == a.size()) {
            // exact same set
            return new FeatureHint("EQ", String.join(", ", overlap));
        }

        // partial overlap
        return new FeatureHint("PART", String.join(", ", overlap));
    }

    private static Set<String> normalizeFeatures(String raw) {
        if (raw == null) return Collections.emptySet();
        String s = raw.trim();
        if (s.isEmpty()) return Collections.emptySet();

        // comma-separated
        String[] parts = s.split(",");
        return Arrays.stream(parts)
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean isCorrect(TrackRow guess, TrackRow answer) {
        return safeEq(guess.trackId, answer.trackId);
    }

    private static String compareInts(Integer g, Integer a) {
        if (g == null || a == null) return "NA";
        if (g.intValue() == a.intValue()) return "EQ";
        return (g < a) ? "UP" : "DOWN";
    }

    private static boolean safeEq(String x, String y) {
        return x != null && x.equals(y);
    }
}