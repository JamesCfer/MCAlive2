package dev.celestia.mcalive2.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.celestia.mcalive2.MCAlive2Plugin;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * Periodically checks the GitHub Releases feed and downloads a newer plugin jar into
 * Bukkit's plugins/update/ folder, which Paper applies automatically on the next
 * server start (stages on restart N, applies on restart N+1). Runs entirely off the
 * main thread, except the empty-server watch (which must touch Bukkit's player list
 * and, if configured, trigger a shutdown) which runs on the main thread.
 * Ported from the MinecraftAlive prototype's proven update pattern.
 */
public final class Updater {

    /** The release asset we look for; must match this exactly. */
    private static final String ASSET_NAME = "MCAlive2.jar";

    /** Marker file written next to the staged jar recording which version it is. */
    private static final String MARKER_NAME = ASSET_NAME + ".version";

    private final MCAlive2Plugin plugin;

    /** Version currently staged in plugins/update/, or null if none. Main-thread and
     *  async-task writes only happen one at a time (single repeating task), but reads
     *  from the empty-watch (main thread) race benignly - a stale read just delays a
     *  shutdown decision by one tick, never causes a bad one. */
    private volatile String stagedVersion;

    /** Main-thread only: wall-clock millis when the server was first observed empty
     *  with an update staged, or null if not currently in such a streak. */
    private Long emptySinceMillis;

    public Updater(MCAlive2Plugin plugin) {
        this.plugin = plugin;
    }

    /** Call from onEnable; schedules the periodic async check and the empty-server watch. */
    public void checkAsync() {
        if (!plugin.getConfig().getBoolean("auto-update.enabled", true)) return;
        String repo = plugin.getConfig().getString("auto-update.github-repo", "JamesCfer/MCAlive2");
        if (repo == null || repo.isBlank() || !repo.contains("/")) {
            plugin.getLogger().warning("auto-update.github-repo is not set; skipping update check");
            return;
        }

        stagedVersion = readStagedVersion();

        int checkMinutes = plugin.getConfig().getInt("auto-update.check-minutes", 2);
        if (checkMinutes > 0) {
            long periodTicks = checkMinutes * 60L * 20L;
            plugin.getServer().getScheduler()
                    .runTaskTimerAsynchronously(plugin, () -> check(repo), 0L, periodTicks);
        } else {
            // 0 = check once, at startup only
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> check(repo));
        }

