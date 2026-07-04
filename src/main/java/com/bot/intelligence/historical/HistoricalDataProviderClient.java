package com.bot.intelligence.historical;

/** Provider-specific REST client used only by the nightly/offline research path. */
public interface HistoricalDataProviderClient {
    String providerName();
    boolean enabled();
    boolean supports(HistoricalDataRequest request);
    HistoricalDataResponse fetch(HistoricalDataRequest request);
}
