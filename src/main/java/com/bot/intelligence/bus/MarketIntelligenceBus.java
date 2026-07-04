package com.bot.intelligence.bus;

import com.bot.model.NewsEvent;
import com.bot.intelligence.DataSourceReliabilityService;
import com.bot.intelligence.EvidenceFusionEngine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class MarketIntelligenceBus {
    private static final MarketIntelligenceBus INSTANCE = new MarketIntelligenceBus();

    private final Map<String, ProviderHealth> providerHealth = new ConcurrentHashMap<>();
    private final Map<String, Deque<MarketIntelligenceSignal>> recentByTicker = new ConcurrentHashMap<>();
    private final List<Consumer<MarketIntelligenceSignal>> signalConsumers = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> fingerprints = Collections.synchronizedSet(new LinkedHashSet<>());
    private final MarketIntelligenceJournal journal = new MarketIntelligenceJournal();
    private final int maxSignalsPerTicker;
    private final int maxFingerprintCache;
    private volatile Consumer<NewsEvent> newsDownstream;
    private volatile boolean started = false;

    private MarketIntelligenceBus() {
        this.maxSignalsPerTicker = envInt("MARKET_INTELLIGENCE_BUS_MAX_SIGNALS_PER_TICKER", 200);
        this.maxFingerprintCache = envInt("MARKET_INTELLIGENCE_BUS_DEDUP_CACHE_MAX", 25_000);
    }

    public static MarketIntelligenceBus getInstance() {
        return INSTANCE;
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        System.out.println("MARKET INTELLIGENCE BUS STARTED: maxSignalsPerTicker=" + maxSignalsPerTicker +
                " dedupCache=" + maxFingerprintCache +
                " journal=" + System.getenv().getOrDefault("MARKET_INTELLIGENCE_BUS_JOURNAL", "logs/market_intelligence_bus.csv"));
    }

    public void setNewsDownstream(Consumer<NewsEvent> newsDownstream) {
        this.newsDownstream = newsDownstream;
    }

    public void registerSignalConsumer(Consumer<MarketIntelligenceSignal> consumer) {
        if (consumer != null) signalConsumers.add(consumer);
    }

    public void publishNews(NewsEvent news) {
        if (news == null) return;
        MarketIntelligenceSignal signal = MarketIntelligenceSignal.fromNews(news);
        publishSignal(signal);
        Consumer<NewsEvent> downstream = newsDownstream;
        if (downstream != null) downstream.accept(news);
    }

    public void publishSignal(MarketIntelligenceSignal signal) {
        if (signal == null) return;
        start();
        String provider = signal.getProvider();
        ProviderHealth health = providerHealth.computeIfAbsent(provider, ProviderHealth::new);
        boolean duplicate = rememberFingerprint(signal.fingerprint());
        health.recordSignal(duplicate);
        DataSourceReliabilityService.getInstance().observeSignal(signal, duplicate);
        EvidenceFusionEngine.getInstance().observeSignal(signal, duplicate);
        journal.record(signal, duplicate);
        if (duplicate) return;
        maybeRouteSignalAsNews(signal);
        if (signal.getTicker() != null && !signal.getTicker().isBlank()) {
            Deque<MarketIntelligenceSignal> deque = recentByTicker.computeIfAbsent(signal.getTicker(), t -> new ArrayDeque<>());
            synchronized (deque) {
                deque.addLast(signal);
                while (deque.size() > maxSignalsPerTicker) deque.removeFirst();
            }
        }
        synchronized (signalConsumers) {
            for (Consumer<MarketIntelligenceSignal> consumer : signalConsumers) {
                try {
                    consumer.accept(signal);
                } catch (Exception e) {
                    System.out.println("MARKET INTELLIGENCE BUS CONSUMER WARNING: " + e.getMessage());
                }
            }
        }
    }

    private void maybeRouteSignalAsNews(MarketIntelligenceSignal signal) {
        if (signal == null) return;
        Consumer<NewsEvent> downstream = newsDownstream;
        if (downstream == null) return;
        boolean isNewsLike = signal.getType() == MarketIntelligenceSignalType.NEWS || signal.getType() == MarketIntelligenceSignalType.PRESS_RELEASE;
        if (!isNewsLike) return;
        String route = signal.getMetadata() == null ? "" : signal.getMetadata().getOrDefault("routeToNews", "false");
        if (!"true".equalsIgnoreCase(route) && !"1".equals(route)) return;
        try {
            downstream.accept(signal.toNewsEvent());
        } catch (Exception e) {
            System.out.println("MARKET INTELLIGENCE BUS NEWS ROUTE WARNING: provider=" + signal.getProvider() + " ticker=" + signal.getTicker() + " error=" + e.getMessage());
        }
    }

    public List<MarketIntelligenceSignal> recentSignals(String ticker, int limit) {
        if (ticker == null || ticker.isBlank()) return Collections.emptyList();
        Deque<MarketIntelligenceSignal> deque = recentByTicker.get(ticker.trim().toUpperCase());
        if (deque == null) return Collections.emptyList();
        List<MarketIntelligenceSignal> copy;
        synchronized (deque) {
            copy = new ArrayList<>(deque);
        }
        if (limit <= 0 || copy.size() <= limit) return copy;
        return new ArrayList<>(copy.subList(copy.size() - limit, copy.size()));
    }

    public double corroborationScore(String ticker, long lookbackMs) {
        List<MarketIntelligenceSignal> signals = recentSignals(ticker, 200);
        if (signals.isEmpty()) return 0.0;
        long cutoff = System.currentTimeMillis() - Math.max(1_000L, lookbackMs);
        Set<String> providers = new LinkedHashSet<>();
        double priority = 0.0;
        for (MarketIntelligenceSignal signal : signals) {
            if (signal.getReceivedAtMs() < cutoff) continue;
            providers.add(signal.getProvider());
            priority = Math.max(priority, signal.getPriority());
        }
        double providerScore = Math.min(1.0, providers.size() / 4.0);
        return Math.max(providerScore, priority);
    }

    public Map<String, ProviderHealth> providerHealthSnapshot() {
        return new LinkedHashMap<>(providerHealth);
    }

    public void recordProviderStatus(String provider, String status) {
        providerHealth.computeIfAbsent(provider == null ? "UNKNOWN" : provider, ProviderHealth::new).recordStatus(status);
    }

    public void stop() {
        started = false;
        System.out.println("MARKET INTELLIGENCE BUS STOPPED: providers=" + providerHealth.size());
    }

    private boolean rememberFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) return false;
        synchronized (fingerprints) {
            boolean duplicate = fingerprints.contains(fingerprint);
            fingerprints.add(fingerprint);
            while (fingerprints.size() > maxFingerprintCache) {
                java.util.Iterator<String> iterator = fingerprints.iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
            return duplicate;
        }
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
