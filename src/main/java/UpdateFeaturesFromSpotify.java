import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class UpdateFeaturesFromSpotify {

    private static final String SPOTIFY_CLIENT_ID = "0827c3e67aed4c53ad344f32332b50a3";
    private static final String SPOTIFY_CLIENT_SECRET = "30c6fb3580b34737b68474f14debd618";

    private static final String DB_URL =
            "jdbc:mysql://127.0.0.1:3306/spotifydata?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "wasd098lop.s.";

    private static final String ARTIST_ID = "4Gso3d4CscCijv0lmajZWs";
    private static final String MAIN_ARTIST_NAME = "Don Toliver";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String token = getToken();

        List<String> albumIds = fetchAllAlbumIds(token);
        System.out.println("Albums/singles: " + albumIds.size());

        int updated = 0;

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tracks SET features = ? WHERE track_id = ?")) {

                for (String albumId : albumIds) {
                    List<JsonNode> tracks = fetchAllTracksForAlbum(token, albumId);

                    for (JsonNode t : tracks) {
                        String trackId = text(t, "id");
                        if (trackId == null) continue;

                        String features = buildFeatures(t.get("artists"));

                        ps.setString(1, features);   // can be null/empty
                        ps.setString(2, trackId);
                        ps.addBatch();
                        updated++;

                        if (updated % 500 == 0) {
                            ps.executeBatch();
                            conn.commit();
                            System.out.println("Updated features rows: " + updated);
                        }
                    }
                }

                ps.executeBatch();
                conn.commit();
            }
        }

        System.out.println("Done. Updated features for " + updated + " tracks.");
    }

    // ---------- Spotify ----------
    private static String getToken() throws Exception {
        String basic = Base64.getEncoder().encodeToString(
                (SPOTIFY_CLIENT_ID + ":" + SPOTIFY_CLIENT_SECRET).getBytes(StandardCharsets.UTF_8)
        );

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://accounts.spotify.com/api/token"))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();

        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new RuntimeException(res.body());

        return MAPPER.readTree(res.body()).get("access_token").asText();
    }

    private static JsonNode spotifyGet(String token, String url) throws Exception {
        while (true) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 200) return MAPPER.readTree(res.body());

            if (res.statusCode() == 429) {
                int wait = Integer.parseInt(res.headers().firstValue("Retry-After").orElse("2"));
                System.out.println("Rate limited. Waiting " + wait + " seconds...");
                Thread.sleep(wait * 1000L);
                continue;
            }

            throw new RuntimeException("GET failed " + res.statusCode() + " url=" + url + " body=" + res.body());
        }
    }

    private static List<String> fetchAllAlbumIds(String token) throws Exception {
        int limit = 10; // Spotify change
        int offset = 0;
        Set<String> ids = new LinkedHashSet<>();

        while (true) {
            String url = "https://api.spotify.com/v1/artists/" + ARTIST_ID +
                    "/albums?include_groups=album,single&limit=" + limit + "&offset=" + offset;

            JsonNode json = spotifyGet(token, url);
            JsonNode items = json.get("items");
            if (items == null || !items.isArray() || items.size() == 0) break;

            for (JsonNode it : items) ids.add(it.get("id").asText());

            offset += limit;
            int total = json.has("total") ? json.get("total").asInt() : 0;
            if (total > 0 && offset >= total) break;
        }

        return new ArrayList<>(ids);
    }

    private static List<JsonNode> fetchAllTracksForAlbum(String token, String albumId) throws Exception {
        int limit = 50;
        int offset = 0;
        List<JsonNode> out = new ArrayList<>();

        while (true) {
            String url = "https://api.spotify.com/v1/albums/" + albumId + "/tracks?limit=" + limit + "&offset=" + offset;

            JsonNode json = spotifyGet(token, url);
            JsonNode items = json.get("items");
            if (items == null || !items.isArray() || items.size() == 0) break;

            for (JsonNode t : items) out.add(t);

            offset += limit;
            int total = json.has("total") ? json.get("total").asInt() : 0;
            if (total > 0 && offset >= total) break;
        }

        return out;
    }

    // ---------- Feature building ----------
    private static String buildFeatures(JsonNode artistsArray) {
        if (artistsArray == null || !artistsArray.isArray()) return null;

        List<String> names = new ArrayList<>();
        for (JsonNode a : artistsArray) {
            String n = text(a, "name");
            if (n == null) continue;
            // remove the main artist (Don Toliver) from features list
            if (n.equalsIgnoreCase(MAIN_ARTIST_NAME)) continue;
            names.add(n.trim());
        }

        // optional: de-dupe while keeping order
        List<String> deduped = names.stream()
                .filter(s -> !s.isBlank())
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new),
                        ArrayList::new
                ));

        if (deduped.isEmpty()) return ""; // no features
        return String.join(", ", deduped);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}