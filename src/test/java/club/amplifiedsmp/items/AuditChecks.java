package club.amplifiedsmp.items;

import java.nio.file.Files;
import java.nio.file.Path;

/** Standalone regression checks; no running Minecraft server required. */
public final class AuditChecks {
    private static int checks;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        System.out.println("AUDIT_CHECK " + (++checks) + " " + message);
    }

    public static void main(String[] args) throws Exception {
        try (var descriptor = AuditChecks.class.getResourceAsStream("/amplified-items.mixins.json")) {
            var mixins = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(descriptor, java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("mixins");
            check(mixins.asList().stream().noneMatch(name -> name.getAsString().equals("LivingFlagsAccess")),
                "Mixin registration excludes the obsolete unused living-flags accessor");
            for (var mixin : mixins) {
                check(AuditChecks.class.getResource("/club/amplifiedsmp/items/mixin/" + mixin.getAsString() + ".class") != null,
                    "Registered mixin class exists: " + mixin.getAsString());
            }
        }
        check(AuditChecks.class.getResource("/club/amplifiedsmp/items/Rituals.class") == null
                && AuditChecks.class.getResource("/club/amplifiedsmp/items/mixin/AbilityKeyMixin.class") == null,
            "Obsolete ritual and F-key classes are absent from compiled output");
        AmplifiedItems.CONFIG = new WeaponConfig();
        Path directory = Files.createTempDirectory(Path.of("build"), "audit-pack-");
        String version;
        try (var descriptor = AuditChecks.class.getResourceAsStream("/fabric.mod.json")) {
            version = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(descriptor, java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject().get("version").getAsString();
        }
        try (var pack = new PackHost(() -> version)) {
            pack.start(directory);
            check(pack.sha1() != null && pack.sha1().matches("[0-9a-f]{40}"), "Pack startup computes a SHA-1");
            pack.close();
            check(pack.sha1() == null, "Closing a pack host clears its advertised digest");
            pack.start(directory);
            Path notDirectory = directory.resolve("not-a-directory");
            Files.writeString(notDirectory, "occupied");
            try {
                pack.start(notDirectory);
                throw new AssertionError("Starting inside a file must fail");
            } catch (java.io.IOException expected) {
                check(pack.sha1() == null, "A failed restart does not advertise the previous pack digest");
            }
            AmplifiedItems.CONFIG.resourcePackHostEnabled = true;
            AmplifiedItems.CONFIG.resourcePackBindAddress = "127.0.0.1";
            try (var occupiedPort = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                AmplifiedItems.CONFIG.resourcePackPort = occupiedPort.getLocalPort();
                try {
                    pack.start(directory);
                    throw new AssertionError("Binding an occupied port must fail");
                } catch (java.io.IOException expected) {
                    check(pack.sha1() == null, "A failed HTTP bind never advertises an unavailable pack");
                }
            }
            AmplifiedItems.CONFIG.resourcePackPort = 0;
            pack.start(directory);
            var hostField = PackHost.class.getDeclaredField("host");
            hostField.setAccessible(true);
            int port = ((com.sun.net.httpserver.HttpServer) hostField.get(pack)).getAddress().getPort();
            var base = java.net.URI.create("http://127.0.0.1:" + port);
            try (var client = java.net.http.HttpClient.newHttpClient()) {
                var response = client.send(java.net.http.HttpRequest.newBuilder(base.resolve("/amplified-items.zip")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                byte[] expected = Files.readAllBytes(directory.resolve("amplified-items-resources-" + version + ".zip"));
                check(response.statusCode() == 200 && java.util.Arrays.equals(expected, response.body()), "HTTP GET serves exactly the extracted release ZIP");
                String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(response.body()));
                check(digest.equals(pack.sha1()) && response.headers().firstValue("ETag").orElse("").equals("\"" + digest + "\""),
                    "HTTP ETag and advertised hash match the bytes clients receive");
                var head = client.send(java.net.http.HttpRequest.newBuilder(base.resolve("/amplified-items.zip"))
                    .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                check(head.statusCode() == 200 && head.body().length == 0, "HTTP HEAD returns headers without a ZIP body");
                var rejected = client.send(java.net.http.HttpRequest.newBuilder(base.resolve("/amplified-items.zip"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.discarding());
                check(rejected.statusCode() == 405, "HTTP host rejects mutation methods");
                var missing = client.send(java.net.http.HttpRequest.newBuilder(base.resolve("/amplified-items.zip/../config.json")).build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
                check(missing.statusCode() == 404, "HTTP host exposes no prefix matches or configuration paths");
            }
            pack.close();
            byte[] custom = new byte[] { 80, 75, 3, 4, 1, 2, 3 };
            Files.write(directory.resolve("amplified-items-resources-" + version + ".zip"), custom);
            AmplifiedItems.CONFIG.resourcePackHostEnabled = false;
            pack.start(directory);
            check(java.util.Arrays.equals(custom, Files.readAllBytes(directory.resolve("amplified-items-resources-" + version + ".zip"))),
                "Pack restarts preserve operator edits to the extracted ZIP");
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("AUDIT_CHECKS_OK " + checks);
    }
}
