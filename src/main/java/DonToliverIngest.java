import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

public class DonToliverIngest {

    // ====== CONFIG ======
    private static final String SPOTIFY_CLIENT_ID = "0827c3e67aed4c53ad344f32332b50a3";
    private static final String SPOTIFY_CLIENT_SECRET = "30c6fb3580b34737b68474f14debd618";

    // Your MySQL DB (adjust user/pass)
    private static final String DB_URL =
            "jdbc:mysql://127.0.0.1:3306/spotifydata?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "wasd098lop.s.";

    // Don Toliver artist id
    private static final String ARTIST_ID = "4Gso3d4CscCijv0lmajZWs";
    private static final String ARTIST_NAME = "Don Toliver";

    // ====== RUNTIME ======
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String token = getClientCredentialsToken(SPOTIFY_CLIENT_ID, SPOTIFY_CLIENT_SECRET);

        // 1) get album ids (albums + singles)
        List<AlbumRef> albums = fetchAllAlbumsForArtist(token, ARTIST_ID);

        System.out.println("Found releases (album/single): " + albums.size());

        // 2) gather track ids from all albums
        Set<String> trackIds = new LinkedHashSet<>();
        for (AlbumRef a : albums) {
            List<String> ids = fetchAllTrackIdsForAlbum(token, a.albumId);
            trackIds.addAll(ids);
        }

        System.out.println("Unique track IDs gathered: " + trackIds.size());

        // 3) batch fetch track details
        List<JsonNode> tracks = fetchTracksIndividually(token, trackIds);

        // 4) upsert into MySQL
        upsertTracks(tracks);

