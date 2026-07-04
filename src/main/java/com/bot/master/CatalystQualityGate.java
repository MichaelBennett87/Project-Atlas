package com.bot.master;

import com.bot.model.NewsEvent;
import com.bot.technical.TechnicalAnalysis;

import java.util.Locale;

/**
 * Central profitability filter for the unified engine.
 *
 * The goal is to trade fewer, better catalysts. Analyst notes, price-target
 * maintenance, class-action/law-firm notices, dividend declarations, generic
 * market summaries, "how much would you have made" articles, and broad whale
 * activity lists create noise but rarely create clean intraday continuation.
 */
public final class CatalystQualityGate {

    private CatalystQualityGate() {
    }

    public static String preNlpRejectReason(NewsEvent news) {
        String headline = normalize(news == null ? "" : news.getHeadline());
        if (headline.isBlank()) {
            return "EMPTY_HEADLINE";
        }

        if (isHighImpactEarningsOrBusinessUpdate(headline)) {
            return null;
        }

        if (containsAny(headline,
                "maintains buy",
                "maintains hold",
                "maintains neutral",
                "maintains outperform",
                "maintains overweight",
                "reiterates buy",
                "reiterates hold",
                "reiterates neutral",
                "raises price target",
                "lowers price target",
                "cuts price target",
                "price target to $",
                "announces price target",
                "analyst maintains",
                "analyst raises",
                "analyst lowers",
                "jefferies maintains",
                "citigroup maintains",
                "barclays maintains",
                "td cowen maintains",
                "rbc capital reiterates",
                "stocks whale activity",
                "whale activity",
                "earnings scheduled",
                "stocks moving in",
                "stocks moving in thursday",
                "stocks moving in friday",
                "stocks moving in monday",
                "stocks moving in tuesday",
                "stocks moving in wednesday",
                "after-market session",
                "pre-market session",
                "premarket session",
                "intraday session",
                "consumer discretionary stocks moving",
                "health care stocks moving",
                "healthcare stocks moving",
                "industrials stocks moving",
                "information technology stocks moving",
                "financial stocks moving",
                "energy stocks moving",
                "communication services stocks moving",
                "materials stocks moving",
                "real estate stocks moving",
                "top gainers",
                "top losers",
                "market clubhouse",
                "stock is trending",
                "what's happening today",
                "what is happening today",
                "why is ",
                "would be worth",
                "if you invested",
                "prediction markets are pricing",
                "nancy pelosi has added",
                "pelosi has added",
                "added these 9 stocks",
                "these 9 stocks",
                "to her portfolio since",
                "magnificent seven names",
                "under-the-radar",
                "stocks to buy",
                "stock lifts off after the close",
                "stocks to watch",
                "things to know",
                "market-moving",
                "market moving",
                "sector stocks moving",
                "movers",
                "here's why",
                "here’s why",
                "final trades")) {
            return "PRE_NLP_LOW_EDGE_FINANCIAL_CONTENT";
        }

        if (containsAny(headline,
                "shareholder alert",
                "investor alert",
                "class action",
                "lead plaintiff",
                "deadline alert",
                "securities fraud lawsuit",
                "shareholders who lost money",
                "law offices",
                "levi & korsinsky",
                "glancy prongay",
                "kahn swick",
                "rosen law",
                "pomerantz",
                "schall law",
                "the schall law firm",
                "rosen, a leading law firm",
                "rosen, skilled investor counsel",
                "rosen, globally recognized",
                "glancy prongay & murray",
                "bronstein, gewirtz",
                "the gross law firm",
                "berger montague",
                "bfa law",
                "faruqi & faruqi",
                "robbins geller",
                "secure counsel before important deadline")) {
            return "PRE_NLP_LOW_EDGE_LEGAL_PR";
        }

        return null;
    }

