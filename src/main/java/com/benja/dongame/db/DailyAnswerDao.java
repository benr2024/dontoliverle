package com.benja.dongame.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public class DailyAnswerDao {
    private final JdbcTemplate jdbc;

    public DailyAnswerDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> findAnswerTrackId(LocalDate date) {
        var sql = "SELECT track_id FROM daily_answer WHERE game_date = ?";
        return jdbc.query(sql, (rs, n) -> rs.getString("track_id"), date)
                .stream().findFirst();
    }

    public void insertAnswer(LocalDate date, String trackId) {
        // if a row already exists, do nothing
        jdbc.update("""
            INSERT IGNORE INTO daily_answer (game_date, track_id)
            VALUES (?, ?)
            """, date, trackId);
    }
}