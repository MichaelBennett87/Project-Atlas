package com.bot.strategy;

import com.bot.model.PendingSignal;
import com.bot.risk.MarketHoursService;

import java.util.List;

public class PendingSignalProcessor {

    private final PendingSignalQueue queue;
    private final NewsMomentumStrategy strategy;
    private final MarketConfirmationFilter confirmation;
    private final MarketHoursService marketHours;

    public PendingSignalProcessor(
            PendingSignalQueue queue,
            NewsMomentumStrategy strategy,
            MarketConfirmationFilter confirmation
    ) {
        this.queue = queue;
        this.strategy = strategy;
        this.confirmation = confirmation;
        this.marketHours = new MarketHoursService();
    }

    public void process() {
        if (!marketHours.isExtendedMarketOpenNow()) {
            System.out.println(
                    "Pending signal processing skipped: not in tradable extended-hours window. session=" +
                            marketHours.currentSessionName()
            );
            return;
        }

        List<PendingSignal> activeSignals = queue.activeSignals();

        for (PendingSignal signal : activeSignals) {
            String ticker = signal.opportunity.news.getTicker();

            System.out.println(
                    "CHECKING PENDING SIGNAL: " +
                            ticker +
                            " score=" +
                            signal.opportunity.finalScore
            );

            if (!confirmation.confirm(ticker)) {
                System.out.println(
                        "PENDING SIGNAL WAITING: market has not confirmed " +
                                ticker
                );
                continue;
            }

            try {
                strategy.onNews(signal.opportunity.news);
                queue.remove(signal);

            } catch (Exception e) {
                System.err.println(
                        "Failed processing pending signal " +
                                ticker +
                                ": " +
                                e.getMessage()
                );
            }
        }
    }
}