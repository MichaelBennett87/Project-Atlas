package com.bot.intelligence;

import com.bot.governance.ImmutableSafetyRules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline codebase review agent.
 *
 * This deterministic first-pass reviewer gives the AI optimizer full visibility
 * into the current source tree without letting it change files during live
 * trading. The generated report is intentionally plain text so LLM/research
 * layers can consume it easily.
 */
public class CodebaseReviewAgent {

    private final Path sourceRoot = Path.of(env("AI_CODEBASE_ROOT", "."));
    private final Path reportPath = Path.of(env("AI_CODEBASE_REVIEW_REPORT_PATH", "logs/codebase_review_report.txt"));

    public ReviewResult runReview() {
        long started = System.currentTimeMillis();
        List<String> findings = new ArrayList<>();
        Counter counter = new Counter();

        try {
            Files.walk(sourceRoot)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> inspectJavaFile(path, findings, counter));
        } catch (IOException e) {
            findings.add("CODEBASE_REVIEW_ERROR: " + e.getMessage());
        }

        writeReport(findings, counter, System.currentTimeMillis() - started);
        return new ReviewResult(counter.files, findings.size(), reportPath.toString(), System.currentTimeMillis() - started);
    }

    private void inspectJavaFile(Path path, List<String> findings, Counter counter) {
        counter.files++;
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            counter.lines += text.lines().count();

            if (!ImmutableSafetyRules.mayAutonomouslyEdit(path)) {
                findings.add("PROTECTED_FILE: " + path);
            }
            if (text.contains("while (true)")) {
                findings.add("LOOP_REVIEW: " + path + " contains while(true); verify shutdown/interrupt behavior.");
            }
            if (text.contains("System.exit(")) {
                findings.add("PROCESS_EXIT_REVIEW: " + path + " calls System.exit; verify supervisor expectations.");
            }
            if (text.contains("TODO") || text.contains("FIXME")) {
                findings.add("TODO_REVIEW: " + path + " contains TODO/FIXME markers.");
            }
            if (text.contains("catch (Exception e)") && !text.contains("e.printStackTrace")) {
                findings.add("ERROR_VISIBILITY_REVIEW: " + path + " catches Exception; verify logging is sufficient.");
            }
            if (ImmutableSafetyRules.isForbiddenSourceChange(text)) {
                findings.add("SAFETY_LANGUAGE_REVIEW: " + path + " contains possible risk-bypass language.");
            }
        } catch (Exception e) {
            findings.add("READ_ERROR: " + path + " error=" + e.getMessage());
        }
    }

    private void writeReport(List<String> findings, Counter counter, long elapsedMs) {
        StringBuilder b = new StringBuilder();
        b.append("AUTONOMOUS CODEBASE REVIEW REPORT\n");
        b.append("timestamp=").append(Instant.now()).append('\n');
        b.append("sourceRoot=").append(sourceRoot).append('\n');
        b.append("filesScanned=").append(counter.files).append('\n');
        b.append("linesScanned=").append(counter.lines).append('\n');
        b.append("findings=").append(findings.size()).append('\n');
        b.append("elapsedMs=").append(elapsedMs).append("\n\n");
        if (findings.isEmpty()) {
            b.append("- none\n");
        } else {
            for (String finding : findings) b.append("- ").append(finding).append('\n');
        }

        try {
            Path parent = reportPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(reportPath, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("CODEBASE REVIEW REPORT WRITE FAILED: " + e.getMessage());
        }
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static final class Counter {
        int files;
        long lines;
    }

    public static final class ReviewResult {
        public final int filesScanned;
        public final int findings;
        public final String reportPath;
        public final long elapsedMs;

        ReviewResult(int filesScanned, int findings, String reportPath, long elapsedMs) {
            this.filesScanned = filesScanned;
            this.findings = findings;
            this.reportPath = reportPath;
            this.elapsedMs = elapsedMs;
        }

        public String summary() {
            return "codebaseReview=files:" + filesScanned + " findings:" + findings + " report:" + reportPath + " elapsedMs:" + elapsedMs;
        }
    }
}
