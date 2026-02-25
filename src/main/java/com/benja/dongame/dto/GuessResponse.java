package com.benja.dongame.dto;

import java.util.Map;

public record GuessResponse(
        boolean correct,
        GuessTrack guess,
        Map<String, String> hints,
        String featuresOverlap
) {
    public record GuessTrack(
            String trackId,
            String name,
            String albumName,
            Integer releaseYear,
            Integer trackNumber,
            Integer durationMs,
            Boolean explicitFlag,
            String features,
            String imageUrl300
    ) {}
}