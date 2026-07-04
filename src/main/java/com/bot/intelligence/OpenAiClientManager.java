package com.bot.intelligence;

import java.io.BufferedWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central OpenAI client shared by every AI agent.
 *
 * All OpenAI access should flow through this manager so the project has one
 * place for API key handling, model selection, timeouts, retries, rate limiting,
 * cost/usage journaling, and fail-closed behavior.  Agents can be enabled or
 * disabled independently, but they all reuse OPENAI_API_KEY.
 */
public final class OpenAiClientManager {
    private static final OpenAiClientManager INSTANCE = new OpenAiClientManager();

    private final boolean enabled = envBool("OPENAI_AGENTS_ENABLED", true);
    private final String apiKey = env("OPENAI_API_KEY", "");
    private final String responsesUrl = env("OPENAI_RESPONSES_URL", "https://api.openai.com/v1/responses");
    private final String defaultModel = env("OPENAI_DEFAULT_MODEL", env("OPENAI_ENTRY_EXIT_MODEL", "gpt-5.5"));
    private final int timeoutSeconds = envInt("OPENAI_REQUEST_TIMEOUT_SECONDS", 60);
    private final int maxConcurrent = Math.max(1, envInt("OPENAI_MAX_CONCURRENT_REQUESTS", 2));
    private final int maxRetries = Math.max(0, envInt("OPENAI_MAX_RETRIES", 1));
    private final long minSpacingMs = Math.max(0L, envLong("OPENAI_MIN_REQUEST_SPACING_MS", 250L));
    private final Path journalPath = Path.of(env("OPENAI_AGENT_JOURNAL", "logs/openai_agent_journal.csv"));
    private final Path quotaStatusPath = Path.of(env("OPENAI_QUOTA_STATUS_PATH", "logs/openai_quota_status.properties"));
    private final boolean disableWhenQuotaExhausted = envBool("OPENAI_DISABLE_WHEN_QUOTA_EXHAUSTED", true);
    private final long quotaCooldownMs = Math.max(60_000L, envLong("OPENAI_QUOTA_COOLDOWN_MS", 6L * 60L * 60L * 1000L));
    private final boolean clearQuotaStatusOnStart = envBool("OPENAI_CLEAR_QUOTA_STATUS_ON_START", true);
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(Math.max(3, timeoutSeconds)))
            .build();
    private final Semaphore semaphore = new Semaphore(maxConcurrent);
    private final AtomicLong lastRequestAt = new AtomicLong(0L);

    private OpenAiClientManager() {
        ensureJournal();
        clearQuotaStatusIfRequested();
        if (!enabled) {
            System.out.println("OPENAI CLIENT MANAGER DISABLED: OPENAI_AGENTS_ENABLED=false");
        } else if (apiKey.isBlank()) {
            System.out.println("OPENAI CLIENT MANAGER READY BUT API KEY MISSING: set OPENAI_API_KEY to enable OpenAI-backed agents.");
        } else {
            System.out.println("OPENAI CLIENT MANAGER READY: model=" + defaultModel + " maxConcurrent=" + maxConcurrent + " timeoutSeconds=" + timeoutSeconds + " journal=" + journalPath);
        }
    }

    public static OpenAiClientManager getInstance() {
        return INSTANCE;
    }

    public boolean isUsable() {
        return enabled && !apiKey.isBlank() && !quotaCooldownActive();
    }

    public String defaultModel() {
        return defaultModel;
    }

    public OpenAiResult requestJson(String agentName, String model, String instructions, String input, int maxOutputTokens) {
        int perAgentTimeout = timeoutForAgent(agentName);
        int perAgentRetries = retriesForAgent(agentName);
        return requestJsonInternal(agentName, model, instructions, input, maxOutputTokens, perAgentTimeout, perAgentRetries);
    }

    public OpenAiResult requestJsonWithTimeout(String agentName, String model, String instructions, String input, int maxOutputTokens, int timeoutSecondsOverride, int maxRetriesOverride) {
        int perAgentTimeout = Math.max(10, timeoutSecondsOverride);
        int perAgentRetries = Math.max(0, maxRetriesOverride);
        return requestJsonInternal(agentName, model, instructions, input, maxOutputTokens, perAgentTimeout, perAgentRetries);
    }

    private OpenAiResult requestJsonInternal(String agentName, String model, String instructions, String input, int maxOutputTokens, int requestTimeoutSeconds, int requestMaxRetries) {
        long started = System.currentTimeMillis();
        String agent = agentName == null || agentName.isBlank() ? "UNKNOWN_AGENT" : agentName.trim();
        if (!isUsable()) {
            String message;
            if (!enabled) message = "OPENAI_AGENTS_ENABLED=false";
            else if (apiKey.isBlank()) message = "OPENAI_API_KEY missing";
            else message = "OpenAI quota cooldown active; using local/statistical fallback";
            journal(agent, "SKIPPED", 0, 0, message);
            return OpenAiResult.skipped(message);
        }

        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(Math.max(1, requestTimeoutSeconds), java.util.concurrent.TimeUnit.SECONDS);
            if (!acquired) {
                String message = "OpenAI request skipped: concurrency limit busy";
                journal(agent, "SKIPPED", 0, 0, message);
                return OpenAiResult.skipped(message);
            }
            throttle();
            String body = buildResponsesBody(
                    model == null || model.isBlank() ? defaultModel : model.trim(),
                    instructions,
                    input,
                    Math.max(256, maxOutputTokens)
            );

            Exception lastError = null;
            for (int attempt = 0; attempt <= requestMaxRetries; attempt++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(responsesUrl))
                            .timeout(Duration.ofSeconds(Math.max(3, requestTimeoutSeconds)))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    String responseBody = response.body() == null ? "" : response.body();
                    long elapsed = System.currentTimeMillis() - started;
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        String output = extractText(responseBody);
                        journal(agent, "OK", response.statusCode(), elapsed, abbreviate(output, 500));
                        return OpenAiResult.ok(response.statusCode(), elapsed, responseBody, output);
                    }
                    String message = "HTTP " + response.statusCode() + " body=" + abbreviate(responseBody, 500);
                    journal(agent, "HTTP_ERROR", response.statusCode(), elapsed, message);
                    if (isQuotaExhausted(response.statusCode(), responseBody)) {
                        markQuotaExhausted(message);
                        return OpenAiResult.error(response.statusCode(), elapsed, message, responseBody);
                    }
                    if (response.statusCode() != 429 && response.statusCode() < 500) {
                        return OpenAiResult.error(response.statusCode(), elapsed, message, responseBody);
                    }
                    lastError = new RuntimeException(message);
                    Thread.sleep(Math.min(2000L, 350L * (attempt + 1L)));
                } catch (Exception e) {
                    lastError = e;
                    if (attempt < requestMaxRetries) {
                        try { Thread.sleep(Math.min(2000L, 350L * (attempt + 1L))); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                }
            }
            long elapsed = System.currentTimeMillis() - started;
            String message = lastError == null ? "unknown OpenAI failure" : lastError.getMessage();
            journal(agent, "ERROR", 0, elapsed, message);
            return OpenAiResult.error(0, elapsed, message, "");
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - started;
            journal(agent, "ERROR", 0, elapsed, e.getMessage());
            return OpenAiResult.error(0, elapsed, e.getMessage(), "");
        } finally {
            if (acquired) semaphore.release();
        }
    }


    private int timeoutForAgent(String agentName) {
        String agent = agentName == null ? "" : agentName.toUpperCase(Locale.ROOT);
        if (agent.contains("NIGHTLY") || agent.contains("POLICY_REVIEW") || agent.contains("ENTRY_EXIT_POLICY_REVIEW")) {
            return Math.max(timeoutSeconds, envInt("OPENAI_NIGHTLY_REQUEST_TIMEOUT_SECONDS", 180));
        }
        if (agent.contains("ENTRY_EXIT") || agent.contains("GOVERNOR")) {
            return Math.max(10, envInt("OPENAI_LIVE_GOVERNOR_TIMEOUT_SECONDS", Math.min(timeoutSeconds, 25)));
        }
        return timeoutSeconds;
    }

    private int retriesForAgent(String agentName) {
        String agent = agentName == null ? "" : agentName.toUpperCase(Locale.ROOT);
        if (agent.contains("NIGHTLY") || agent.contains("POLICY_REVIEW") || agent.contains("ENTRY_EXIT_POLICY_REVIEW")) {
            return Math.max(maxRetries, envInt("OPENAI_NIGHTLY_MAX_RETRIES", 2));
        }
        return maxRetries;
    }

    private void throttle() {
        if (minSpacingMs <= 0L) return;
        long now = System.currentTimeMillis();
        long last = lastRequestAt.get();
        long wait = minSpacingMs - (now - last);
        if (wait > 0L) {
            try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        lastRequestAt.set(System.currentTimeMillis());
    }

    private static String buildResponsesBody(String model, String instructions, String input, int maxOutputTokens) {
        return "{"
                + "\"model\":" + json(model) + ","
                + "\"max_output_tokens\":" + maxOutputTokens + ","
                + "\"text\":{\"format\":{\"type\":\"json_object\"}},"
                + "\"input\":["
                + "{\"role\":\"system\",\"content\":" + json(instructions == null ? "" : instructions) + "},"
                + "{\"role\":\"user\",\"content\":" + json(input == null ? "" : input) + "}"
                + "]"
                + "}";
    }

    /** Best-effort extraction of output_text from the Responses API JSON without adding dependencies. */
    private static String extractText(String body) {
        if (body == null || body.isBlank()) return "";
        int idx = body.indexOf("\"output_text\"");
        if (idx >= 0) {
            int textIdx = body.indexOf("\"text\"", idx);
            if (textIdx >= 0) {
                int colon = body.indexOf(':', textIdx);
                if (colon >= 0) return parseJsonStringAt(body, colon + 1);
            }
        }
        int textIdx = body.indexOf("\"text\"");
        if (textIdx >= 0) {
            int colon = body.indexOf(':', textIdx);
            if (colon >= 0) return parseJsonStringAt(body, colon + 1);
        }
        return body;
    }

    private static String parseJsonStringAt(String s, int start) {
        int i = start;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length() || s.charAt(i) != '"') return "";
        i++;
        StringBuilder out = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c == '\\' && i < s.length()) {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case 'u':
                        if (i + 4 <= s.length()) {
                            try { out.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); } catch (Exception ignored) {}
                            i += 4;
                        }
                        break;
                    default: out.append(e);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }



    private void clearQuotaStatusIfRequested() {
        if (!clearQuotaStatusOnStart) return;
        try {
            if (Files.exists(quotaStatusPath)) {
                Files.deleteIfExists(quotaStatusPath);
                System.out.println("OPENAI QUOTA STATUS CLEARED ON START: " + quotaStatusPath);
            }
        } catch (Exception e) {
            System.out.println("OPENAI QUOTA STATUS CLEAR FAILED: " + e.getMessage());
        }
    }

    private boolean quotaCooldownActive() {
        if (!disableWhenQuotaExhausted) return false;
        try {
            if (!Files.exists(quotaStatusPath)) return false;
            List<String> lines = Files.readAllLines(quotaStatusPath, StandardCharsets.UTF_8);
            long exhaustedAt = 0L;
            for (String line : lines) {
                if (line != null && line.startsWith("exhaustedAtEpochMs=")) {
                    exhaustedAt = Long.parseLong(line.substring("exhaustedAtEpochMs=".length()).trim());
                }
            }
            if (exhaustedAt <= 0L) return false;
            long age = System.currentTimeMillis() - exhaustedAt;
            boolean active = age >= 0L && age < quotaCooldownMs;
            if (active) {
                return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isQuotaExhausted(int httpStatus, String body) {
        if (httpStatus != 429) return false;
        String u = body == null ? "" : body.toUpperCase(Locale.ROOT);
        return u.contains("INSUFFICIENT_QUOTA") || u.contains("EXCEEDED YOUR CURRENT QUOTA") || u.contains("BILLING");
    }

    private void markQuotaExhausted(String detail) {
        try {
            Path parent = quotaStatusPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            String text = "exhaustedAt=" + Instant.now() + System.lineSeparator()
                    + "exhaustedAtEpochMs=" + System.currentTimeMillis() + System.lineSeparator()
                    + "cooldownMs=" + quotaCooldownMs + System.lineSeparator()
                    + "detail=" + (detail == null ? "" : detail.replace("\\n", " ").replace("\\r", " ")) + System.lineSeparator()
                    + "message=OpenAI quota exhausted; nightly and live agents should use local/statistical fallbacks until credits/billing are restored." + System.lineSeparator();
            Files.writeString(quotaStatusPath, text, StandardCharsets.UTF_8);
            System.out.println("OPENAI QUOTA EXHAUSTED: disabling OpenAI calls temporarily. status=" + quotaStatusPath);
        } catch (Exception e) {
            System.out.println("OPENAI QUOTA STATUS WRITE FAILED: " + e.getMessage());
        }
    }

    private void ensureJournal() {
        try {
            Path parent = journalPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (!Files.exists(journalPath) || Files.size(journalPath) == 0) {
                try (BufferedWriter w = Files.newBufferedWriter(journalPath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
                    w.write("timestamp,agent,status,httpStatus,elapsedMs,detail");
                    w.newLine();
                }
            }
        } catch (Exception e) {
            System.out.println("OPENAI CLIENT JOURNAL INIT FAILED: " + e.getMessage());
        }
    }

    private synchronized void journal(String agent, String status, int httpStatus, long elapsedMs, String detail) {
        try {
            Path parent = journalPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter w = Files.newBufferedWriter(journalPath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
                w.write(String.join(",", csv(Instant.now().toString()), csv(agent), csv(status), Integer.toString(httpStatus), Long.toString(elapsedMs), csv(detail)));
                w.newLine();
            }
        } catch (Exception ignored) {}
    }

    public static final class OpenAiResult {
        public final boolean attempted;
        public final boolean ok;
        public final int httpStatus;
        public final long elapsedMs;
        public final String rawBody;
        public final String outputText;
        public final String error;
        private OpenAiResult(boolean attempted, boolean ok, int httpStatus, long elapsedMs, String rawBody, String outputText, String error) {
            this.attempted = attempted; this.ok = ok; this.httpStatus = httpStatus; this.elapsedMs = elapsedMs;
            this.rawBody = rawBody == null ? "" : rawBody; this.outputText = outputText == null ? "" : outputText; this.error = error == null ? "" : error;
        }
        public static OpenAiResult ok(int status, long elapsedMs, String rawBody, String outputText) { return new OpenAiResult(true, true, status, elapsedMs, rawBody, outputText, ""); }
        public static OpenAiResult skipped(String reason) { return new OpenAiResult(false, false, 0, 0, "", "", reason); }
        public static OpenAiResult error(int status, long elapsedMs, String reason, String rawBody) { return new OpenAiResult(true, false, status, elapsedMs, rawBody, "", reason); }
    }

    public static String json(String value) {
        if (value == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '"': b.append("\\\""); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 32) b.append(String.format("\\u%04x", (int)c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static String csv(String value) { String v = value == null ? "" : value; return '"' + v.replace("\"", "\"\"").replace('\n',' ').replace('\r',' ') + '"'; }
    private static String abbreviate(String value, int max) { if (value == null) return ""; return value.length() <= max ? value : value.substring(0, max) + "..."; }
    private static String env(String key, String fallback) { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : v.trim(); }
    private static boolean envBool(String key, boolean fallback) { String v = System.getenv(key); if (v == null || v.isBlank()) return fallback; String x = v.trim().toLowerCase(Locale.ROOT); return x.equals("true") || x.equals("1") || x.equals("yes") || x.equals("on"); }
    private static int envInt(String key, int fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim()); } catch(Exception e) { return fallback; } }
    private static long envLong(String key, long fallback) { try { String v = System.getenv(key); return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim()); } catch(Exception e) { return fallback; } }
}
