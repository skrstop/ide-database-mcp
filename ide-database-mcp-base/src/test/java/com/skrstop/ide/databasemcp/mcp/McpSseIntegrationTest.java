package com.skrstop.ide.databasemcp.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Simple integration test for SSE functionality.
 * This test verifies that the MCP server correctly handles GET requests for SSE connections.
 * <p>
 * To run this test:
 * 1. Start the MCP server (e.g., by running the plugin in a test IDE)
 * 2. Run this class as a Java application
 * <p>
 * Expected output:
 * - SSE connection should be established
 * - Session ID should be received in response header
 * - SSE events should be received
 */
public class McpSseIntegrationTest {

    private static final String BASE_URL = "http://localhost:18765";
    private static final String MCP_ENDPOINT = BASE_URL + "/mcp";

    public static void main(String[] args) {
        System.out.println("=== MCP SSE Integration Test ===\n");

        try {
            // Test 1: SSE GET request
            testSseConnection();

            // Test 2: POST with session ID
            testPostWithSession();

            System.out.println("\n=== All tests completed ===");
        } catch (Exception e) {
            System.err.println("Test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void testSseConnection() throws IOException {
        System.out.println("Test 1: Testing SSE GET connection...");

        URL url = new URL(MCP_ENDPOINT);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setDoInput(true);
        connection.setDoOutput(false);

        int responseCode = connection.getResponseCode();
        System.out.println("  Response code: " + responseCode);

        if (responseCode != 200) {
            throw new RuntimeException("Expected 200, got " + responseCode);
        }

        // Check response headers
        String contentType = connection.getHeaderField("Content-Type");
        String sessionId = connection.getHeaderField("Mcp-Session-Id");
        String cacheControl = connection.getHeaderField("Cache-Control");
        String connectionHeader = connection.getHeaderField("Connection");

        System.out.println("  Content-Type: " + contentType);
        System.out.println("  Mcp-Session-Id: " + sessionId);
        System.out.println("  Cache-Control: " + cacheControl);
        System.out.println("  Connection: " + connectionHeader);

        // Validate headers
        if (contentType == null || !contentType.contains("text/event-stream")) {
            throw new RuntimeException("Expected Content-Type: text/event-stream, got: " + contentType);
        }
        if (sessionId == null || sessionId.isEmpty()) {
            throw new RuntimeException("Expected Mcp-Session-Id header, but it was missing");
        }
        if (cacheControl == null || !cacheControl.contains("no-cache")) {
            throw new RuntimeException("Expected Cache-Control: no-cache, got: " + cacheControl);
        }

        // Read SSE events (with timeout)
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            int eventCount = 0;
            long startTime = System.currentTimeMillis();

            while ((line = reader.readLine()) != null && eventCount < 5) {
                if (System.currentTimeMillis() - startTime > 5000) {
                    System.out.println("  Timeout reading SSE events, stopping");
                    break;
                }

                if (!line.isEmpty()) {
                    System.out.println("  SSE: " + line);
                    eventCount++;
                }
            }

            System.out.println("  Received " + eventCount + " SSE lines");
        }

        connection.disconnect();
        System.out.println("  ✓ SSE connection test passed\n");
    }

    private static void testPostWithSession() throws IOException {
        System.out.println("Test 2: Testing POST with session ID...");

        // First, get a session ID via SSE
        URL url = new URL(MCP_ENDPOINT);
        HttpURLConnection sseConnection = (HttpURLConnection) url.openConnection();
        sseConnection.setRequestMethod("GET");
        sseConnection.setRequestProperty("Accept", "text/event-stream");
        sseConnection.setConnectTimeout(5000);
        sseConnection.setReadTimeout(5000);

        String sessionId = sseConnection.getHeaderField("Mcp-Session-Id");
        sseConnection.disconnect();

        if (sessionId == null || sessionId.isEmpty()) {
            throw new RuntimeException("Failed to get session ID from SSE connection");
        }

        System.out.println("  Got session ID: " + sessionId);

        // Now, test POST with session ID
        HttpURLConnection postConnection = (HttpURLConnection) url.openConnection();
        postConnection.setRequestMethod("POST");
        postConnection.setRequestProperty("Content-Type", "application/json");
        postConnection.setRequestProperty("Mcp-Session-Id", sessionId);
        postConnection.setDoInput(true);
        postConnection.setDoOutput(true);

        // Send initialize request
        String initRequest = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        postConnection.getOutputStream().write(initRequest.getBytes(StandardCharsets.UTF_8));

        int responseCode = postConnection.getResponseCode();
        System.out.println("  POST response code: " + responseCode);

        if (responseCode != 200) {
            throw new RuntimeException("Expected 200, got " + responseCode);
        }

        // Read response
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(postConnection.getInputStream(), StandardCharsets.UTF_8))) {

            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            System.out.println("  POST response: " + response.toString());

            // Validate response contains expected fields
            String responseStr = response.toString();
            if (!responseStr.contains("\"jsonrpc\":\"2.0\"")) {
                throw new RuntimeException("Response missing jsonrpc field");
            }
            if (!responseStr.contains("\"id\":1")) {
                throw new RuntimeException("Response missing id field");
            }
            if (!responseStr.contains("\"result\"")) {
                throw new RuntimeException("Response missing result field");
            }
        }

        postConnection.disconnect();
        System.out.println("  ✓ POST with session test passed\n");
    }
}
