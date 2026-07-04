package com.bot.strategy;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class NewsDeduplicator {

    private final Set<String> seenNewsIds = ConcurrentHashMap.newKeySet();

    public boolean isNew(String newsId) {
        if (newsId == null || newsId.isBlank()) {
            return true;
        }

        return seenNewsIds.add(newsId);
    }
}