    public static String rejectReason(NewsEvent news) {
        String text = normalize(news == null ? "" : news.fullText());
        if (text.isBlank()) {
            return "EMPTY_NEWS_TEXT";
        }

        if (containsAny(text,
                "class action",
                "shareholder alert",
                "law offices",
                "law office",
                "investor alert",
                "shareholders who lost money",
                "shareholder investigation",
                "investigates merger",
                "investigates sale",
                "investigates acquisition",
                "fairness of the merger",
                "kahn swick",
                "kahn swick & foti",
                "levi & korsinsky",
                "levi and korsinsky",
                "rosen law",
                "the rosen law",
                "grabar law",
                "ademi llp",
                "portnoy law",
                "bragar eagel",
                "pomerantz law",
                "bernstein liebhard",
                "glancy prongay",
                "schall law",
                "securities fraud lawsuit",
                "investors should contact",
                "deadline:",
                "investigation on your behalf")) {
            return "LOW_EDGE_LEGAL_PR";
        }

        if (containsAny(text,
                "maintains buy",
                "maintains outperform",
                "maintains neutral",
                "reiterates buy",
                "reiterates outperform",
                "reiterates overweight",
                "raises price target",
                "lowers price target",
                "announces price target",
                "price target to",
                "analyst maintains",
                "analysts cut",
                "analysts slash",
                "analyst rating")) {
            return "LOW_EDGE_ANALYST_PRICE_TARGET";
        }

        if (!isHighImpactEarningsOrBusinessUpdate(text) && containsAny(text,
                "declares dividend",
                "dividend",
                "share buy-back program",
                "share buyback program",
                "transactions related to share buy",
                "annual meeting",
                "conference call transcript",
                "transcript:",
                "if you invested",
                "how much $",
                "how much you would have made",
                "would be worth today",
                "worth this much today",
                "whale alerts",
                "whale activity",
                "stocks whale activity",
                "stocks with whale",
                "stocks moving in",
                "earnings scheduled",
                "stocks to watch",
                "top gainers",
                "top losers",
                "market clubhouse",
                "why is ",
                "what's happening today",
                "stock is trending",
                "final trades",
                "stock market today",
                "nancy pelosi has added",
                "pelosi has added",
                "added these 9 stocks",
                "these 9 stocks",
                "to her portfolio since",
                "magnificent seven names",
                "under-the-radar",
                "stocks to buy",
                "stock lifts off after the close",
                "here's why",
                "here’s why")) {
            return "LOW_EDGE_GENERIC_FINANCIAL_CONTENT";
        }

        if (containsAny(text,
                "shares are trading lower",
                "stock edges lower",
                "stock sinks",
                "bearish pressure",
                "tumbles",
                "falls after",
                "downgrade",
                "cuts forecast",
                "slashes forecast")) {
            return "NEGATIVE_OR_WEAK_LONG_CATALYST";
        }

        if (containsAny(text,
                "bankruptcy",
                "chapter 11",
                "going concern",
                "delisting",
                "offering",
                "registered direct",
                "at-the-market",
                "atm offering",
                "reverse split",
                "sec investigation",
                "fda rejection",
                "clinical hold")) {
            return "DANGEROUS_DILUTION_OR_CATASTROPHIC_NEWS";
        }

        // If it is a broad macro/political headline, only allow it when it also
        // contains a very direct company catalyst. Otherwise it tends to create
        // noisy sympathy trades.
        if (containsAny(text,
                "president ",
                "federal reserve",
                "fed rate",
                "iran",
                "oil sales",
                "stock market",
                "sector rotation",
                "committee on energy and commerce")) {
            if (tradeableCatalystScore(news) < 0.55) {
                return "LOW_EDGE_MACRO_OR_POLITICAL_SYMPATHY";
            }
        }

        return null;
    }

