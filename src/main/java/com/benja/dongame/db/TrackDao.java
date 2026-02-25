package com.benja.dongame.db;

import com.benja.dongame.dto.TrackSuggestion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class TrackDao {
    private final JdbcTemplate jdbc;

    public TrackDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TrackRow> findById(String trackId) {
        var sql = """
            SELECT track_id, name, album_id, album_name, release_year, track_number, duration_ms, explicit_flag, image_url_640, features, image_url_300
        	FROM tracks
        	WHERE track_id = ?
            """;
        List<TrackRow> rows = jdbc.query(sql, (rs, n) -> {
            TrackRow t = new TrackRow();
            t.trackId = rs.getString("track_id");
            t.name = rs.getString("name");
            t.albumId = rs.getString("album_id");
            t.albumName = rs.getString("album_name");
            t.releaseYear = (Integer) rs.getObject("release_year");
            t.trackNumber = (Integer) rs.getObject("track_number");
            t.durationMs = (Integer) rs.getObject("duration_ms");
            t.explicitFlag = (Boolean) rs.getObject("explicit_flag");
            t.imageUrl640 = rs.getString("image_url_640");
            t.features = rs.getString("features");
            t.imageUrl300 = rs.getString("image_url_300");
            return t;
        }, trackId);

        return rows.stream().findFirst();
    }

    public int countTracks() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tracks", Integer.class);
        return n == null ? 0 : n;
    }

    public List<String> getAllTrackIdsStable() {
        // stable ordering so the "daily" pick is deterministic
        return jdbc.queryForList("""
            SELECT track_id
            FROM tracks
            ORDER BY release_year, album_name, track_number, name, track_id
            """, String.class);
    }

    public List<TrackSuggestion> searchByName(String q, int limit) {

        String like = "%" + q + "%";
        String starts = q + "%";

        var sql = """
        	    SELECT track_id, name, album_name, image_url_300
        	    FROM tracks
        	    WHERE name LIKE ?
        	    ORDER BY (LOWER(name) LIKE LOWER(?)) DESC,
        	             release_year DESC,
        	             name ASC
        	    LIMIT ?
        	    """;

        	return jdbc.query(sql, (rs, n) -> new TrackSuggestion(
        	        rs.getString("track_id"),
        	        rs.getString("name"),
        	        rs.getString("album_name"),
        	        rs.getString("image_url_300")
        	), like, starts, limit);
    }
}