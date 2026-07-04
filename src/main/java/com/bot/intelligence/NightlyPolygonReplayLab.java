package com.bot.intelligence;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Nightly Polygon premium replay cache builder for significant symbols. */
public final class NightlyPolygonReplayLab {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String key = env("POLYGON_API_KEY", "");
    private final ZoneId zone = ZoneId.of(env("MARKET_TIME_ZONE", "America/New_York"));
    private final Path cacheDir = Path.of(env("NIGHTLY_POLYGON_REPLAY_CACHE_DIR", "logs/polygon_replay_lab"));
    private final Path journal = Path.of(env("NIGHTLY_POLYGON_REPLAY_JOURNAL", "logs/nightly_polygon_replay_lab.csv"));

    public Result run() {
        if (key.isBlank() || !boolEnv("NIGHTLY_POLYGON_REPLAY_ENABLED", true)) {
            System.out.println("NIGHTLY POLYGON REPLAY LAB SKIPPED: enabled=false or POLYGON_API_KEY missing");
            return new Result(0,0,0,"disabled");
        }
        Set<String> symbols = selectSymbols();
        int max = intEnv("NIGHTLY_POLYGON_REPLAY_MAX_SYMBOLS", 600);
        int requested = 0, cached = 0, errors = 0;
        LocalDate end = LocalDate.now(zone);
        LocalDate start = end.minusDays(intEnv("NIGHTLY_POLYGON_REPLAY_LOOKBACK_DAYS", 1));
        for (String symbol : symbols) {
            if (requested >= max) break;
            requested++;
            try {
                String endpoint = "/v2/aggs/ticker/" + enc(symbol) + "/range/1/minute/" + start + "/" + end;
                String url = "https://api.polygon.io" + endpoint + "?adjusted=true&sort=asc&limit=" + intEnv("NIGHTLY_POLYGON_REPLAY_BAR_LIMIT", 50000) + "&apiKey=" + enc(key);
                long t0 = System.currentTimeMillis();
                HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().header("accept", "application/json").build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                long elapsed = System.currentTimeMillis() - t0;
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    Files.createDirectories(cacheDir);
                    Path out = cacheDir.resolve(symbol + "_1min_" + start + "_" + end + ".json");
                    Files.writeString(out, response.body() == null ? "{}" : response.body(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    cached++;
                    journal(symbol, true, elapsed, "cached=" + out);
                } else {
                    errors++;
                    journal(symbol, false, elapsed, "HTTP " + response.statusCode());
                }
            } catch (Exception e) {
                errors++;
                journal(symbol, false, 0, e.getMessage());
            }
        }
        Result result = new Result(symbols.size(), cached, errors, cacheDir.toString());
        System.out.println("NIGHTLY POLYGON REPLAY LAB COMPLETE: " + result.summary());
        return result;
    }

    private Set<String> selectSymbols() {
        Set<String> out = new LinkedHashSet<>();
        addCsv(out, env("NIGHTLY_POLYGON_REPLAY_SYMBOLS", ""));
        readTickerColumn(out, Path.of("logs/unified_candidate_scores.csv"));
        readTickerColumn(out, Path.of("logs/market_knowledge_database.csv"));
        readTickerColumn(out, Path.of("logs/opportunity_memory.csv"));
        readTickerColumn(out, Path.of("logs/stock_memory.csv"));
        if (out.isEmpty()) addCsv(out, "SPY,QQQ,IWM,AAPL,NVDA,TSLA,META,AMD,MSFT,AMZN");
        return out;
    }

    private void journal(String symbol, boolean success, long elapsedMs, String message) {
        try {
            Path parent = journal.getParent(); if (parent != null) Files.createDirectories(parent);
            boolean newFile = !Files.exists(journal) || Files.size(journal) == 0;
            try (BufferedWriter w = Files.newBufferedWriter(journal, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (newFile) { w.write("timestamp,symbol,success,elapsedMs,message"); w.newLine(); }
                w.write(String.join(",", q(java.time.Instant.now().toString()), q(symbol), String.valueOf(success), String.valueOf(elapsedMs), q(message))); w.newLine();
            }
        } catch (Exception ignored) {}
    }

    private static void readTickerColumn(Set<String> out, Path path) { if(!Files.exists(path))return; try(BufferedReader r=Files.newBufferedReader(path)){String h=r.readLine(); int idx=findTickerIndex(h); String line; while((line=r.readLine())!=null){List<String> c=parse(line); if(idx>=0&&idx<c.size()) { String t=norm(c.get(idx)); if(!t.isBlank()) out.add(t); }}}catch(Exception ignored){} }
    private static int findTickerIndex(String h){List<String> c=parse(h); for(int i=0;i<c.size();i++){String x=c.get(i).trim().toLowerCase(Locale.ROOT); if(x.equals("ticker")||x.equals("symbol"))return i;} return 0;}
    private static void addCsv(Set<String> out,String csv){if(csv==null)return; for(String p:csv.split(",")){String t=norm(p); if(!t.isBlank())out.add(t);}}
    private static String norm(String s){return s==null?"":s.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9._-]","");}
    private static String env(String k,String f){String v=System.getenv(k);return v==null||v.isBlank()?f:v.trim();}
    private static int intEnv(String k,int f){try{String v=System.getenv(k);return v==null||v.isBlank()?f:Integer.parseInt(v.trim());}catch(Exception e){return f;}}
    private static boolean boolEnv(String k,boolean f){String v=System.getenv(k);return v==null||v.isBlank()?f:"true".equalsIgnoreCase(v.trim())||"1".equals(v.trim());}
    private static String enc(String s){return URLEncoder.encode(s==null?"":s, StandardCharsets.UTF_8).replace("+","%20");}
    private static String q(String s){String v=s==null?"":s;return '"'+v.replace("\"","\"\"")+'"';}
    private static List<String> parse(String line){List<String> out=new ArrayList<>(); if(line==null)return out; StringBuilder cur=new StringBuilder(); boolean q=false; for(int i=0;i<line.length();i++){char ch=line.charAt(i); if(q){if(ch=='"'){if(i+1<line.length()&&line.charAt(i+1)=='"'){cur.append('"');i++;}else q=false;}else cur.append(ch);}else{if(ch==','){out.add(cur.toString());cur.setLength(0);}else if(ch=='"')q=true;else cur.append(ch);}} out.add(cur.toString()); return out;}
    public static final class Result { public final int selected,cached,errors; public final String cache; Result(int s,int c,int e,String ca){selected=s;cached=c;errors=e;cache=ca;} public String summary(){return "selected="+selected+" cached="+cached+" errors="+errors+" cache="+cache;} }
}
