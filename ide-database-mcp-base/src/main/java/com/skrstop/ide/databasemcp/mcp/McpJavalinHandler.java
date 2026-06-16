package com.skrstop.ide.databasemcp.mcp;

import com.skrstop.ide.databasemcp.db.IdeDatabaseFacade;
import com.skrstop.ide.databasemcp.service.McpRuntimeLogService;
import com.skrstop.ide.databasemcp.settings.McpSettingsState;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.javalin.Javalin;
import io.javalin.http.sse.SseClient;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * MCP HTTP handler using Javalin with built-in SSE support.
 *
 * <p>Supports two MCP transport modes:
 * <ul>
 *   <li><b>SSE mode</b> — Client connects via GET (SSE), receives an endpoint URL,
 *       then POSTs JSON-RPC requests to that URL. Responses are sent back through
 *       the SSE connection as {@code message} events, and the POST returns 202.</li>
 *   <li><b>Streamable HTTP mode</b> — Client POSTs JSON-RPC requests directly to
 *       {@code /mcp} without an SSE connection. Responses are returned in the
 *       HTTP response body.</li>
 * </ul>
 */
public final class McpJavalinHandler {
    private static final int MAX_LOG_PAYLOAD_LENGTH = 20000;
    private static final int DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 5;

    private final McpProtocolRouter router;
    private final ConcurrentHashMap<String, SseClient> sseClients = new ConcurrentHashMap<>();
    private Javalin app;
    private ScheduledFuture<?> heartbeatTask;

    public McpJavalinHandler(IdeDatabaseFacade databaseFacade) {
        this.router = new McpProtocolRouter(databaseFacade);
    }

    /**
     * Starts the Javalin server on the specified port.
     *
     * @param port The port to listen on
     */
    public void start(int port) {
        app = Javalin.create(config -> {
            config.jetty.defaultHost = "0.0.0.0";
        });

        // SSE endpoint — MCP SSE transport: client connects here to receive events
        app.sse("/mcp", client -> {
            // CRITICAL: keep the SSE stream open after this lambda returns.
            // In Javalin 6, the SSE connection is closed as soon as the consumer
            // lambda exits unless keepAlive() is called. Without this, clients can
            // never hold a stable SSE channel and will reconnect forever.
            client.keepAlive();

            String sessionId = java.util.UUID.randomUUID().toString();
            try {
                String remoteIp = client.ctx().ip();
                String userAgent = client.ctx().header("User-Agent");

                // Clean up stale sessions from the same IP: when a client (e.g. Cherry Studio)
                // disables and re-enables MCP, old SSE connections may not be closed cleanly.
                // Remove dead sessions to prevent resource leaks and session ID confusion.
                cleanupStaleSessions(remoteIp);

                // Set session ID header so client knows its session
                client.ctx().header("Mcp-Session-Id", sessionId);

                // Register first to avoid race: client may POST immediately after receiving endpoint.
                sseClients.put(sessionId, client);
                McpRuntimeLogService.logInfo("sse", "[SSE] Client registered, session=" + sessionId
                        + " remoteIp=" + remoteIp
                        + " userAgent=" + (userAgent == null ? "unknown" : userAgent)
                        + " activeClients=" + sseClients.size());

                // Include sessionId in endpoint so the client correlates its POST requests
                // with this SSE connection.
                String endpointUrl = client.ctx().scheme() + "://" + client.ctx().host() + "/mcp?sessionId=" + sessionId;

                // Per MCP SSE spec: send the endpoint URL that the client should POST to.
                client.sendEvent("endpoint", endpointUrl);
                McpRuntimeLogService.logInfo("sse", "[SSE] Connection established, session=" + sessionId
                        + " endpoint=" + endpointUrl);

                client.onClose(() -> {
                    sseClients.remove(sessionId, client);
                    McpRuntimeLogService.logInfo("sse", "[SSE] Connection closed, session=" + sessionId
                            + " activeClients=" + sseClients.size());
                });
            } catch (Exception e) {
                sseClients.remove(sessionId, client);
                McpRuntimeLogService.logWarn("sse", "[SSE] Error, session=" + sessionId + ": " + e.getMessage());
            }
        });

        // DELETE /mcp — terminate SSE session (MCP Streamable HTTP spec)
        app.delete("/mcp", ctx -> {
            String sessionId = ctx.header("Mcp-Session-Id");
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = ctx.queryParam("sessionId");
            }
            if (sessionId != null && !sessionId.isEmpty()) {
                SseClient client = sseClients.remove(sessionId);
                if (client != null) {
                    client.close();
                    McpRuntimeLogService.logInfo("http", "[HTTP-DELETE] Session terminated: " + sessionId);
                    ctx.status(200);
                    ctx.result("Session terminated");
                } else {
                    ctx.status(404);
                    ctx.result("Session not found");
                }
            } else {
                ctx.status(400);
                ctx.result("Missing Mcp-Session-Id header or sessionId parameter");
            }
        });

