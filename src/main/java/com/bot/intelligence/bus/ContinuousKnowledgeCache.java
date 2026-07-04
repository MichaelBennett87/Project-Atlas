package com.bot.intelligence.bus;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Tiny local cache of research facts so agents reuse known answers before asking Polygon again. */
public final class ContinuousKnowledgeCache {
    private static final ContinuousKnowledgeCache INSTANCE = new ContinuousKnowledgeCache();
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final Path journal = Path.of(env("CONTINUOUS_KNOWLEDGE_CACHE_JOURNAL", "logs/continuous_knowledge_cache.csv"));
    private ContinuousKnowledgeCache() {}
    public static ContinuousKnowledgeCache getInstance() { return INSTANCE; }

    public void put(String symbol, String topic, String value, double confidence) {
        String key = key(symbol, topic);
        Entry e = new Entry(symbol, topic, value, confidence, System.currentTimeMillis());
        cache.put(key, e);
        try {
            Path parent = journal.getParent(); if (parent != null) Files.createDirectories(parent);
            boolean writeHeader = !Files.exists(journal) || Files.size(journal) == 0L;
            try (BufferedWriter w = Files.newBufferedWriter(journal, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (writeHeader) w.write("timestamp,symbol,topic,confidence,value\n");
                w.write(q(Instant.ofEpochMilli(e.updatedMs).toString()) + "," + q(e.symbol) + "," + q(e.topic) + "," + String.format(Locale.US, "%.4f", e.confidence) + "," + q(e.value));
                w.newLine();
            }
        } catch (Exception ignored) {}
    }

    public boolean fresh(String symbol, String topic, long maxAgeMs) {
        Entry e = cache.get(key(symbol, topic));
        return e != null && System.currentTimeMillis() - e.updatedMs <= Math.max(1_000L, maxAgeMs);
    }

    private static String key(String s, String t) { return norm(s) + ":" + (t == null ? "" : t.trim().toUpperCase(Locale.ROOT)); }
    private static String norm(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]", ""); }
    private static String q(String s) { return '"' + (s == null ? "" : s.replace("\"", "\"\"")) + '"'; }
    private static String env(String k, String f) { String v = System.getenv(k); return v == null || v.isBlank() ? f : v.trim(); }
    private static final class Entry { final String symbol, topic, value; final double confidence; final long updatedMs; Entry(String s,String t,String v,double c,long m){symbol=s;topic=t;value=v;confidence=c;updatedMs=m;} }
}
