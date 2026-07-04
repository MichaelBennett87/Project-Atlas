package com.bot.journal;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;

public class TradeJournal {

    private static final String JOURNAL_FOLDER = "logs";
    private static final String JOURNAL_FILE = "trade-journal.csv";

    private final Path journalPath;

    public TradeJournal() {
        try {
            Path folderPath = Paths.get(JOURNAL_FOLDER);

            if (!Files.exists(folderPath)) {
                Files.createDirectories(folderPath);
            }

            this.journalPath = folderPath.resolve(JOURNAL_FILE);

            if (!Files.exists(journalPath)) {
                writeHeader();
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize trade journal.", e);
        }
    }

    public synchronized void record(
            String side,
            String ticker,
            int qty,
            String details
    ) {
        String line = String.join(",",
                Instant.now().toString(),
                clean(side),
                clean(ticker),
                String.valueOf(qty),
                clean(details)
        );

        try (BufferedWriter writer = Files.newBufferedWriter(
                journalPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        )) {
            writer.write(line);
            writer.newLine();

        } catch (IOException e) {
            throw new RuntimeException("Failed to write trade journal entry.", e);
        }

        System.out.println("[JOURNAL] " + line);
    }

    private void writeHeader() throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(
                journalPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        )) {
            writer.write("timestamp,side,ticker,qty,details");
            writer.newLine();
        }
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace(",", " ")
                .replace("\n", " ")
                .replace("\r", " ")
                .trim();
    }
}