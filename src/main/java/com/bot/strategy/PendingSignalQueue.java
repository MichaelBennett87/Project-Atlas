package com.bot.strategy;

import com.bot.model.NewsOpportunity;
import com.bot.model.PendingSignal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class PendingSignalQueue {

    private final List<PendingSignal> pendingSignals = new ArrayList<>();
    private final long ttlMillis;

    public PendingSignalQueue(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    public synchronized void add(NewsOpportunity opportunity) {
        long now = System.currentTimeMillis();

        PendingSignal signal = new PendingSignal(
                opportunity,
                now,
                now + ttlMillis
        );

        pendingSignals.add(signal);

        System.out.println(
                "PENDING SIGNAL ADDED: " +
                        opportunity.news.getTicker() +
                        " score=" +
                        opportunity.finalScore +
                        " expiresInMs=" +
                        ttlMillis
        );
    }

    public synchronized List<PendingSignal> activeSignals() {
        removeExpired();

        return new ArrayList<>(pendingSignals);
    }

    public synchronized void remove(PendingSignal signal) {
        pendingSignals.remove(signal);
    }

    private void removeExpired() {
        Iterator<PendingSignal> iterator = pendingSignals.iterator();

        while (iterator.hasNext()) {
            PendingSignal signal = iterator.next();

            if (signal.isExpired()) {
                System.out.println(
                        "PENDING SIGNAL EXPIRED: " +
                                signal.opportunity.news.getTicker() +
                                " " +
                                signal.opportunity.news.getHeadline()
                );

                iterator.remove();
            }
        }
    }
}