package com.bot.stream;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the unified live runner from wasting Alpaca stock-price polling slots on
 * symbols that clearly are not tradable Alpaca US-equity symbols.
 *
 * Benzinga and Alpaca news can include Canadian tickers, crypto pairs, foreign
 * listings, warrants, class-share suffixes with slashes, and index-like symbols.
 * Those are useful as news context, but they should not consume one of the live
 * stock price tracking slots unless Alpaca can actually price them through the
 * stock bars/trades endpoints.
 */
public final class AlpacaSymbolFilter {

    private static final Set<String> PERMANENTLY_REJECTED_SYMBOLS = ConcurrentHashMap.newKeySet();

    private static final Set<String> COMMON_MARKET_ETFS_AND_INDEX_PROXIES = Set.of(
            "SPY", "QQQ", "IWM", "DIA", "VOO", "VTI",
            "XLF", "XLI", "XLV", "XLY", "XLC", "XLE", "XLB", "XLK", "XLU", "XLP", "XLRE",
            "SMH", "SOXX", "SOXQ", "SPMO", "UUP", "USO", "GLD", "SLV", "TLT", "HYG", "LQD"
    );

    private AlpacaSymbolFilter() {
    }

    public static String normalize(String ticker) {
        if (ticker == null) {
            return "";
        }
        return ticker.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isEligibleStockSymbol(String ticker) {
        String symbol = normalize(ticker);

        if (symbol.isBlank()) {
            return false;
        }

        if (PERMANENTLY_REJECTED_SYMBOLS.contains(symbol)) {
            return false;
        }

        if (COMMON_MARKET_ETFS_AND_INDEX_PROXIES.contains(symbol)) {
            return false;
        }

        // OTC/foreign ordinary shares often appear in news as five-letter symbols
        // ending in F (for example SSNLF/GFTYF). Alpaca's stock endpoint often
        // cannot return regular bars for these, so they should not consume live
        // strategy price-stream capacity.
        if (symbol.length() == 5 && symbol.endsWith("F")) {
            return false;
        }

        // Exchange-prefixed symbols from news, e.g. TSX:MDA, are not valid for
        // Alpaca's /v2/stocks/{symbol}/bars/latest endpoint.
        if (symbol.contains(":")) {
            return false;
        }

        // Benzinga can emit class-share formats such as TSX:GIB/A. Alpaca stock
        // symbols in this project should not be slash-delimited.
        if (symbol.contains("/")) {
            return false;
        }

        // Avoid crypto pairs from broad news polling. They need Alpaca crypto
        // endpoints, not the stock endpoints used by this bot.
        if (symbol.endsWith("USD") && symbol.length() > 3) {
            return false;
        }

        // Common index/non-equity markers.
        if (symbol.startsWith("^") || symbol.startsWith("$") || symbol.contains("=")) {
            return false;
        }

        // Permit normal US symbols plus dot class shares, e.g. BRK.B.
        return symbol.matches("[A-Z][A-Z0-9]{0,5}(\\.[A-Z])?");
    }

    public static boolean isEligibleAlpacaAsset(String symbol, String name, String exchange, boolean tradable, boolean marginable, boolean shortable) {
        String normalized = normalize(symbol);
        if (!tradable || !isEligibleStockSymbol(normalized)) {
            return false;
        }

        String ex = exchange == null ? "" : exchange.trim().toUpperCase(Locale.ROOT);
        boolean allowArcaEtfs = "true".equalsIgnoreCase(System.getenv().getOrDefault("ALLOW_MARKET_ETFS", "false"));
        if ("ARCA".equals(ex) && !allowArcaEtfs) {
            return false;
        }
        if (!("NYSE".equals(ex) || "NASDAQ".equals(ex) || "AMEX".equals(ex) || (allowArcaEtfs && "ARCA".equals(ex)))) {
            return false;
        }

        String n = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
        if (looksLikeNonCommonEquity(normalized, n)) {
            return false;
        }

        // If Alpaca says the asset is neither marginable nor shortable, it is often
        // a very thin/special instrument. Keep listed penny stocks, but avoid most
        // odd lots/preferreds/rights that clog full-market scans.
        if (!marginable && !shortable && normalized.length() >= 5 && !"true".equalsIgnoreCase(System.getenv().getOrDefault("ALLOW_THIN_LONG_SYMBOLS", "false"))) {
            return false;
        }

        return true;
    }

    private static boolean looksLikeNonCommonEquity(String symbol, String upperName) {
        if (upperName == null) {
            upperName = "";
        }
        String n = upperName;
        if (n.contains(" ETF") || n.endsWith(" ETF") || n.contains(" EXCHANGE TRADED") || n.contains(" ETN")) return true;
        if (n.contains(" FUND") || n.endsWith(" FUND") || n.contains(" CLOSED END") || n.contains(" CLOSED-END")) return true;
        if (n.contains(" PREFERRED") || n.contains(" PREFERENCE") || n.contains(" PFD")) return true;
        if (n.contains(" WARRANT") || n.contains(" WT ") || n.endsWith(" WT") || n.contains(" RIGHT") || n.contains(" RIGHTS")) return true;
        if (n.contains(" UNIT") || n.endsWith(" UNITS") || n.contains(" DEPOSITARY") || n.contains(" DEPOSITORY")) return true;
        if (n.contains(" NOTE") || n.contains(" NOTES") || n.contains(" BOND") || n.contains(" DEBENTURE")) return true;
        if (n.contains("SPAC") || n.contains("ACQUISITION CORP") && (symbol.endsWith("U") || symbol.endsWith("W") || symbol.endsWith("R"))) return true;

        // Common Alpaca/Benzinga symbol suffixes for preferreds, rights, warrants, and units.
        // Do not block normal 5-letter Nasdaq names like GOOGL unless the name also
        // indicates a special instrument or Alpaca metadata says it is very thin.
        if (symbol.length() >= 5 && (symbol.endsWith("W") || symbol.endsWith("WS") || symbol.endsWith("WT") || symbol.endsWith("R") || symbol.endsWith("U"))) {
            return true;
        }
        return false;
    }

    public static void rejectPermanently(String ticker, String reason) {
        String symbol = normalize(ticker);
        if (symbol.isBlank()) {
            return;
        }

        PERMANENTLY_REJECTED_SYMBOLS.add(symbol);
        System.out.println("ALPACA SYMBOL PERMANENTLY REJECTED: ticker=" + symbol + " reason=" + reason);
    }

    public static boolean isPermanentlyRejected(String ticker) {
        return PERMANENTLY_REJECTED_SYMBOLS.contains(normalize(ticker));
    }
}