        // JSON-RPC endpoint for POST requests. Two transports are supported:
        //  1. HTTP+SSE transport — the client first opened a GET SSE channel and
        //     received an endpoint URL containing its sessionId. For these requests
        //     the JSON-RPC response MUST be delivered back through that SSE channel
        //     as a "message" event, and this POST returns 202 Accepted. Returning
        //     the response in the POST body instead leaves the SSE client waiting
        //     forever, causing endless reconnects.
        //  2. Streamable HTTP transport — the client POSTs without any live SSE
        //     channel. The JSON-RPC response is returned directly in this POST body.
        //
        // NOTE: If a sessionId is provided but no longer valid (e.g. client reconnected
        // after a disable/enable cycle), we gracefully fall back to Streamable HTTP
        // instead of returning 400. This prevents connection failures when clients
        // cache a stale session ID.
        app.post("/mcp", ctx -> {
            String method = ctx.method().name();
            String uri = ctx.path();
            String sessionId = ctx.header("Mcp-Session-Id");
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = ctx.queryParam("sessionId");
            }

            McpRuntimeLogService.logInfo("http", "[HTTP-IN] method=" + method + " uri=" + uri
                    + " sessionId=" + (sessionId != null ? sessionId : "none"));
            McpRuntimeLogService.logInfo("http", "[HTTP-IN] ua=" + (ctx.header("User-Agent") != null ? ctx.header("User-Agent") : "unknown")
                    + " remoteIp=" + ctx.ip());

            String requestBody = ctx.body();
            McpRuntimeLogService.logInfo("http", "[HTTP-IN] body=" + formatPayload(requestBody));

            // Process the JSON-RPC request and return the response in the HTTP body.
            int status = 200;
            String response;
            try {
                response = router.handle(requestBody);
            } catch (Exception ex) {
                status = 500;
                response = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error: " + escapeJson(ex.getMessage()) + "\"}}";
                McpRuntimeLogService.logError("http", "[HTTP-OUT] status=500 reason=" + ex.getMessage());
            }

            // null response = notification accepted (MCP spec: no response body for notifications)
            if (response == null) {
                ctx.status(202);
                McpRuntimeLogService.logInfo("http", "[HTTP-OUT] status=202 (notification accepted)");
                return;
            }

            // HTTP+SSE transport: if this POST belongs to a live SSE session, deliver
            // the JSON-RPC response over that SSE channel and ACK the POST with 202.
            SseClient sseClient = sessionId != null ? sseClients.get(sessionId) : null;
            if (sseClient != null) {
                try {
                    sseClient.sendEvent("message", response);
                    ctx.status(202);
                    McpRuntimeLogService.logInfo("http", "[HTTP-OUT] status=202 (response routed via SSE session=" + sessionId + ") body=" + formatPayload(response));
                    return;
                } catch (Exception ex) {
                    // SSE channel is gone — fall back to returning the response in the body.
                    sseClients.remove(sessionId, sseClient);
                    McpRuntimeLogService.logWarn("sse", "[SSE] Failed to route response to session " + sessionId + ", falling back to HTTP body: " + ex.getMessage());
                }
            }

            // Streamable HTTP transport (or SSE session expired): return in the body.
            if (sessionId != null && sseClient == null) {
                McpRuntimeLogService.logInfo("http", "[HTTP-OUT] Session " + sessionId
                        + " not found in SSE clients, falling back to Streamable HTTP");
            }
            ctx.status(status);
            ctx.contentType("application/json; charset=utf-8");
            ctx.result(response);
            McpRuntimeLogService.logInfo("http", "[HTTP-OUT] status=" + status + " body=" + formatPayload(response));
        });

        // Health check endpoint
        app.get("/health", ctx -> {
            ctx.result("ok");
        });

        app.start(port);
        startHeartbeat();
        McpRuntimeLogService.logInfo("server", "Javalin server started on port " + port);
    }

    /**
     * Periodically pings every SSE client with an SSE comment. This serves two purposes:
     * <ul>
     *   <li>Keeps idle SSE connections alive through OS/proxy idle timeouts, so the client
     *       does not silently lose its channel after the initial handshake.</li>
     *   <li>Detects dead/half-open sockets (e.g. when a client is disabled without a clean
     *       close): the write fails, and we remove the stale session so it stops counting as
     *       an active client and reconnects start from a clean state.</li>
     * </ul>
     */
    private void startHeartbeat() {
        int intervalSeconds = getHeartbeatIntervalSeconds();
        // Reuse the platform-managed shared scheduler instead of spawning our own thread/executor,
        // as recommended for IntelliJ plugins.
        heartbeatTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                () -> sseClients.forEach((sessionId, client) -> {
                    try {
                        client.sendComment("ping");
                    } catch (Exception e) {
                        if (sseClients.remove(sessionId, client)) {
                            try {
                                client.close();
                            } catch (Exception ignored) {
                                // best-effort cleanup
                            }
                            McpRuntimeLogService.logInfo("sse", "[SSE] Heartbeat detected dead session=" + sessionId
                                    + ", removed. activeClients=" + sseClients.size());
                        }
                    }
                }),
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        McpRuntimeLogService.logInfo("sse", "[SSE] Heartbeat started, interval=" + intervalSeconds + "s");
    }

    private int getHeartbeatIntervalSeconds() {
        try {
            int value = McpSettingsState.getInstance().getHeartbeatIntervalEffective();
            return value >= 1 ? value : DEFAULT_HEARTBEAT_INTERVAL_SECONDS;
        } catch (Exception e) {
            return DEFAULT_HEARTBEAT_INTERVAL_SECONDS;
        }
    }

    /**
     * Cleans up stale SSE sessions from the given IP address.
     * When a client disables and re-enables MCP without closing the old SSE connection
     * cleanly, the old session lingers in the map. This method detects and removes
     * such dead sessions by sending a ping; if the write fails, the session is dead.
     *
     * @param remoteIp the IP address to check sessions for
     */
    private void cleanupStaleSessions(String remoteIp) {
        if (remoteIp == null) {
            return;
        }
        java.util.List<String> toRemove = new java.util.ArrayList<>();
        sseClients.forEach((sid, sseClient) -> {
            try {
                String clientIp = sseClient.ctx().ip();
                if (remoteIp.equals(clientIp)) {
                    // Probe the connection — if it's dead, sendComment throws
                    sseClient.sendComment("ping");
                }
            } catch (Exception e) {
                // Connection is dead, mark for removal
                toRemove.add(sid);
            }
        });
        for (String sid : toRemove) {
            SseClient stale = sseClients.remove(sid);
            if (stale != null) {
                try {
                    stale.close();
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
                McpRuntimeLogService.logInfo("sse", "[SSE] Cleaned up stale session=" + sid
                        + " on new connection from same IP=" + remoteIp + ", activeClients=" + sseClients.size());
            }
        }
    }

    /**
     * Stops the Javalin server.
     */
    public void stop() {
        // Stop heartbeat first so it does not race with connection teardown.
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
        // Close all SSE connections before stopping
        sseClients.forEach((sessionId, client) -> {
            try {
                client.close();
            } catch (Exception e) {
                McpRuntimeLogService.logWarn("sse", "[SSE] Error closing session " + sessionId + ": " + e.getMessage());
            }
        });
        sseClients.clear();
        if (app != null) {
            app.stop();
            app = null;
        }
        McpRuntimeLogService.logInfo("server", "Javalin server stopped");
    }

    private static String formatPayload(String payload) {
        String value = payload == null ? "" : payload.replace("\r", "\\r").replace("\n", "\\n");
        if (value.length() <= MAX_LOG_PAYLOAD_LENGTH) {
            return value;
        }
        int omitted = value.length() - MAX_LOG_PAYLOAD_LENGTH;
        return value.substring(0, MAX_LOG_PAYLOAD_LENGTH) + "...<truncated " + omitted + " chars>";
    }

    private static String escapeJson(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        return message
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", " ");
    }

}
