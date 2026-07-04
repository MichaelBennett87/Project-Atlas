package com.bot.governance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Final safety gate for autonomous strategy/code evolution.
 *
 * This class is intentionally deterministic. It does not decide what is
 * profitable. It only decides whether an autonomous change is allowed to be
 * considered for promotion without weakening account-survival controls.
 */
public final class PromotionGatekeeper {

    public GateResult inspectCandidateTree(Path candidateRoot) {
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (candidateRoot == null) {
            failures.add("candidateRoot=null");
            return writeAndReturn(candidateRoot, failures, warnings);
        }

        if (!Files.exists(candidateRoot)) {
            warnings.add("candidateRoot does not exist yet: " + candidateRoot);
            return writeAndReturn(candidateRoot, failures, warnings);
        }

        try {
            Files.walk(candidateRoot)
                    .filter(Files::isRegularFile)
                    .forEach(path -> inspectFile(candidateRoot, path, failures, warnings));
        } catch (IOException e) {
            failures.add("Failed to inspect candidate tree: " + e.getMessage());
        }

        return writeAndReturn(candidateRoot, failures, warnings);
    }

    private void inspectFile(Path root, Path file, List<String> failures, List<String> warnings) {
        Path relative = root.relativize(file);
        if (!ImmutableSafetyRules.mayAutonomouslyEdit(relative)) {
            failures.add("Forbidden autonomous edit target: " + relative);
            return;
        }

        String name = file.getFileName().toString().toLowerCase();
        if (!name.endsWith(".java") && !name.endsWith(".properties") && !name.endsWith(".json") && !name.endsWith(".txt")) {
            warnings.add("Unrecognized candidate artifact type: " + relative);
            return;
        }

        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (ImmutableSafetyRules.isForbiddenSourceChange(text)) {
                failures.add("Candidate contains forbidden safety-bypass language: " + relative);
            }
        } catch (Exception e) {
            warnings.add("Could not read candidate artifact " + relative + ": " + e.getMessage());
        }
    }

    private GateResult writeAndReturn(Path candidateRoot, List<String> failures, List<String> warnings) {
        boolean passed = failures.isEmpty();
        GateResult result = new GateResult(passed, candidateRoot, failures, warnings);
        writeReport(result);
        return result;
    }

    private void writeReport(GateResult result) {
        StringBuilder b = new StringBuilder();
        b.append("AUTONOMOUS PROMOTION GATEKEEPER REPORT\n");
        b.append("timestamp=").append(Instant.now()).append('\n');
        b.append("passed=").append(result.passed).append('\n');
        b.append("candidateRoot=").append(result.candidateRoot).append('\n');
        b.append("failures=\n");
        if (result.failures.isEmpty()) b.append("- none\n");
        for (String failure : result.failures) b.append("- ").append(failure).append('\n');
        b.append("warnings=\n");
        if (result.warnings.isEmpty()) b.append("- none\n");
        for (String warning : result.warnings) b.append("- ").append(warning).append('\n');

        try {
            Path report = ImmutableSafetyRules.autonomousReportPath();
            Path parent = report.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(report, b.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("PROMOTION GATEKEEPER REPORT WRITE FAILED: " + e.getMessage());
        }
    }

    public static final class GateResult {
        public final boolean passed;
        public final Path candidateRoot;
        public final List<String> failures;
        public final List<String> warnings;

        GateResult(boolean passed, Path candidateRoot, List<String> failures, List<String> warnings) {
            this.passed = passed;
            this.candidateRoot = candidateRoot;
            this.failures = List.copyOf(failures);
            this.warnings = List.copyOf(warnings);
        }

        public String summary() {
            return "gatekeeper=passed:" + passed + " failures=" + failures.size() + " warnings=" + warnings.size();
        }
    }
}
