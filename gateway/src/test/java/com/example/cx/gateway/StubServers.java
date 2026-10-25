package com.example.cx.gateway;

import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test helpers: a JDK HttpServer that echoes request details as JSON, and JWT minting with the dummy secret. */
final class StubServers {
    static final String SECRET = "dev-only-NOT-A-REAL-SECRET-change-me-0123456789abcdef";

    record Seen(String method, String uri, Map<String, List<String>> headers, String body) { }

    static final class Stub {
        final HttpServer server;
        final String name;
        final List<Seen> seen = new CopyOnWriteArrayList<>();

        Stub(String name) throws IOException {
            this.name = name;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                byte[] in = ex.getRequestBody().readAllBytes();
                seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().toString(), Map.copyOf(ex.getRequestHeaders()),
                        new String(in, StandardCharsets.UTF_8)));
                byte[] out = ("{\"stub\":\"" + name + "\",\"path\":\"" + ex.getRequestURI().getPath() + "\"}").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.getResponseHeaders().add("X-Stub", name);
                int status = ex.getRequestURI().getPath().endsWith("/teapot") ? 418 : 200;
                ex.sendResponseHeaders(status, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            });
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        void stop() { server.stop(0); }
    }

    static String token(String secret, long ttlMs) {
        return Jwts.builder().subject(UUID.randomUUID().toString()).claim("tid", UUID.randomUUID().toString())
                .claim("roles", List.of("AGENT")).id(UUID.randomUUID().toString())
                .expiration(new Date(System.currentTimeMillis() + ttlMs))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }

    static String token() { return token(SECRET, 60_000); }

    static HttpResponse<String> send(HttpClient c, String method, String url, String bearer, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private StubServers() { }
}
