package com.leonimust.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static com.leonimust.SpotiCraft.LOGGER;

public class CallbackServer {
    private static CallbackServer activeServer;

    private final HttpServer server;
    private final int port;

    private CallbackServer(int port) throws IOException {
        this.port = port;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext("/callback", new CallbackHandler(this));
        this.server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SpotiCraft-AuthCallback");
            thread.setDaemon(true);
            return thread;
        }));
    }

    public static synchronized CallbackServer startOrRestart(int port) throws IOException {
        if (activeServer != null) {
            activeServer.stop();
            activeServer = null;
        }

        CallbackServer callbackServer = new CallbackServer(port);
        callbackServer.start();
        activeServer = callbackServer;
        return callbackServer;
    }

    private void start() {
        server.start();
        LOGGER.info("Callback server started on 127.0.0.1:{}", port);
    }

    public synchronized void stop() {
        server.stop(0);
        if (activeServer == this) {
            activeServer = null;
        }
    }

    private static final class CallbackHandler implements HttpHandler {
        private final CallbackServer owner;

        private CallbackHandler(CallbackServer owner) {
            this.owner = owner;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method)) {
                send(exchange, 405, "Method not allowed.");
                return;
            }

            URI requestUri = exchange.getRequestURI();
            String query = requestUri.getRawQuery();
            String code = getQueryParam(query, "code");
            String error = getQueryParam(query, "error");

            if (code != null && !code.isBlank()) {
                LOGGER.info("Authorization code received");
                try {
                    SpotifyAuthHandler.exchangeCodeForToken(code);
                    send(exchange, 200, "Authorization successful! You can close this window and return to Minecraft.");
                    owner.stop();
                } catch (Exception e) {
                    LOGGER.error("Failed to exchange Spotify authorization code", e);
                    send(exchange, 500, "Authorization failed while exchanging the Spotify code: " + e.getMessage());
                }
                return;
            }

            if (error != null && !error.isBlank()) {
                LOGGER.warn("Spotify authorization returned error: {}", error);
                send(exchange, 400, "Authorization failed: " + error);
                return;
            }

            send(exchange, 400, "Error: No code received.");
        }

        private static String getQueryParam(String query, String key) {
            if (query == null || query.isBlank()) {
                return null;
            }

            String prefix = key + "=";
            for (String part : query.split("&")) {
                if (part.startsWith(prefix)) {
                    return part.substring(prefix.length());
                }
            }

            return null;
        }

        private static void send(HttpExchange exchange, int statusCode, String message) throws IOException {
            byte[] body = message.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(statusCode, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            } finally {
                exchange.close();
            }
        }
    }
}
