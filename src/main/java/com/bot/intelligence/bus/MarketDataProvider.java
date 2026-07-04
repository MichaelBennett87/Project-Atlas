package com.bot.intelligence.bus;

import java.util.function.Consumer;

public interface MarketDataProvider {
    String name();
    boolean enabled();
    void start(Consumer<MarketIntelligenceSignal> signalConsumer);
    void stop();
}
