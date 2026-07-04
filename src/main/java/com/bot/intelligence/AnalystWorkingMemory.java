package com.bot.intelligence;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Persistent, bounded per-agent notes so analysts can remember context during the session. */
public final class AnalystWorkingMemory {
    private static final AnalystWorkingMemory INSTANCE = new AnalystWorkingMemory();
    private final Map<String, Deque<MemoryItem>> memory = new LinkedHashMap<>();
    private final int maxItems = Math.max(10, intEnv("ANALYST_WORKING_MEMORY_MAX_ITEMS", 200));
    private final Path journal = Path.of(env("ANALYST_WORKING_MEMORY_JOURNAL", "logs/analyst_working_memory.csv"));

    public static AnalystWorkingMemory getInstance() { return INSTANCE; }

    public synchronized void remember(String agent, String ticker, String observation, double confidence) {
        String key = norm(agent) + ":" + norm(ticker);
        Deque<MemoryItem> q = memory.computeIfAbsent(key, k -> new ArrayDeque<>());
        q.addLast(new MemoryItem(Instant.now().toString(), norm(agent), norm(ticker), observation == null ? "" : observation, confidence));
        while (q.size() > maxItems) q.removeFirst();
        append(q.peekLast());
    }

    public synchronized String recallSummary(String agent, String ticker, int limit) {
        Deque<MemoryItem> q = memory.get(norm(agent) + ":" + norm(ticker));
        if (q == null || q.isEmpty()) return "no prior observations";
        StringBuilder sb = new StringBuilder(); int c = 0;
        java.util.Iterator<MemoryItem> it = q.descendingIterator();
        while (it.hasNext() && c++ < limit) { MemoryItem item = it.next(); if (sb.length() > 0) sb.append(" | "); sb.append(item.observation); }
        return sb.toString();
    }

    public Result snapshot() {
        int keys = memory.size(); int items = 0; for (Deque<MemoryItem> q : memory.values()) items += q.size();
        System.out.println("ANALYST WORKING MEMORY READY: keys=" + keys + " items=" + items + " journal=" + journal);
        return new Result(keys, items, journal.toString());
    }

    private void append(MemoryItem item) {
        if (item == null) return;
        try { Path parent=journal.getParent(); if(parent!=null) Files.createDirectories(parent); boolean exists=Files.exists(journal)&&Files.size(journal)>0; try(BufferedWriter w=Files.newBufferedWriter(journal, StandardOpenOption.CREATE, StandardOpenOption.APPEND)){ if(!exists){w.write("time,agent,ticker,confidence,observation"); w.newLine();} w.write(String.join(",", csv(item.time), csv(item.agent), csv(item.ticker), d(item.confidence), csv(item.observation))); w.newLine(); }} catch(Exception e){ System.out.println("ANALYST WORKING MEMORY JOURNAL FAILED: " + e.getMessage()); }
    }
    private static String norm(String s){return s==null?"":s.trim().toUpperCase(Locale.ROOT);}
    private static String env(String k,String f){String v=System.getenv(k);return v==null||v.isBlank()?f:v.trim();}
    private static int intEnv(String k,int f){try{String v=System.getenv(k);return v==null||v.isBlank()?f:Integer.parseInt(v.trim());}catch(Exception e){return f;}}
    private static String d(double v){return String.format(Locale.US,"%.6f",Double.isFinite(v)?v:0);}
    private static String csv(String v){String s=v==null?"":v.replace("\n"," ").replace("\r"," ");return '"'+s.replace("\"","\"\"")+'"';}
    private static final class MemoryItem { final String time,agent,ticker,observation; final double confidence; MemoryItem(String time,String agent,String ticker,String observation,double confidence){this.time=time;this.agent=agent;this.ticker=ticker;this.observation=observation;this.confidence=confidence;} }
    public static final class Result { public final int keys,items; public final String journal; Result(int k,int i,String j){keys=k;items=i;journal=j;} }
}
