# SSE (Server-Sent Events) Implementation Summary

## Overview

This document describes the implementation of SSE support for the MCP (Model Context Protocol) server, using **Javalin**
for clean and maintainable SSE handling.

## Why Javalin?

Instead of hand-rolling SSE with `com.sun.net.httpserver.HttpServer`, we use **Javalin** which provides:

- ✅ Built-in SSE support with clean API
- ✅ Automatic connection management
- ✅ Heartbeat support
- ✅ Event broadcasting
- ✅ Proper resource cleanup

## Changes Made

### 1. New Files Created

#### `McpJavalinHandler.java`

- **Purpose**: Main HTTP/SSE handler using Javalin
- **Key Features**:
    - SSE endpoint (`GET /mcp`) with automatic connection management
    - JSON-RPC endpoint (`POST /mcp`) for MCP requests
    - Health check endpoint (`GET /health`)
    - Built-in heartbeat for SSE connections
    - Session tracking and broadcasting

### 2. Modified Files

#### `McpServerManager.java`

- **Changes**:
    - Replaced `com.sun.net.httpserver.HttpServer` with `McpJavalinHandler`
    - Simplified server lifecycle management
    - Removed manual thread pool management (Javalin handles this)

#### `McpProtocolRouter.java`

- **Changes**:
    - Updated `initializeResult()` to include SSE capabilities in response
    - Added `"sse": {"supported": true}` to capabilities

#### `build.gradle.kts`

- **Changes**:
    - Added Javalin dependency: `io.javalin:javalin:6.1.3`

### 3. Documentation Updates

#### `README.md` and `README_zh.md`

- Added SSE to key features list
- Added new section "4.2) SSE (Server-Sent Events) Support" with:
    - Connection examples
    - Session ID usage
    - Event format
    - Session lifecycle

## Technical Details

### SSE Event Format

Events follow the standard SSE specification:

```
event: <event-type>
data: <json-data>

```

### Event Types

1. **`endpoint`**: Sent immediately after connection
    - Contains the POST endpoint URL
    - Example: `data: /mcp`

2. **`message`**: Used for JSON-RPC messages
    - Contains full JSON-RPC response/notification
    - Capabilities are obtained from router's `initialize` response (consistent with POST requests)
    - Example: `data: {"jsonrpc":"2.0","method":"notifications/initialized","params":{...}}`

### Capabilities Consistency

The SSE handler obtains capabilities by calling the router's `handle()` method with an initialize request. This ensures:

- SSE capabilities match exactly what POST `/mcp` returns for `initialize`
- Single source of truth in `McpProtocolRouter.initializeResult()`
- No duplication or synchronization issues

### Session Management

- Each SSE connection gets a unique UUID session ID
- Session ID is returned in `Mcp-Session-Id` response header
- Clients must include session ID in subsequent POST requests
- Sessions are automatically cleaned up on disconnect

### Headers

#### SSE Response Headers

```
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive
Mcp-Session-Id: <uuid>
```

#### POST Request Headers (with session)

```
Content-Type: application/json
Mcp-Session-Id: <uuid>
```

## Usage Examples

### 1. Establish SSE Connection

```bash
curl -N -H "Accept: text/event-stream" http://localhost:18765/mcp
```

### 2. Use Session ID in POST Request

```bash
curl -s http://localhost:18765/mcp \
  -H 'Content-Type: application/json' \
  -H 'Mcp-Session-Id: <session-id>' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize"}'
```

### 3. Full MCP Handshake via SSE

1. Client sends GET `/mcp` with `Accept: text/event-stream`
2. Server responds with session ID and initial events
3. Client sends POST `/mcp` with session ID for initialize
4. Client sends POST `/mcp` with session ID for tools/list
5. Client sends POST `/mcp` with session ID for tools/call

## Error Handling

- **Invalid session ID**: Returns 400 Bad Request
- **Missing session header**: Returns 400 Bad Request
- **Session expired**: Returns 400 Bad Request
- **Invalid Accept header**: Returns 400 Bad Request
- **Non-GET request to SSE endpoint**: Returns 405 Method Not Allowed

## Backward Compatibility

- Existing POST-only clients continue to work without changes
- Session validation only applies when `Mcp-Session-Id` header is present
- Clients can still use POST without session ID (legacy mode)

## Future Enhancements

1. **Reconnection**: Support client reconnection with session resumption
2. **Event Filtering**: Allow clients to subscribe to specific event types
3. **Rate Limiting**: Implement rate limiting for SSE connections
4. **Metrics**: Add SSE connection metrics and monitoring

## Testing

### Manual Testing

```bash
# Test SSE connection
curl -N -H "Accept: text/event-stream" http://localhost:18765/mcp

# Test POST with session
curl -s http://localhost:18765/mcp \
  -H 'Content-Type: application/json' \
  -H 'Mcp-Session-Id: <session-id>' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize"}'
```

### Integration Testing

Run `McpSseIntegrationTest.java` to verify:

1. SSE connection establishment
2. Session ID validation
3. POST requests with session ID
4. Event reception

## Dependencies

### Required Dependencies

- `io.javalin:javalin:6.1.3` - Lightweight web framework with SSE support
- `com.google.code.gson:gson:2.11.0` - JSON parsing
- `com.sun.net.httpserver.HttpServer` - JDK built-in (used by Javalin internally)

## Compliance

This implementation follows the MCP Streamable HTTP transport specification:

- Supports GET requests for SSE connections
- Uses `Mcp-Session-Id` header for session management
- Sends events in standard SSE format
- Maintains connection for server-initiated messages
