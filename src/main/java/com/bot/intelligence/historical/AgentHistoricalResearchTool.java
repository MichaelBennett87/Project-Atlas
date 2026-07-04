package com.bot.intelligence.historical;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple tool wrapper exposed to analyst-style agents during the nightly phase.
 *
 * It gives agents an explicit way to ask for historical REST data instead of
 * waiting for providers to push signals.  Keep this out of the live hot path.
 */
public final class AgentHistoricalResearchTool {
    private final HistoricalDataRequestRouter router;

    public AgentHistoricalResearchTool(HistoricalDataRequestRouter router) {
        this.router = router == null ? new HistoricalDataRequestRouter() : router;
    }

    public HistoricalDataResponse requestBars(String agent, String ticker, LocalDate from, LocalDate to, String interval, int maxRows) {
        return router.request(HistoricalDataRequest.builder()
                .requestingAgent(agent)
                .provider(HistoricalDataRequest.Provider.AUTO)
                .dataType(HistoricalDataRequest.DataType.BARS)
                .ticker(ticker)
                .from(from)
                .to(to)
                .interval(interval)
                .maxRows(maxRows)
                .build());
    }

    public HistoricalDataResponse requestNews(String agent, String ticker, LocalDate from, LocalDate to, int maxRows) {
        return router.request(HistoricalDataRequest.builder()
                .requestingAgent(agent)
                .provider(HistoricalDataRequest.Provider.AUTO)
                .dataType(HistoricalDataRequest.DataType.NEWS)
                .ticker(ticker)
                .from(from)
                .to(to)
                .interval("news")
                .maxRows(maxRows)
                .build());
    }

    public HistoricalDataResponse requestFundamentals(String agent, String ticker) {
        LocalDate today = LocalDate.now();
        return router.request(HistoricalDataRequest.builder()
                .requestingAgent(agent)
                .provider(HistoricalDataRequest.Provider.AUTO)
                .dataType(HistoricalDataRequest.DataType.FUNDAMENTALS)
                .ticker(ticker)
                .from(today.minusDays(1))
                .to(today)
                .interval("overview")
                .maxRows(1)
                .build());
    }


    public HistoricalDataResponse requestTrades(String agent, String ticker, LocalDate from, LocalDate to, int maxRows) {
        return router.request(HistoricalDataRequest.builder()
                .requestingAgent(agent)
                .provider(HistoricalDataRequest.Provider.AUTO)
                .dataType(HistoricalDataRequest.DataType.TRADES)
                .ticker(ticker)
                .from(from)
                .to(to)
                .interval("trades")
                .maxRows(maxRows)
                .build());
    }

    public HistoricalDataResponse requestQuotes(String agent, String ticker, LocalDate from, LocalDate to, int maxRows) {
        return router.request(HistoricalDataRequest.builder()
                .requestingAgent(agent)
                .provider(HistoricalDataRequest.Provider.AUTO)
                .dataType(HistoricalDataRequest.DataType.QUOTES)
                .ticker(ticker)
                .from(from)
                .to(to)
                .interval("quotes")
                .maxRows(maxRows)
                .build());
    }

    public List<HistoricalDataResponse> requestForTickers(String agent, List<String> tickers, LocalDate from, LocalDate to, String interval, int maxRowsPerTicker) {
        List<HistoricalDataResponse> out = new ArrayList<>();
        if (tickers == null) return out;
        for (String ticker : tickers) {
            if (ticker == null || ticker.isBlank()) continue;
            out.add(requestBars(agent, ticker.trim(), from, to, interval, maxRowsPerTicker));
        }
        return out;
    }
}
