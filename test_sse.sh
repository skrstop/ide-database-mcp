#!/bin/bash

# Test script for SSE implementation
# This script tests the SSE functionality of the MCP server

echo "=== MCP SSE Test Script ==="
echo ""

# Configuration
HOST="127.0.0.1"
PORT="18765"
BASE_URL="http://${HOST}:${PORT}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test counter
TESTS_PASSED=0
TESTS_FAILED=0

# Function to print test result
print_result() {
  local test_name=$1
  local result=$2
  local message=$3

  if [ "$result" = "PASS" ]; then
    echo -e "${GREEN}✓ PASS${NC}: $test_name"
    ((TESTS_PASSED++))
  else
    echo -e "${RED}✗ FAIL${NC}: $test_name - $message"
    ((TESTS_FAILED++))
  fi
}

# Function to check if server is running
check_server() {
  echo -e "${YELLOW}Checking if server is running...${NC}"
  if curl -s "${BASE_URL}/health" >/dev/null 2>&1; then
    echo -e "${GREEN}Server is running${NC}"
    return 0
  else
    echo -e "${RED}Server is not running. Please start the MCP server first.${NC}"
    return 1
  fi
}

# Test 1: Health check
test_health() {
  echo -e "\n${YELLOW}Test 1: Health Check${NC}"
  response=$(curl -s "${BASE_URL}/health")
  if [ "$response" = "ok" ]; then
    print_result "Health check" "PASS"
  else
    print_result "Health check" "FAIL" "Expected 'ok', got '$response'"
  fi
}

# Test 2: SSE connection
test_sse_connection() {
  echo -e "\n${YELLOW}Test 2: SSE Connection${NC}"

  # Send GET request with SSE accept header
  response=$(
    curl -s -N -H "Accept: text/event-stream" \
      -w "\n%{http_code}\n%{header_json}" \
      "${BASE_URL}/mcp" 2>&1 &

    # Wait a bit for connection to establish
    sleep 2

    # Get the process ID
    PID=$!

    # Kill the curl process
    kill $PID 2>/dev/null
    wait $PID 2>/dev/null
  )

  # Check if we got a 200 response
  if echo "$response" | grep -q "200"; then
    print_result "SSE connection status" "PASS"
  else
    print_result "SSE connection status" "FAIL" "Expected HTTP 200"
  fi

  # Check for SSE headers
  if echo "$response" | grep -q "text/event-stream"; then
    print_result "SSE content type" "PASS"
  else
    print_result "SSE content type" "FAIL" "Expected Content-Type: text/event-stream"
  fi

  # Check for session ID header
  if echo "$response" | grep -q "Mcp-Session-Id"; then
    print_result "SSE session ID" "PASS"
  else
    print_result "SSE session ID" "FAIL" "Expected Mcp-Session-Id header"
  fi
}

# Test 3: POST with session ID
test_post_with_session() {
  echo -e "\n${YELLOW}Test 3: POST with Session ID${NC}"

  # First, get a session ID
  SESSION_ID=$(curl -s -N -H "Accept: text/event-stream" \
    -D - "${BASE_URL}/mcp" 2>&1 |
    grep -i "Mcp-Session-Id" |
    cut -d' ' -f2 |
    tr -d '\r\n')

  if [ -z "$SESSION_ID" ]; then
    print_result "Get session ID" "FAIL" "Failed to get session ID"
    return
  fi

  echo -e "Session ID: ${SESSION_ID}"

  # Test POST with session ID
  response=$(curl -s -X POST "${BASE_URL}/mcp" \
    -H "Content-Type: application/json" \
    -H "Mcp-Session-Id: ${SESSION_ID}" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}')

  if echo "$response" | grep -q '"jsonrpc":"2.0"'; then
    print_result "POST with session" "PASS"
  else
    print_result "POST with session" "FAIL" "Invalid response: $response"
  fi
}

# Test 4: POST without session ID (backward compatibility)
test_post_without_session() {
  echo -e "\n${YELLOW}Test 4: POST without Session ID (backward compatibility)${NC}"

  response=$(curl -s -X POST "${BASE_URL}/mcp" \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}')

  if echo "$response" | grep -q '"jsonrpc":"2.0"'; then
    print_result "POST without session" "PASS"
  else
    print_result "POST without session" "FAIL" "Invalid response: $response"
  fi
}

# Test 5: Invalid session ID
test_invalid_session() {
  echo -e "\n${YELLOW}Test 5: Invalid Session ID${NC}"

  response=$(curl -s -X POST "${BASE_URL}/mcp" \
    -H "Content-Type: application/json" \
    -H "Mcp-Session-Id: invalid-session-id" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
    -w "%{http_code}")

  if echo "$response" | grep -q "400"; then
    print_result "Invalid session ID" "PASS"
  else
    print_result "Invalid session ID" "FAIL" "Expected HTTP 400"
  fi
}

# Main test execution
main() {
  echo "Starting MCP SSE tests..."
  echo "Target: ${BASE_URL}"
  echo ""

  # Check if server is running
  if ! check_server; then
    exit 1
  fi

  # Run tests
  test_health
  test_sse_connection
  test_post_with_session
  test_post_without_session
  test_invalid_session

  # Print summary
  echo -e "\n${YELLOW}=== Test Summary ===${NC}"
  echo -e "Tests passed: ${GREEN}${TESTS_PASSED}${NC}"
  echo -e "Tests failed: ${RED}${TESTS_FAILED}${NC}"

  if [ $TESTS_FAILED -eq 0 ]; then
    echo -e "\n${GREEN}All tests passed!${NC}"
    exit 0
  else
    echo -e "\n${RED}Some tests failed.${NC}"
    exit 1
  fi
}

# Run main function
main
