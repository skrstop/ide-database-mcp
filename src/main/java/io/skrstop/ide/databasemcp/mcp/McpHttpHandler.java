package io.skrstop.ide.databasemcp.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.skrstop.ide.databasemcp.db.IdeDatabaseFacade;
import io.skrstop.ide.databasemcp.service.McpRuntimeLogService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class McpHttpHandler implements HttpHandler {
    private static final int MAX_LOG_PAYLOAD_LENGTH = 20000;

    private final McpProtocolRouter router;

    public McpHttpHandler(IdeDatabaseFacade databaseFacade) {
        this.router = new McpProtocolRouter(databaseFacade);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String uri = exchange.getRequestURI() == null ? "" : exchange.getRequestURI().toString();

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            byte[] body = "Method Not Allowed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(405, body.length);
            exchange.getResponseBody().write(body);
            logWarn("[HTTP-IN] method=" + method + " uri=" + uri + " body=<empty>");
            logWarn("[HTTP-OUT] status=405 body=" + formatPayload("Method Not Allowed"));
            exchange.close();
            return;
        }

        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        logInfo("[HTTP-IN] method=" + method + " uri=" + uri + " body=" + formatPayload(requestBody));

        int status = 200;
        String response;
        try {
            response = router.handle(requestBody);
        } catch (Exception ex) {
            status = 500;
            response = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"Internal error: " + escapeJson(ex.getMessage()) + "\"}}";
            logError("[HTTP-OUT] status=500 reason=" + ex.getMessage());
        }

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        logInfo("[HTTP-OUT] status=" + status + " body=" + formatPayload(response));
        exchange.close();
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

    private void logInfo(String message) {
        McpRuntimeLogService service = logService();
        if (service != null) {
            service.info("http", message);
        }
    }

    private void logWarn(String message) {
        McpRuntimeLogService service = logService();
        if (service != null) {
            service.warn("http", message);
        }
    }

    private void logError(String message) {
        McpRuntimeLogService service = logService();
        if (service != null) {
            service.error("http", message);
        }
    }

    private McpRuntimeLogService logService() {
        return com.intellij.openapi.application.ApplicationManager.getApplication() == null
                ? null
                : McpRuntimeLogService.getInstance();
    }
}
