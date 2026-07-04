package com.bot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small append-only supervisor journal used by ScheduledAutonomousTradingSupervisorMain.
 *
 * The journal is intentionally dependency-free so the always-on supervisor can
 * record phase transitions even when the trading engine or nightly evolution
 * process fails to start. It gives the autonomous system an auditable record of
 * what was run, when it ran, and which policy/evolution files were active.
 */
public final class AutonomousSupervisorJournal {
    private final Path path;

    public AutonomousSupervisorJournal() {
        this(Path.of(System.getenv().getOrDefault("AUTONOMOUS_SUPERVISOR_JOURNAL", "logs/autonomous_supervisor_journal.csv")));
    }

    public AutonomousSupervisorJournal(Path path) {
        this.path = path;
        ensureHeader();
    }

    public void event(String phase, String status, Map<String, String> fields) {
        Map<String, String> safe = new LinkedHashMap<>();
        if (fields != null) {
            safe.putAll(fields);
        }
        StringBuilder line = new StringBuilder();
        line.append(escape(Instant.now().toString())).append(',')
                .append(escape(phase)).append(',')
                .append(escape(status)).append(',')
                .append(escape(flatten(safe))).append(System.lineSeparator());
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, line.toString(), StandardCharsets.UTF_8,
                    Files.exists(path)
                            ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
        } catch (IOException e) {
            System.err.println("AUTONOMOUS SUPERVISOR JOURNAL ERROR: " + e.getMessage());
        }
    }

    public void event(String phase, String status, String key, String value) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(key, value);
        event(phase, status, fields);
    }

    private void ensureHeader() {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(path)) {
                Files.writeString(path, "timestamp,phase,status,fields" + System.lineSeparator(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.err.println("AUTONOMOUS SUPERVISOR JOURNAL INIT ERROR: " + e.getMessage());
        }
    }

    private static String flatten(Map<String, String> fields) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (!first) {
                out.append(';');
            }
            out.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return out.toString();
    }

    private static String escape(String value) {
        String safe = value == null ? "" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
