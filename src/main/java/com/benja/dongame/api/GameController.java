package com.benja.dongame.api;

import com.benja.dongame.db.TrackDao;
import com.benja.dongame.dto.GuessRequest;
import com.benja.dongame.dto.GuessResponse;
import com.benja.dongame.dto.TodayResponse;
import com.benja.dongame.service.DailyAnswerService;
import com.benja.dongame.service.HintService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class GameController {

    private static final int MAX_GUESSES = 8;

    private final TrackDao trackDao;
    private final DailyAnswerService dailyAnswerService;
    private final HintService hintService;

    public GameController(TrackDao trackDao, DailyAnswerService dailyAnswerService, HintService hintService) {
        this.trackDao = trackDao;
        this.dailyAnswerService = dailyAnswerService;
        this.hintService = hintService;
    }

    @GetMapping("/today")
    public TodayResponse today() {
        LocalDate date = dailyAnswerService.today();
        dailyAnswerService.getOrCreateAnswerTrackId(date);

        var zone = java.time.ZoneId.of("Europe/London");
        var now = java.time.ZonedDateTime.now(zone);
        var nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone);
        long nextResetEpochMs = nextMidnight.toInstant().toEpochMilli();

        return new TodayResponse(date, MAX_GUESSES, nextResetEpochMs);
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam("q") String q) {
        String trimmed = q == null ? "" : q.trim();
        if (trimmed.length() < 2) return ResponseEntity.ok(java.util.List.of());
        return ResponseEntity.ok(trackDao.searchByName(trimmed, 10));
    }

    @PostMapping("/guess")
    public ResponseEntity<?> guess(@RequestBody GuessRequest req) {
        if (req == null || req.trackId() == null || req.trackId().isBlank()) {
            return ResponseEntity.badRequest().body("trackId required");
        }

        var guessOpt = trackDao.findById(req.trackId().trim());
        if (guessOpt.isEmpty()) return ResponseEntity.badRequest().body("unknown trackId");

        LocalDate date = dailyAnswerService.today();
        String answerId = dailyAnswerService.getOrCreateAnswerTrackId(date);
        var answerOpt = trackDao.findById(answerId);
        if (answerOpt.isEmpty()) return ResponseEntity.internalServerError().body("answer missing from tracks table");

        var guess = guessOpt.get();
        var answer = answerOpt.get();

        boolean correct = hintService.isCorrect(guess, answer);
        var hints = hintService.makeHints(guess, answer);
        var fh = hintService.compareFeatures(guess.features, answer.features);

        var guessDto = new GuessResponse.GuessTrack(
                guess.trackId,
                guess.name,
                guess.albumName,
                guess.releaseYear,
                guess.trackNumber,
                guess.durationMs,
                guess.explicitFlag,
                guess.features,
                guess.imageUrl300
        );

        return ResponseEntity.ok(new GuessResponse(correct, guessDto, hints, fh.overlapDisplay));
    }
    
    @GetMapping("/answer")
    public ResponseEntity<?> answer() {
        LocalDate date = dailyAnswerService.today();
        String answerId = dailyAnswerService.getOrCreateAnswerTrackId(date);
        var answerOpt = trackDao.findById(answerId);
        if (answerOpt.isEmpty()) return ResponseEntity.internalServerError().body("answer missing");
        var a = answerOpt.get();
        return ResponseEntity.ok(Map.of(
                "trackId", a.trackId,
                "name", a.name,
                "albumName", a.albumName,
                "releaseYear", a.releaseYear,
                "trackNumber", a.trackNumber,
                "durationMs", a.durationMs
        ));
    }
    
    @GetMapping("/random")
    public ResponseEntity<?> random() {
        var ids = trackDao.getAllTrackIdsStable();
        if (ids.isEmpty()) return ResponseEntity.ok(Map.of());
        String id = ids.get(new java.util.Random().nextInt(ids.size()));
        return ResponseEntity.ok(Map.of("trackId", id));
    }
    
}