        // watch for "staged update + empty server" on the main thread, every minute
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickEmptyWatch, 1200L, 1200L);
    }

    /** Main-thread tick: if auto-update.apply-when-empty is set, an update is staged,
     *  and the server has had zero players for auto-update.empty-minutes, shut the
     *  server down so an external restart loop (e.g. scripts/run-server.cmd) can
     *  relaunch it and apply the staged jar. */
    private void tickEmptyWatch() {
        if (!plugin.getConfig().getBoolean("auto-update.apply-when-empty", false)
                || stagedVersion == null
                || !plugin.getServer().getOnlinePlayers().isEmpty()) {
            emptySinceMillis = null;
            return;
        }

        long now = System.currentTimeMillis();
        if (emptySinceMillis == null) {
            emptySinceMillis = now;
            return;
        }

        int emptyMinutes = plugin.getConfig().getInt("auto-update.empty-minutes", 10);
        long elapsedMinutes = (now - emptySinceMillis) / 60_000L;
        if (elapsedMinutes >= emptyMinutes) {
            plugin.getLogger().warning("[MCAlive2] Update v" + stagedVersion + " is staged and the server has "
                    + "been empty for " + emptyMinutes + "+ minutes. Shutting down now (auto-update.apply-when-empty) "
                    + "so a restart loop can relaunch and apply it.");
            plugin.getServer().shutdown();
        }
    }

    /** Reads back the version marker written next to a previously staged jar, if any
     *  (both the jar and the marker must exist). Used so a fresh JVM run - or a later
     *  periodic check within the same run - never re-downloads a version already
     *  staged on disk. */
    private String readStagedVersion() {
        try {
            File updateDir = new File(plugin.getDataFolder().getParentFile(), plugin.getServer().getUpdateFolder());
            File jar = new File(updateDir, ASSET_NAME);
            File marker = new File(updateDir, MARKER_NAME);
            if (jar.isFile() && marker.isFile()) {
                String v = Files.readString(marker.toPath()).trim();
                if (!v.isEmpty()) return v;
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Could not read staged update marker: " + e.getMessage());
        }
        return null;
    }

    private void check(String repo) {
        try (HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + repo + "/releases/latest"))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "MCAlive2-Updater")
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                plugin.getLogger().info("Update check: GitHub returned " + resp.statusCode() + " (no release yet?)");
                return;
            }
            JsonObject release = JsonParser.parseString(resp.body()).getAsJsonObject();
            String tag = release.get("tag_name").getAsString();
            String remote = tag.startsWith("v") ? tag.substring(1) : tag;
            String local = plugin.getPluginMeta().getVersion();
            if (compareVersions(remote, local) <= 0) {
                plugin.getLogger().info("Update check: up to date (v" + local + ")");
                return;
            }
            if (remote.equals(stagedVersion)) {
                // already downloaded and staged by an earlier check (this run or a previous one) - skip
                return;
            }

            String jarUrl = null;
            JsonArray assets = release.getAsJsonArray("assets");
            for (JsonElement a : assets) {
                JsonObject asset = a.getAsJsonObject();
                if (ASSET_NAME.equals(asset.get("name").getAsString())) {
                    jarUrl = asset.get("browser_download_url").getAsString();
                    break;
                }
            }
            if (jarUrl == null) {
                plugin.getLogger().warning("Update check: release " + tag + " has no " + ASSET_NAME + " asset");
                return;
            }

            plugin.getLogger().info("Update found: v" + local + " -> v" + remote + ", downloading...");
            File updateDir = new File(plugin.getDataFolder().getParentFile(),
                    plugin.getServer().getUpdateFolder());
            updateDir.mkdirs();
            // must keep the plugin's real jar name so Paper swaps it in on next restart
            Path target = new File(updateDir, ASSET_NAME).toPath();
            Path tmp = Files.createTempFile(updateDir.toPath(), "mcalive2-", ".part");
            HttpRequest dl = HttpRequest.newBuilder()
                    .uri(URI.create(jarUrl))
                    .header("User-Agent", "MCAlive2-Updater")
                    .timeout(Duration.ofMinutes(2))
                    .build();
            HttpResponse<InputStream> jar = http.send(dl, HttpResponse.BodyHandlers.ofInputStream());
            if (jar.statusCode() != 200) {
                plugin.getLogger().warning("Update download failed: HTTP " + jar.statusCode());
                Files.deleteIfExists(tmp);
                return;
            }
            try (InputStream in = jar.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(tmp);
            if (size < 10_000) { // sanity check: a real jar is never this small
                plugin.getLogger().warning("Update download looks truncated (" + size + " bytes), discarding");
                Files.deleteIfExists(tmp);
                return;
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            Path markerPath = new File(updateDir, MARKER_NAME).toPath();
            Files.writeString(markerPath, remote,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            stagedVersion = remote;
            emptySinceMillis = null; // a freshly staged version resets any empty-server streak

            boolean applyWhenEmpty = plugin.getConfig().getBoolean("auto-update.apply-when-empty", false);
            plugin.getLogger().warning("==================================================================");
            plugin.getLogger().warning("[MCAlive2] Update v" + remote + " downloaded and staged in "
                    + updateDir.getName() + "/.");
            plugin.getLogger().warning("[MCAlive2] It will apply on the next server restart"
                    + (applyWhenEmpty
                        ? ", or sooner: this server will auto-shutdown once it has been empty for "
                            + plugin.getConfig().getInt("auto-update.empty-minutes", 10)
                            + " minutes (auto-update.apply-when-empty is enabled)."
                        + " Make sure the server runs under a restart loop (see scripts/run-server.cmd)!"
                        : "."));
            plugin.getLogger().warning("==================================================================");
        } catch (Exception e) {
            plugin.getLogger().warning("Update check failed: " + e.getMessage());
        }
    }

    /** Compare dotted numeric versions; positive if a > b. */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("[.-]"), pb = b.split("[.-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
