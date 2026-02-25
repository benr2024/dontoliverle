package com.benja.dongame.dto;

import java.time.LocalDate;

public record TodayResponse(
        LocalDate date,
        int maxGuesses,
        long nextResetEpochMs
) {}