    public static double tradeableCatalystScore(NewsEvent news) {
        if (news != null && isSyntheticMarketStateOpportunity(news)) {
            double embedded = news.getCatalystScore();
            if (embedded > 0.0) {
                return TechnicalAnalysis.clamp(Math.max(0.40, embedded));
            }
            double parsed = parseStateOpportunityScore(news.fullText());
            return TechnicalAnalysis.clamp(Math.max(0.40, parsed));
        }
        String text = normalize(news == null ? "" : news.fullText());
        double score = 0.0;

        if (containsAny(text, "fda approval", "fda approves", "approved by fda", "fda clears", "clearance", "510(k)", "breakthrough therapy")) score += 0.65;
        if (containsAny(text, "phase ii", "phase 2", "phase iii", "phase 3", "patient enrollment", "clinical trial")) score += 0.30;
        if (containsAny(text, "wins contract", "won contract", "awarded", "awarded contract", "contract award", "major contract", "contract", "defense orders", "purchase order", "orderbook", "backlog")) score += 0.45;
        if (containsAny(text, "raises guidance", "guidance raise", "raises full-year", "increases outlook")) score += 0.55;
        if (containsAny(text,
                "beats earnings",
                "beats estimates",
                "beats expectations",
                "beat estimate",
                "beat estimates",
                "beat expectation",
                "beat expectations",
                "eps beat",
                "eps beats",
                "eps $",
                "eps of $",
                "adj. eps",
                "adjusted eps",
                "sales beat",
                "sales beats",
                "sales $",
                "revenue beat",
                "revenue beats",
                "revenue $",
                "record results",
                "reports record results",
                "record revenue",
                "record sales",
                "record quarterly",
                "revenue growth",
                "profit growth")
                || ((text.contains("eps") || text.contains("sales") || text.contains("revenue"))
                && (text.contains(" beat") || text.contains(" beats") || text.contains("above estimate") || text.contains("tops estimate")))) score += 0.45;
        if (containsAny(text, "strategic review", "strategic options", "merger", "merger agreement", "definitive merger agreement", "acquisition", "acquisition agreement", "acquires", "to be acquired", "buyout", "takeover")) score += 0.45;
        if (containsAny(text, "partnership", "joint venture", "collaboration", "launches", "commercial launch", "signs agreement", "agreement with", "supply chain planning", "national security activities")) score += 0.35;
        if (containsAny(text, "patent", "granted patent", "patent granted", "notice of allowance", "exclusive license", "regulatory approval")) score += 0.35;
        if (containsAny(text, "trading halt", "halt news pending", "halted pending news")) score += 0.30;
        if (containsAny(text, "short squeeze", "low float", "heavily shorted")) score += 0.25;

        // Penalize weaker article forms even if they contain one good word.
        if (containsAny(text, "maintains", "price target", "reiterates", "dividend", "shareholder alert", "class action")) {
            score -= 0.45;
        }
        if (containsAny(text, "stock sinks", "trading lower", "bearish pressure", "downgrade")) {
            score -= 0.35;
        }

        return TechnicalAnalysis.clamp(score);
    }

    public static boolean isTradeableCatalyst(NewsEvent news) {
        return rejectReason(news) == null && tradeableCatalystScore(news) >= envDouble("MIN_TRADEABLE_CATALYST_SCORE", 0.30);
    }

    /**
     * Returns true for internally generated market-state opportunities that are
     * intentionally routed through the NewsEvent pipeline.
     *
     * These are not normal external news headlines. They are created by the
     * state-driven opportunity engine when technicals, order flow,
     * microstructure, and risk conditions combine into a tradeable setup. The
     * regular pre-NLP/news-quality filters should not reject them just because
     * they read like a synthetic market-state summary instead of a press
     * release.
     */
    public static boolean isSyntheticMarketStateOpportunity(NewsEvent news) {
        if (news == null) {
            return false;
        }

        String source = normalize(news.getSource());
        String text = normalize(news.fullText());

        if (source.contains("state_opportunity_ranker")
                || source.contains("local_market_state_db")
                || source.contains("market_state")) {
            return true;
        }

        if (text.isBlank()) {
            return false;
        }

        if (containsAny(text,
                "syntheticmarketstateopportunity",
                "synthetic market state opportunity",
                "state opportunity:",
                "state-driven opportunity",
                "state driven opportunity",
                "local market state",
                "market state supports this opportunity",
                "continuously maintained local market state",
                "ai governor state opportunity",
                "ai_governor_state_opportunity",
                "technical=",
                "orderflow=",
                "order flow=",
                "microstructure=",
                "riskscore=",
                "risk score=")) {
            return true;
        }

        return containsAny(text, "direction=long", "direction=short")
                && containsAny(text, "opportunity score", "opportunityscore", "score=")
                && containsAny(text, "technical", "order flow", "orderflow", "microstructure", "market state");
    }

