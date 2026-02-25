package com.benja.dongame.db;

public class TrackRow {
    public String trackId;
    public String name;

    public String albumId;
    public String albumName;

    public Integer releaseYear;
    public Integer trackNumber;
    public Integer durationMs;
    public Boolean explicitFlag;

    public String imageUrl640; // optional, for later (cover reveal)
    public String features; // comma-separated e.g. "Future, Travis Scott"
    public String imageUrl300;
}