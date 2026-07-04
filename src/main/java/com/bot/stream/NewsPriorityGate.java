package com.bot.stream;

import com.bot.master.CatalystQualityGate;
import com.bot.model.NewsEvent;

import java.util.Locale;

/**
 * Lightweight feed-priority gate that runs before expensive strategy/NLP work.
 *
 * This class is intentionally deterministic and cheap. It exists to keep broad
 * REST polling from flooding the live decision path while still allowing true
 * hard catalysts to interrupt the queue immediately.
 */
public final class NewsPriorityGate {

    private NewsPriorityGate() {
    }

    public static String preFreshnessRejectReason(NewsEvent news) {
        if (news == null) {
            return "INVALID_NEWS";
        }

        String text = normalize(news.fullText());
        String headline = normalize(news.getHeadline());
        String source = normalizeSource(news.getSource());

        if (headline.isBlank()) {
            return "EMPTY_HEADLINE";
        }

        if (isHighPriorityCatalyst(news)) {
            return null;
        }

        if (isGeneralRestSource(source)) {
            if (isGenericOpinionOrMarketCommentary(text)) {
                return "REST_FAST_REJECT_GENERIC_OPINION_OR_MARKET_COMMENTARY";
            }

            if (isKnownBadArticleCategory(text)) {
                return "REST_FAST_REJECT_KNOWN_BAD_ARTICLE_CATEGORY";
            }

            if (!isCompanySpecificEnough(news)) {
                return "REST_FAST_REJECT_NOT_COMPANY_SPECIFIC";
            }

            if (!hasAnyActionableKeyword(text)) {
                return "REST_FAST_REJECT_NO_ACTIONABLE_KEYWORD";
            }
        }

        return null;
    }

    public static boolean isHighPriorityCatalyst(NewsEvent news) {
        if (news == null) {
            return false;
        }

        String text = normalize(news.fullText());
        if (text.isBlank()) {
            return false;
        }

        if (isDangerousDilutionOrCatastrophic(text)) {
            return false;
        }

        return containsAny(text,
                "fda approval",
                "fda approves",
                "approved by fda",
                "fda clears",
                "510(k) clearance",
                "receives clearance",
                "granted patent",
                "patent granted",
                "notice of allowance",
                "wins contract",
                "won contract",
                "awarded contract",
                "contract award",
                "major contract",
                "purchase order",
                "merger agreement",
                "definitive merger agreement",
                "to be acquired",
                "acquires",
                "acquisition agreement",
                "buyout",
                "takeover",
                "raises guidance",
                "raised guidance",
                "increases outlook",
                "raises full-year",
                "beats earnings",
                "beats estimates",
                "beats expectations",
                "record revenue",
                "record results",
                "positive topline",
                "met primary endpoint",
                "trading halt",
                "halt news pending",
                "halted pending news");
    }

    public static int sourcePriority(NewsEvent news) {
        String source = normalizeSource(news == null ? null : news.getSource());
        if (source.contains("ALPACA") && source.contains("WEBSOCKET")) return 100;
        if (source.contains("ALPACA") && source.contains("NEWS") && !source.contains("REST")) return 95;
        if (source.contains("BENZINGA") && source.contains("DIRECT")) return 90;
        if (source.contains("BENZINGA") && source.contains("WEBSOCKET")) return 90;
        if (source.contains("PRESS_RELEASE") && source.contains("REST")) return 70;
        if (source.contains("ALPACA") && source.contains("REST")) return 50;
        if (source.contains("BENZINGA") && source.contains("REST")) return 45;
        return 30;
    }

    public static String priorityLabel(NewsEvent news) {
        if (isHighPriorityCatalyst(news)) {
            return "HIGH_PRIORITY_CATALYST_INTERRUPT";
        }
        int priority = sourcePriority(news);
        if (priority >= 90) return "WEBSOCKET_FIRST";
        if (priority >= 70) return "PRESS_RELEASE_BACKUP";
        if (priority >= 45) return "LOW_PRIORITY_REST";
        return "NORMAL";
    }

    public static boolean isGeneralRestSource(String source) {
        String value = normalizeSource(source);
        return value.contains("REST") && !value.contains("PRESS_RELEASE");
    }

    private static boolean isCompanySpecificEnough(NewsEvent news) {
        String ticker = normalize(news.getTicker()).toUpperCase(Locale.ROOT);
        String text = normalize(news.fullText());

        if (ticker.isBlank()) {
            return true;
        }

        if (ticker.length() >= 1 && text.contains(ticker.toLowerCase(Locale.ROOT))) {
            return true;
        }

        return hasAnyActionableKeyword(text) && !containsAny(text,
                "market today",
                "stock market",
                "sector rotation",
                "etf outflows",
                "etf inflows",
                "magnificent 7",
                "magnificent seven",
                "crypto",
                "bitcoin",
                "ethereum",
                "xrp");
    }

    private static boolean hasAnyActionableKeyword(String text) {
        return containsAny(text,
                "fda",
                "approval",
                "clearance",
                "contract",
                "awarded",
                "purchase order",
                "guidance",
                "outlook",
                "earnings",
                "eps",
                "revenue",
                "record results",
                "merger",
                "acquisition",
                "acquires",
                "buyout",
                "partnership",
                "agreement",
                "patent",
                "license",
                "launch",
                "commercial launch",
                "primary endpoint",
                "topline",
                "phase 1",
                "phase 2",
                "phase 3",
                "nasdaq compliance",
                "nyse compliance",
                "halt");
    }

    private static boolean isGenericOpinionOrMarketCommentary(String text) {
        return containsAny(text,
                "gets a rude awakening",
                "technical point to more weakness",
                "technicals point to more weakness",
                "stock chart signals",
                "net worth plunges",
                "billionaire tax plan",
                "legendary investor",
                "valuation gauge",
                "the zen of",
                "watching berkeley",
                "viral advice",
                "personal finance expert",
                "survey shows",
                "republicans are more likely",
                "top personal finance",
                "would be worth",
                "if you invested",
                "stocks that prove",
                "bulls and bears",
                "stock market today",
                "mags etf",
                "etf assets",
                "etf outflows",
                "etf inflows",
                "crypto effect",
                "bitcoin price",
                "xrp hanging",
                "polymarket");
    }

    private static boolean isKnownBadArticleCategory(String text) {
        return containsAny(text,
                "stocks moving in",
                "after-market session",
                "pre-market session",
                "premarket session",
                "top gainers",
                "top losers",
                "market clubhouse",
                "whale activity",
                "options activity",
                "unusual options",
                "shareholder alert",
                "investor alert",
                "class action",
                "lead plaintiff",
                "law offices",
                "deadline alert",
                "declares dividend",
                "earnings scheduled",
                "conference call transcript",
                "transcript:",
                "stocks to watch",
                "things to know",
                "here's why",
                "here’s why",
                "why is ",
                "final trades");
    }

    private static boolean isDangerousDilutionOrCatastrophic(String text) {
        return containsAny(text,
                "public offering",
                "registered direct",
                "at-the-market offering",
                "atm offering",
                "files prospectus",
                "resale by the selling stockholders",
                "bankruptcy",
                "chapter 11",
                "going concern",
                "delisting",
                "clinical hold",
                "fda rejection");
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return CatalystQualityGate.normalize(value == null ? "" : value);
    }

    private static String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "UNKNOWN";
        }
        return source.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }
}
