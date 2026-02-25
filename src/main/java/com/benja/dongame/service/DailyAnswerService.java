package com.benja.dongame.service;

import com.benja.dongame.db.DailyAnswerDao;
import com.benja.dongame.db.TrackDao;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class DailyAnswerService {
    private static final ZoneId ZONE = ZoneId.of("Europe/London");
    private static final String SALT = "guess-don-v1"; // can be anything, keep stable

    private final TrackDao trackDao;
    private final DailyAnswerDao dailyDao;

    public DailyAnswerService(TrackDao trackDao, DailyAnswerDao dailyDao) {
        this.trackDao = trackDao;
        this.dailyDao = dailyDao;
    }

    public LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public String getOrCreateAnswerTrackId(LocalDate date) {
        return dailyDao.findAnswerTrackId(date)
                .orElseGet(() -> {
                    String picked = pickDeterministic(date);
                    dailyDao.insertAnswer(date, picked);
                    // read again (covers race conditions)
                    return dailyDao.findAnswerTrackId(date).orElse(picked);
                });
    }

    private String pickDeterministic(LocalDate date) {
        List<String> ids = trackDao.getAllTrackIdsStable();
        if (ids.isEmpty()) throw new IllegalStateException("tracks table is empty");

        int idx = positiveMod(hashToInt(date.toString() + "|" + SALT), ids.size());
        return ids.get(idx);
    }

    private static int positiveMod(int x, int m) {
        int r = x % m;
        return r < 0 ? r + m : r;
    }

    private static int hashToInt(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            // take first 4 bytes to int
            return ((d[0] & 0xff) << 24) | ((d[1] & 0xff) << 16) | ((d[2] & 0xff) << 8) | (d[3] & 0xff);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}