    private static double parseStateOpportunityScore(String text) {
        String value = normalize(text);
        if (value.isBlank()) {
            return 0.0;
        }

        double parsed = Math.max(
                parseLabeledDouble(value, "opportunityscore"),
                Math.max(
                        parseLabeledDouble(value, "opportunity score"),
                        parseLabeledDouble(value, "score")
                )
        );

        if (parsed > 0.0) {
            return TechnicalAnalysis.clamp(parsed);
        }

        double score = 0.0;
        if (containsAny(value, "direction=long", "direction=short")) score += 0.12;
        if (containsAny(value, "technical", "technical=")) score += 0.10;
        if (containsAny(value, "order flow", "orderflow", "orderflow=")) score += 0.10;
        if (containsAny(value, "microstructure", "microstructure=")) score += 0.10;
        if (containsAny(value, "risk", "risk=")) score += 0.06;
        if (containsAny(value, "state opportunity", "market state supports this opportunity")) score += 0.12;

        return TechnicalAnalysis.clamp(score);
    }

    private static double parseLabeledDouble(String text, String label) {
        if (text == null || label == null || label.isBlank()) {
            return 0.0;
        }

        String token = label.toLowerCase(Locale.ROOT);
        int index = text.indexOf(token);
        while (index >= 0) {
            int cursor = index + token.length();
            while (cursor < text.length()) {
                char c = text.charAt(cursor);
                if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                    cursor++;
                    continue;
                }
                break;
            }

            int start = cursor;
            boolean seenDigit = false;
            while (cursor < text.length()) {
                char c = text.charAt(cursor);
                if ((c >= '0' && c <= '9') || c == '.' || c == '-') {
                    if (c >= '0' && c <= '9') {
                        seenDigit = true;
                    }
                    cursor++;
                } else {
                    break;
                }
            }

            if (seenDigit) {
                try {
                    double value = Double.parseDouble(text.substring(start, cursor));
                    if (Double.isFinite(value)) {
                        return value > 1.0 && value <= 100.0 ? value / 100.0 : value;
                    }
                } catch (Exception ignored) {
                    // Keep scanning in case the first label occurrence is malformed.
                }
            }

            index = text.indexOf(token, index + token.length());
        }
        return 0.0;
    }


    private static boolean isHighImpactEarningsOrBusinessUpdate(String text) {
        String value = normalize(text);
        if (value.isBlank()) {
            return false;
        }

        boolean earningsOrResults = containsAny(value,
                "reports record results",
                "record results",
                "record quarterly",
                "record revenue",
                "record sales",
                "q1 adj. eps",
                "q2 adj. eps",
                "q3 adj. eps",
                "q4 adj. eps",
                "fy adj. eps",
                "adj. eps",
                "adjusted eps",
                "eps beats",
                "eps beat",
                "sales beat",
                "sales beats",
                "revenue beat",
                "revenue beats",
                "beats estimate",
                "beats estimates",
                "beat estimate",
                "beat estimates",
                "above estimate",
                "tops estimate",
                "beats expectation",
                "beats expectations");

        boolean hardBusinessCatalyst = containsAny(value,
                "raises guidance",
                "raised guidance",
                "raises outlook",
                "increases outlook",
                "signs agreement",
                "signed agreement",
                "awarded contract",
                "contract award",
                "major contract",
                "receives notice of allowance",
                "notice of allowance",
                "fda approves",
                "fda approval",
                "fda clears",
                "510(k)",
                "positive topline",
                "met primary endpoint");

        boolean obviousNoise = containsAny(value,
                "maintains buy",
                "maintains hold",
                "maintains neutral",
                "reiterates",
                "price target",
                "class action",
                "shareholder alert",
                "investor alert",
                "lead plaintiff",
                "deadline alert",
                "offering",
                "warrants",
                "public offering",
                "registered direct",
                "at-the-market",
                "atm offering");

        return (earningsOrResults || hardBusinessCatalyst) && !obviousNoise;
    }

    public static boolean containsAny(String text, String... needles) {
        String value = text == null ? "" : text;
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && value.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value
                .toLowerCase(Locale.ROOT)
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
