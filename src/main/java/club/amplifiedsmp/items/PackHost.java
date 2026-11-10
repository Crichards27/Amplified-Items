package club.amplifiedsmp.items;

import com.sun.net.httpserver.HttpServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.server.level.ServerPlayer;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Optional, bounded static ZIP hosting. Only the pack endpoint is exposed. */
public final class PackHost implements AutoCloseable {
    private HttpServer host;
    private ExecutorService executor;
    private String sha1;
    // Preserve the advertised pack identity for servers upgrading from the initial build.
    public static final UUID ID = UUID.fromString("f8cacdc5-4749-386b-8054-2a92c9a775c2");
    public void start(Path configDir) throws Exception {
        Path pack = configDir.resolve("amplified-items-resources-1.5.0.zip");
        try (var in = getClass().getResourceAsStream("/bundled/amplified-items-resources-1.5.0.zip")) {
            if (in == null) throw new IllegalStateException("Bundled pack is missing");
            // User edits to this extracted pack survive restarts.
            if (!Files.exists(pack)) Files.copy(in, pack);
        }
        byte[] bytes = Files.readAllBytes(pack);
        sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        var config = AmplifiedItems.CONFIG;
        AmplifiedItems.LOG.info("Resource pack: {} (SHA-1 {})", pack, sha1);
        if (!config.resourcePackHostEnabled) return;
        host = HttpServer.create(new InetSocketAddress(config.resourcePackBindAddress, config.resourcePackPort), 16);
        executor = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), r -> {
            Thread t = new Thread(r, "amplified-items-pack"); t.setDaemon(true); return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        host.setExecutor(executor);
        com.sun.net.httpserver.HttpHandler handler = exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                if (!path.equals("/amplified-items.zip")) { exchange.sendResponseHeaders(404, -1); return; }
                String method = exchange.getRequestMethod();
                if (!method.equals("GET") && !method.equals("HEAD")) { exchange.sendResponseHeaders(405, -1); return; }
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("ETag", "\"" + sha1 + "\"");
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=300");
                exchange.sendResponseHeaders(200, method.equals("HEAD") ? -1 : bytes.length);
                if (method.equals("GET")) exchange.getResponseBody().write(bytes);
            }
        };
        host.createContext("/amplified-items.zip", handler);
        host.start();
        AmplifiedItems.LOG.info("Pack host listening on {}:{}", config.resourcePackBindAddress, config.resourcePackPort);
        if (config.resourcePackPublicUrl.isBlank()) AmplifiedItems.LOG.warn("Set resourcePackPublicUrl to let clients download weapon textures.");
    }
    public void send(ServerPlayer player) {
        String url = AmplifiedItems.CONFIG.resourcePackPublicUrl;
        if (url.isBlank() || sha1 == null) return;
        player.connection.send(new ClientboundResourcePackPushPacket(ID, url, sha1,
            AmplifiedItems.CONFIG.resourcePackRequired, Optional.of(Component.literal("AmplifiedSMP custom item models and textures"))));
    }
    public String sha1() { return sha1; }
    @Override public void close() {
        if (host != null) host.stop(0);
        if (executor != null) executor.shutdownNow();
    }
}