        System.out.println("Done. Inserted/updated " + tracks.size() + " tracks into MySQL.");
    }

    // ---------------- Spotify Auth ----------------
    private static String getClientCredentialsToken(String clientId, String clientSecret) throws Exception {
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8)
        );

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://accounts.spotify.com/api/token"))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();

        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new RuntimeException("Token request failed: " + res.statusCode() + " " + res.body());
        }
        JsonNode json = MAPPER.readTree(res.body());
        return json.get("access_token").asText();
    }

    // ---------------- Spotify API helpers ----------------
    private static JsonNode spotifyGet(String token, String url) throws Exception {

        while (true) {

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 200) {
                return MAPPER.readTree(res.body());
            }

            if (res.statusCode() == 429) {
                String retryAfter = res.headers()
                        .firstValue("Retry-After")
                        .orElse("2");

                int waitSeconds = Integer.parseInt(retryAfter);
                System.out.println("Rate limited. Waiting " + waitSeconds + " seconds...");
                Thread.sleep(waitSeconds * 1000L);
                continue; // retry
            }

            throw new RuntimeException("GET failed " + res.statusCode() + " for " + url + " body=" + res.body());
        }
    }

    private static List<AlbumRef> fetchAllAlbumsForArtist(String token, String artistId) throws Exception {
        int limit = 10;               // max allowed now
        int offset = 0;

        List<AlbumRef> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        while (true) {
            String url = "https://api.spotify.com/v1/artists/" + artistId
                    + "/albums?include_groups=album,single"
                    + "&limit=" + limit
                    + "&offset=" + offset;

            JsonNode json = spotifyGet(token, url);
            JsonNode items = json.get("items");
            if (items == null || !items.isArray() || items.size() == 0) break;

            for (JsonNode it : items) {
                String id = it.get("id").asText();
                if (seen.add(id)) out.add(new AlbumRef(id)); // de-dupe
            }

            // move to next page
            offset += limit;

            // stop once we've fetched everything (if total is present)
            JsonNode totalNode = json.get("total");
            if (totalNode != null && !totalNode.isNull() && offset >= totalNode.asInt()) break;
        }

        return out;
    }

    private static List<String> fetchAllTrackIdsForAlbum(String token, String albumId) throws Exception {
        String url = "https://api.spotify.com/v1/albums/" + albumId + "/tracks?limit=50";

        List<String> ids = new ArrayList<>();
        String next = url;

        while (next != null && !next.equals("null")) {
            JsonNode json = spotifyGet(token, next);
            JsonNode items = json.get("items");
            if (items != null && items.isArray()) {
                for (JsonNode it : items) {
                    // This endpoint returns simplified track objects, but they still have "id"
                    JsonNode idNode = it.get("id");
                    if (idNode != null && !idNode.isNull()) {
                        ids.add(idNode.asText());
                    }
                }
            }
            JsonNode nextNode = json.get("next");
            next = (nextNode == null || nextNode.isNull()) ? null : nextNode.asText();
        }

        return ids;
    }

    private static List<JsonNode> fetchTracksIndividually(String token, Set<String> trackIds) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        int i = 0;

        for (String id : trackIds) {
            String url = "https://api.spotify.com/v1/tracks/" + id;
            JsonNode track = spotifyGet(token, url);
            out.add(track);

            i++;
            if (i % 25 == 0) {
                System.out.println("Fetched track details: " + i + "/" + trackIds.size());
            }

            // small polite delay to reduce chances of rate limiting
            Thread.sleep(150);
        }

        return out;
    }

    // ---------------- MySQL upsert ----------------
    private static void upsertTracks(List<JsonNode> tracks) throws Exception {
        String sql = """
            INSERT INTO tracks (
              track_id, name,
              artist_id, artist_name,
              album_id, album_name,
              release_date, release_year,
              track_number, duration_ms, explicit_flag,
              spotify_url,
              image_url_64, image_url_300, image_url_640
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              name=VALUES(name),
              artist_id=VALUES(artist_id),
              artist_name=VALUES(artist_name),
              album_id=VALUES(album_id),
              album_name=VALUES(album_name),
              release_date=VALUES(release_date),
              release_year=VALUES(release_year),
              track_number=VALUES(track_number),
              duration_ms=VALUES(duration_ms),
              explicit_flag=VALUES(explicit_flag),
              spotify_url=VALUES(spotify_url),
              image_url_64=VALUES(image_url_64),
              image_url_300=VALUES(image_url_300),
              image_url_640=VALUES(image_url_640)
            """;

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement ps = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);

            int count = 0;
            for (JsonNode t : tracks) {
                TrackRow row = mapTrackRow(t);

                ps.setString(1, row.trackId);
                ps.setString(2, row.name);

                ps.setString(3, row.artistId);
                ps.setString(4, row.artistName);

                ps.setString(5, row.albumId);
                ps.setString(6, row.albumName);

                ps.setString(7, row.releaseDate);
                if (row.releaseYear == null) ps.setNull(8, Types.INTEGER);
                else ps.setInt(8, row.releaseYear);

                if (row.trackNumber == null) ps.setNull(9, Types.INTEGER);
                else ps.setInt(9, row.trackNumber);

                if (row.durationMs == null) ps.setNull(10, Types.INTEGER);
                else ps.setInt(10, row.durationMs);


                if (row.explicitFlag == null) ps.setNull(12, Types.BOOLEAN);
                else ps.setBoolean(11, row.explicitFlag);

                ps.setString(12, row.spotifyUrl);

                ps.setString(13, row.image64);
                ps.setString(14, row.image300);
                ps.setString(15, row.image640);

                ps.addBatch();
                count++;

                if (count % 200 == 0) {
                    ps.executeBatch();
                    conn.commit();
                    System.out.println("Upserted: " + count);
                }
            }

            ps.executeBatch();
            conn.commit();
            System.out.println("Upsert complete: " + count);
        }
    }

    private static TrackRow mapTrackRow(JsonNode t) {
        TrackRow r = new TrackRow();

        r.trackId = text(t, "id");
        r.name = text(t, "name");

        // artists[0] is typically main artist; for album tracks, Don Toliver may be first
        // For your game, it's fine to store Don Toliver as the "artist" consistently.
        r.artistId = ARTIST_ID;
        r.artistName = ARTIST_NAME;

        JsonNode album = t.get("album");
        r.albumId = (album != null) ? text(album, "id") : null;
        r.albumName = (album != null) ? text(album, "name") : null;

        r.releaseDate = (album != null) ? text(album, "release_date") : null;
        r.releaseYear = parseReleaseYear(r.releaseDate);

        r.trackNumber = intOrNull(t, "track_number");
        r.durationMs = intOrNull(t, "duration_ms");
        JsonNode exp = t.get("explicit");
        r.explicitFlag = (exp == null || exp.isNull()) ? null : exp.asBoolean();

        // external_urls.spotify
        JsonNode ext = t.get("external_urls");
        r.spotifyUrl = (ext != null) ? text(ext, "spotify") : null;

        // album.images (array of {height,width,url})
        if (album != null && album.has("images") && album.get("images").isArray()) {
            Map<Integer, String> byHeight = new HashMap<>();
            for (JsonNode img : album.get("images")) {
                Integer h = img.has("height") && !img.get("height").isNull() ? img.get("height").asInt() : null;
                String url = text(img, "url");
                if (h != null && url != null) byHeight.put(h, url);
            }
            r.image640 = pickClosest(byHeight, 640);
            r.image300 = pickClosest(byHeight, 300);
            r.image64 = pickClosest(byHeight, 64);
        }

        return r;
    }

    private static String pickClosest(Map<Integer, String> map, int target) {
        if (map.isEmpty()) return null;
        int best = map.keySet().stream()
                .min(Comparator.comparingInt(h -> Math.abs(h - target)))
                .orElse(target);
        return map.get(best);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asInt();
    }

    private static Integer parseReleaseYear(String releaseDate) {
        if (releaseDate == null || releaseDate.length() < 4) return null;
        try {
            return Integer.parseInt(releaseDate.substring(0, 4));
        } catch (Exception e) {
            return null;
        }
    }

    // Simple record for album id
    private static class AlbumRef {
        String albumId;
        AlbumRef(String albumId) { this.albumId = albumId; }
    }

    private static class TrackRow {
        String trackId;
        String name;

        String artistId;
        String artistName;

        String albumId;
        String albumName;

        String releaseDate;
        Integer releaseYear;

        Integer trackNumber;
        Integer durationMs;
        Boolean explicitFlag;

        String spotifyUrl;

        String image64;
        String image300;
        String image640;
    }
}