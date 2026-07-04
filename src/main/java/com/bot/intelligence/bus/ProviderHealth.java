package com.bot.intelligence.bus;

public class ProviderHealth {
    private final String provider;
    private volatile long receivedSignals;
    private volatile long duplicateSignals;
    private volatile long lastReceivedAtMs;
    private volatile String lastStatus;

    public ProviderHealth(String provider) {
        this.provider = provider == null ? "UNKNOWN" : provider;
        this.lastStatus = "INITIALIZED";
    }

    public synchronized void recordSignal(boolean duplicate) {
        if (duplicate) {
            duplicateSignals++;
        } else {
            receivedSignals++;
            lastReceivedAtMs = System.currentTimeMillis();
        }
        lastStatus = duplicate ? "DUPLICATE" : "ACTIVE";
    }

    public synchronized void recordStatus(String status) {
        lastStatus = status == null ? "UNKNOWN" : status;
    }

    public String getProvider() { return provider; }
    public long getReceivedSignals() { return receivedSignals; }
    public long getDuplicateSignals() { return duplicateSignals; }
    public long getLastReceivedAtMs() { return lastReceivedAtMs; }
    public String getLastStatus() { return lastStatus; }

    public String compactSummary() {
        return provider + " status=" + lastStatus + " signals=" + receivedSignals + " duplicates=" + duplicateSignals;
    }
}
