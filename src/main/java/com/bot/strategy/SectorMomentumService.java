package com.bot.strategy;

import com.bot.model.MarketDataCache;
import com.bot.model.SectorMomentumProfile;

import java.util.List;

public class SectorMomentumService implements SectorMomentumProvider {

    private static final int LOOKBACK_BARS = 5;
    private static final int MIN_USABLE_PEERS = 2;

    private final MarketDataCache marketData;
    private final SectorTickerMapper sectorTickerMapper;
    private final SectorMomentumProvider provider;

    public SectorMomentumService(
            SectorMomentumProvider provider
    ) {
        this.marketData = null;
        this.sectorTickerMapper = new SectorTickerMapper();
        this.provider = provider;
    }

    public SectorMomentumService(
            MarketDataCache marketData
    ) {
        this(
                marketData,
                new SectorTickerMapper()
        );
    }

    public SectorMomentumService(
            MarketDataCache marketData,
            SectorTickerMapper sectorTickerMapper
    ) {
        this.marketData = marketData;
        this.sectorTickerMapper = sectorTickerMapper;
        this.provider = null;
    }

    @Override
    public SectorMomentumProfile profile(String ticker) {
        if (provider != null) {
            SectorMomentumProfile provided =
                    provider.profile(ticker);

            if (provided == null) {
                return unknown(
                        ticker,
                        "Sector momentum provider returned null"
                );
            }

            return provided;
        }

        if (ticker == null || ticker.isBlank()) {
            return unknown(
                    "",
                    "Missing ticker"
            );
        }

        if (marketData == null) {
            return unknown(
                    ticker,
                    "Market data cache unavailable"
            );
        }

        String normalizedTicker =
                ticker.trim().toUpperCase();

        String sector =
                sectorTickerMapper.sectorForTicker(normalizedTicker);

        List<String> peers =
                sectorTickerMapper.peersForTicker(normalizedTicker);

        if (peers == null || peers.isEmpty()) {
            return new SectorMomentumProfile(
                    normalizedTicker,
                    sector,
                    0.0,
                    0.50,
                    false,
                    "UNKNOWN_SECTOR",
                    "No sector basket mapped for ticker"
            );
        }

        double totalMomentum =
                0.0;

        int usablePeers =
                0;

        for (String peer : peers) {
            double peerMomentum =
                    marketData.percentChangeBars(
                            peer,
                            LOOKBACK_BARS
                    );

            if (peerMomentum == 0.0) {
                continue;
            }

            totalMomentum += peerMomentum;
            usablePeers++;
        }

        if (usablePeers < MIN_USABLE_PEERS) {
            return new SectorMomentumProfile(
                    normalizedTicker,
                    sector,
                    0.0,
                    0.50,
                    false,
                    "UNKNOWN_SECTOR",
                    "Not enough usable sector peer data"
            );
        }

        double averageSectorMomentum =
                totalMomentum / usablePeers;

        return classify(
                normalizedTicker,
                sector,
                averageSectorMomentum,
                usablePeers
        );
    }

    public boolean allowsAutoBuy(SectorMomentumProfile profile) {
        if (profile == null || !profile.usable) {
            return true;
        }

        return !"WEAK_SECTOR".equalsIgnoreCase(profile.category) &&
                !"HOSTILE_SECTOR".equalsIgnoreCase(profile.category);
    }

    private SectorMomentumProfile classify(
            String ticker,
            String sector,
            double sectorMomentum,
            int usablePeers
    ) {
        if (sectorMomentum >= 0.03) {
            return new SectorMomentumProfile(
                    ticker,
                    sector,
                    sectorMomentum,
                    0.95,
                    true,
                    "VERY_STRONG_SECTOR",
                    "Sector basket is strongly moving with the trade; usablePeers=" + usablePeers
            );
        }

        if (sectorMomentum >= 0.015) {
            return new SectorMomentumProfile(
                    ticker,
                    sector,
                    sectorMomentum,
                    0.85,
                    true,
                    "STRONG_SECTOR",
                    "Sector basket is moving with the trade; usablePeers=" + usablePeers
            );
        }

        if (sectorMomentum >= 0.003) {
            return new SectorMomentumProfile(
                    ticker,
                    sector,
                    sectorMomentum,
                    0.65,
                    true,
                    "POSITIVE_SECTOR",
                    "Sector basket is mildly positive; usablePeers=" + usablePeers
            );
        }

        if (sectorMomentum > -0.005) {
            return new SectorMomentumProfile(
                    ticker,
                    sector,
                    sectorMomentum,
                    0.50,
                    true,
                    "NEUTRAL_SECTOR",
                    "Sector basket is mostly neutral; usablePeers=" + usablePeers
            );
        }

        if (sectorMomentum > -0.02) {
            return new SectorMomentumProfile(
                    ticker,
                    sector,
                    sectorMomentum,
                    0.25,
                    true,
                    "WEAK_SECTOR",
                    "Sector basket is weak; usablePeers=" + usablePeers
            );
        }

        return new SectorMomentumProfile(
                ticker,
                sector,
                sectorMomentum,
                0.10,
                true,
                "HOSTILE_SECTOR",
                "Sector basket is moving strongly against the trade; usablePeers=" + usablePeers
        );
    }

    private SectorMomentumProfile unknown(
            String ticker,
            String reason
    ) {
        return new SectorMomentumProfile(
                ticker,
                "UNKNOWN",
                0.0,
                0.50,
                false,
                "UNKNOWN_SECTOR",
                reason
        );
    }
}