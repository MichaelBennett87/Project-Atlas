package com.bot.strategy;

import com.bot.model.AutoBuyCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AutoBuyList {

    private final List<AutoBuyCandidate> candidates = new ArrayList<>();

    public synchronized void add(AutoBuyCandidate candidate) {
        if (candidate == null) {
            return;
        }

        candidates.add(candidate);

        candidates.sort(
                Comparator.comparingDouble((AutoBuyCandidate c) -> c.autoBuyScore)
                        .reversed()
        );

        System.out.println(
                "AUTO-BUY CANDIDATE ADDED: " +
                        candidate
        );
    }

    public synchronized List<AutoBuyCandidate> all() {
        return new ArrayList<>(candidates);
    }

    public synchronized int size() {
        return candidates.size();
    }

    public synchronized void clear() {
        candidates.clear();
    }
}