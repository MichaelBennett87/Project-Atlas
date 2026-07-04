package com.bot.broker;

import com.bot.model.AccountService;

public class AlpacaAccountService implements AccountService {

    private static final long ACCOUNT_SERVICE_CACHE_TTL_MS = envLong("ACCOUNT_SERVICE_CACHE_TTL_MS", 30_000L);
    private static final long ACCOUNT_SERVICE_STALE_FALLBACK_MS = envLong("ACCOUNT_SERVICE_STALE_FALLBACK_MS", 10 * 60_000L);

    private final AlpacaBroker broker;
    private volatile AlpacaBroker.AccountSnapshot cachedAccount;
    private volatile long cachedAtMs = 0L;

    public AlpacaAccountService(AlpacaBroker broker) {
        this.broker = broker;
    }

    @Override
    public double equity() {
        AlpacaBroker.AccountSnapshot account = safeAccount();
        return account == null ? 0.0 : account.getEquity();
    }

    @Override
    public double buyingPower() {
        AlpacaBroker.AccountSnapshot account = safeAccount();
        return account == null ? 0.0 : account.getBuyingPower();
    }

    @Override
    public double dailyDrawdown() {
        AlpacaBroker.AccountSnapshot account = safeAccount();
        if (account == null) {
            return 0.0;
        }

        double equity = account.getEquity();
        double lastEquity = account.getLastEquity();

        if (lastEquity == 0) return 0;

        return (equity - lastEquity) / lastEquity;
    }

    @Override
    public double lastPrice(String ticker) {
        try {
            return broker.getPrice(ticker);
        } catch (Exception e) {
            System.out.println("ACCOUNT SERVICE PRICE FALLBACK: ticker=" + ticker + " detail=" + rootCauseMessage(e));
            return 0.0;
        }
    }

    private AlpacaBroker.AccountSnapshot safeAccount() {
        long now = System.currentTimeMillis();
        AlpacaBroker.AccountSnapshot local = cachedAccount;
        if (local != null && now - cachedAtMs <= ACCOUNT_SERVICE_CACHE_TTL_MS) {
            return local;
        }

        try {
            AlpacaBroker.AccountSnapshot fresh = broker.getAccount();
            if (fresh != null) {
                cachedAccount = fresh;
                cachedAtMs = now;
                return fresh;
            }
        } catch (Exception e) {
            local = cachedAccount;
            if (local != null && now - cachedAtMs <= ACCOUNT_SERVICE_STALE_FALLBACK_MS) {
                System.out.println("ACCOUNT SERVICE STALE FALLBACK: ageMs=" + (now - cachedAtMs) + " detail=" + rootCauseMessage(e));
                return local;
            }
            System.out.println("ACCOUNT SERVICE UNAVAILABLE: " + rootCauseMessage(e));
        }

        return local;
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable current = t;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null || current.getMessage() == null ? String.valueOf(t) : current.getMessage();
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}