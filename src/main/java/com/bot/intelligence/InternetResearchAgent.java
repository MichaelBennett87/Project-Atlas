package com.bot.intelligence;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Optional after-hours internet research agent.
 *
 * It never places orders and never runs inside the live trade loop. When enabled
 * with OPENAI_API_KEY and AI_WEB_RESEARCH_ENABLED=true, it asks a reasoning model
 * with web_search access to research market regime, fresh catalysts, common
 * failure patterns, and strategy ideas. The output is written to disk for the
 * autonomous optimizer to consume as another research artifact.
 */
public class InternetResearchAgent {

    private final Path reportPath = Path.of(env("AI_INTERNET_RESEARCH_REPORT_PATH", "logs/internet_research_report.json"));
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(intEnv("AI_RESEARCH_CONNECT_TIMEOUT_SECONDS", 20)))
            .build();

    public ResearchResult runNightlyResearch() {
        long started = System.currentTimeMillis();
        if (!boolEnv("AI_WEB_RESEARCH_ENABLED", false)) {
            String message = "AI_WEB_RESEARCH_ENABLED=false; internet research skipped.";
            writeText(disabledReport(message));
            return new ResearchResult(false, false, message, System.currentTimeMillis() - started);
        }

        String apiKey = env("OPENAI_API_KEY", "");
        if (apiKey.isBlank()) {
            String message = "OPENAI_API_KEY missing; internet research skipped.";
            writeText(disabledReport(message));
            return new ResearchResult(false, false, message, System.currentTimeMillis() - started);
        }

        try {
            String body = buildOpenAiRequestBody();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(env("OPENAI_RESPONSES_URL", "https://api.openai.com/v1/responses")))
                    .timeout(Duration.ofSeconds(intEnv("AI_RESEARCH_REQUEST_TIMEOUT_SECONDS", 120)))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String responseBody = response.body() == null ? "" : response.body();
            writeText(responseBody);

            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            String message = "status=" + response.statusCode() + " reportPath=" + reportPath;
            return new ResearchResult(true, ok, message, System.currentTimeMillis() - started);
        } catch (Exception e) {
            String message = "internet research failed: " + e.getMessage();
            writeText(disabledReport(message));
            return new ResearchResult(true, false, message, System.currentTimeMillis() - started);
        }
    }

    private String buildOpenAiRequestBody() {
        String model = env("AI_RESEARCH_MODEL", "gpt-5.5");
        String prompt = env("AI_RESEARCH_PROMPT", defaultPrompt());
        int maxTokens = intEnv("AI_RESEARCH_MAX_OUTPUT_TOKENS", 4000);

        return "{"
                + "\"model\":" + json(model) + ","
                + "\"tools\":[{\"type\":\"web_search\"}],"
                + "\"max_output_tokens\":" + maxTokens + ","
                + "\"input\":" + json(prompt)
                + "}";
    }

    private String defaultPrompt() {
        return "You are the after-hours research agent for an autonomous stock-trading system. "
                + "Use web search when helpful. Produce strict JSON with keys: market_regime, overnight_risks, "
                + "high_impact_catalyst_types, small_cap_momentum_conditions, short_alpha_conditions, "
                + "sources_to_monitor, strategy_hypotheses, risk_warnings, and tomorrow_focus. "
                + "Do not recommend bypassing risk controls. Focus on evidence that can improve real-time sentiment, "
                + "news-release, technical, liquidity, float, and volatility decisions.";
    }

    private String disabledReport(String message) {
        return "{\n"
                + "  \"timestamp\": " + json(Instant.now().toString()) + ",\n"
                + "  \"enabled\": false,\n"
                + "  \"message\": " + json(message) + "\n"
                + "}\n";
    }

    private void writeText(String text) {
        try {
            Path parent = reportPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(reportPath, text == null ? "" : text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("AI INTERNET RESEARCH REPORT WRITE FAILED: " + e.getMessage());
        }
    }

    private static String json(String value) {
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

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static boolean boolEnv(String key, boolean fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : "true".equalsIgnoreCase(v.trim());
    }

    private static int intEnv(String key, int fallback) {
        try { return Integer.parseInt(env(key, Integer.toString(fallback))); }
        catch (Exception e) { return fallback; }
    }

    public static final class ResearchResult {
        public final boolean enabled;
        public final boolean success;
        public final String message;
        public final long elapsedMs;

        ResearchResult(boolean enabled, boolean success, String message, long elapsedMs) {
            this.enabled = enabled;
            this.success = success;
            this.message = message;
            this.elapsedMs = elapsedMs;
        }

        public String summary() {
            return "internetResearch=enabled:" + enabled + " success:" + success + " elapsedMs:" + elapsedMs + " message:" + message;
        }
    }
}
