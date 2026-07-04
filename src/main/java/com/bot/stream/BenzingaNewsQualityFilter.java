package com.bot.stream;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public class BenzingaNewsQualityFilter {

    public enum Decision {
        ACCEPT,
        REJECT_TRANSLATION,
        REJECT_AUTOMATED_CONTENT,
        REJECT_MOVERS_RECAP,
        REJECT_OPTIONS_FLOW,
        REJECT_NO_SYMBOLS,
        REJECT_UPDATED_EVENT,
        REJECT_NON_STOCK_SECURITY,
        REJECT_BROAD_MULTI_SYMBOL_ARTICLE,
        REJECT_PRIVATE_COMPANY_VALUATION_ARTICLE,
        REJECT_NON_ENGLISH
    }

    public Decision evaluate(
            String action,
            JsonNode content
    ) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return Decision.REJECT_NO_SYMBOLS;
        }

        if (isRejectUpdatedEventsEnabled() && "Updated".equalsIgnoreCase(safe(action))) {
            return Decision.REJECT_UPDATED_EVENT;
        }

        String type =
                safe(content.path("type").asText())
                        .toLowerCase(Locale.ROOT);

        String title =
                safe(content.path("title").asText())
                        .toLowerCase(Locale.ROOT);

        String body =
                safe(content.path("body").asText())
                        .toLowerCase(Locale.ROOT);

        String combined =
                title + " " + body;

        if (isTranslationType(type)) {
            return Decision.REJECT_TRANSLATION;
        }

        if (containsNonEnglishLanguageMarkers(title, body)) {
            return Decision.REJECT_NON_ENGLISH;
        }

        if (containsChannel(content, "Movers") || containsTag(content, "BZI-TFM")) {
            return Decision.REJECT_MOVERS_RECAP;
        }

        if (containsTag(content, "BZI-AUOA") || containsChannel(content, "Options")) {
            return Decision.REJECT_OPTIONS_FLOW;
        }

        if (containsOptionsFlowLanguage(combined)) {
            return Decision.REJECT_OPTIONS_FLOW;
        }

        if (containsAutomatedContentLanguage(combined)) {
            return Decision.REJECT_AUTOMATED_CONTENT;
        }

        if (containsHardPreNlpNoiseLanguage(combined)) {
            return Decision.REJECT_AUTOMATED_CONTENT;
        }

        if (containsGeneratedRecapLanguage(combined)) {
            return Decision.REJECT_MOVERS_RECAP;
        }

        if (containsPrivateCompanyValuationLanguage(combined)) {
            return Decision.REJECT_PRIVATE_COMPANY_VALUATION_ARTICLE;
        }

        if (containsBroadMacroCommentaryLanguage(combined)) {
            return Decision.REJECT_BROAD_MULTI_SYMBOL_ARTICLE;
        }

        JsonNode securities =
                content.path("securities");

        if (!securities.isArray() || securities.isEmpty()) {
            return Decision.REJECT_NO_SYMBOLS;
        }

        if (isBroadMultiSymbolRejectEnabled() && isBroadMultiSymbolArticle(content)) {
            return Decision.REJECT_BROAD_MULTI_SYMBOL_ARTICLE;
        }

        return Decision.ACCEPT;
    }

    public boolean isSupportedStockSecurity(
            JsonNode security
    ) {
        if (security == null || security.isMissingNode() || security.isNull()) {
            return false;
        }

        String symbol =
                safe(security.path("symbol").asText())
                        .trim()
                        .toUpperCase(Locale.ROOT);

        String exchange =
                safe(security.path("exchange").asText())
                        .trim()
                        .toUpperCase(Locale.ROOT);

        if (symbol.isBlank()) {
            return false;
        }

        if (symbol.contains("/") || symbol.contains(":") || symbol.contains(".") || symbol.contains("-")) {
            return false;
        }

        if (symbol.length() < 1 || symbol.length() > 5) {
            return false;
        }

        for (int i = 0; i < symbol.length(); i++) {
            if (!Character.isLetter(symbol.charAt(i))) {
                return false;
            }
        }

        return exchange.equals("NASDAQ") ||
                exchange.equals("NYSE") ||
                exchange.equals("AMEX") ||
                exchange.equals("ARCA");
    }

    public Set<String> supportedSymbols(
            JsonNode content
    ) {
        Set<String> symbols =
                new LinkedHashSet<>();

        if (content == null || content.isMissingNode() || content.isNull()) {
            return symbols;
        }

        JsonNode securities =
                content.path("securities");

        if (!securities.isArray()) {
            return symbols;
        }

        for (JsonNode security : securities) {
            if (!isSupportedStockSecurity(security)) {
                continue;
            }

            String symbol =
                    safe(security.path("symbol").asText())
                            .trim()
                            .toUpperCase(Locale.ROOT);

            if (!symbol.isBlank()) {
                symbols.add(symbol);
            }
        }

        return symbols;
    }

    private boolean isRejectUpdatedEventsEnabled() {
        return !"false".equalsIgnoreCase(
                System.getenv().getOrDefault(
                        "BENZINGA_REJECT_UPDATED_EVENTS",
                        "false"
                )
        );
    }

    private boolean isBroadMultiSymbolRejectEnabled() {
        return !"false".equalsIgnoreCase(
                System.getenv().getOrDefault(
                        "BENZINGA_REJECT_BROAD_MULTI_SYMBOL_ARTICLES",
                        "true"
                )
        );
    }

    private boolean isBroadMultiSymbolArticle(
            JsonNode content
    ) {
        Set<String> symbols =
                supportedSymbols(content);

        int hardMaxSymbols =
                intEnv("BENZINGA_BROAD_ARTICLE_MAX_SYMBOLS", 5);

        if (symbols.size() > hardMaxSymbols) {
            return true;
        }

        String title =
                safe(content.path("title").asText())
                        .toLowerCase(Locale.ROOT);

        if (symbols.size() >= 3 && containsAny(
                title,
                "more than",
                "combined",
                "versus",
                "compared with",
                "compared to",
                "stocks combined",
                "stock market today",
                "market update",
                "traded more than",
                "stock market euphoric",
                "musk post and iran deal",
                "iran deal",
                "oil falls",
                "oil is falling",
                "top upgrades",
                "top downgrades",
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
                "here’s why"
        )) {
            return true;
        }

        return false;
    }

    private boolean isTranslationType(
            String type
    ) {
        if (type == null || type.isBlank()) {
            return false;
        }

        String normalized =
                type.trim().toLowerCase(Locale.ROOT);

        // Benzinga uses many localized wire type variants such as
        // benzinga_wire_spanish, benzinga_wire_italia, benzinga_wire_japanese, etc.
        // Reject every localized wire variant before symbol extraction / FinBERT.
        return normalized.startsWith("benzinga_wire_");
    }

    private boolean containsNonEnglishLanguageMarkers(
            String title,
            String body
    ) {
        String titleOnly =
                safe(title).toLowerCase(Locale.ROOT);

        String combined =
                (safe(title) + " " + safe(body)).toLowerCase(Locale.ROOT);

        if (containsNonLatinScript(titleOnly)) {
            return true;
        }

        // Keep this fallback intentionally conservative. It catches translated
        // Benzinga headlines even if a feed item arrives as type=story.
        return containsAny(
                titleOnly,
                "cuánto valdría",
                "cuanto valdria",
                "inversión de",
                "inversion de",
                "hace 5 años",
                "voici combien",
                "vaudraient aujourd",
                "investis dans",
                "il y a 5 ans",
                "quanto valeria",
                "quanto varrebbe",
                "investimento de",
                "investimento em",
                "feito há",
                "ecco quanto",
                "effettuato 5 anni fa",
                "wäre",
                "waere",
                "investition in",
                "vor 5 jahren",
                "heute wert"
        ) || containsAny(
                combined,
                "contenido automatizado de benzinga",
                "moteur de contenu automatisé",
                "motor automatizado de conteúdos",
                "motore automatizzato di benzinga",
                "automatisierte inhaltsmaschine",
                "この記事はbenzinga",
                "本文由benzinga",
                "이 기사는 benzinga"
        );
    }

    private boolean containsNonLatinScript(
            String text
    ) {
        if (text == null || text.isBlank()) {
            return false;
        }

        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeBlock block =
                    Character.UnicodeBlock.of(text.charAt(i));

            if (block == Character.UnicodeBlock.ARABIC ||
                    block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                    block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                    block == Character.UnicodeBlock.HANGUL_JAMO ||
                    block == Character.UnicodeBlock.HIRAGANA ||
                    block == Character.UnicodeBlock.KATAKANA) {
                return true;
            }
        }

        return false;
    }

    private boolean containsBroadMacroCommentaryLanguage(
            String text
    ) {
        return containsAny(
                text,
                "stock market euphoric over",
                "euphoric stock market",
                "stock market reaction to the iran deal",
                "iran deal",
                "u.s. iran deal",
                "us iran deal",
                "ceasefire for 60 days",
                "strait of hormuz",
                "oil is falling",
                "oil falls",
                "sell-the-news",
                "sell the news",
                "prudent investors need to remember",
                "the arora report",
                "retail traders should stop watching",
                "start watching the calendar",
                "market-structure event",
                "market structure event",
                "what you actually bought"
        );
    }

    private boolean containsPrivateCompanyValuationLanguage(
            String text
    ) {
        if (text == null || text.isBlank()) {
            return false;
        }

        boolean privateFundingLanguage = containsAny(
                text,
                "valuation ahead of potential ipo",
                "ahead of potential ipo",
                "potential 2027 ipo",
                "potential ipo",
                "pre-ipo",
                "pre ipo",
                "eyes staggering",
                "staggering valuation",
                "new funding round",
                "funding round",
                "private funding",
                "secondary share sales",
                "raise private funding",
                "raising private funding",
                "private company valuation",
                "venture funding",
                "reportedly eyes",
                "eyes valuation",
                "valued at",
                "valuation to between",
                "valuation of between"
        );

        if (!privateFundingLanguage) {
            return false;
        }

        // Do not block actual tradable IPO events. Those can remain low/moderate catalysts.
        return !containsAny(
                text,
                "files for ipo",
                "filed for ipo",
                "ipo priced",
                "prices ipo",
                "ipo closes",
                "closes ipo",
                "begins trading",
                "starts trading",
                "commences trading"
        );
    }


    private boolean containsHardPreNlpNoiseLanguage(
            String text
    ) {
        return containsAny(
                text,
                "shareholder alert",
                "investor alert",
                "deadline alert",
                "secure counsel before important deadline",
                "rosen, a leading law firm",
                "rosen, globally recognized",
                "lead plaintiff deadline",
                "class action",
                "securities fraud lawsuit",
                "lost money urged to contact",
                "kahn swick",
                "levi & korsinsky",
                "glancy prongay",
                "rosen law",
                "pomerantz law",
                "bronstein gewirtz",
                "bronstein, gewirtz",
                "bragar eagel",
                "the gross law firm",
                "the schall law firm",
                "berger montague",
                "bfa law",
                "faruqi & faruqi",
                "robbins geller",
                "investigates claims",
                "investigates merger",
                "law firm reminds",
                "analyst maintains",
                "maintains buy",
                "maintains hold",
                "maintains neutral",
                "maintains sell",
                "raises price target",
                "lowers price target",
                "price target to",
                "stock is moving higher today",
                "stock is gaining today",
                "stock is trending",
                "what's happening today",
                "what is happening today",
                "stocks whale activity",
                "whale activity in today's session",
                "earnings scheduled",
                "calendar shift sparked confusion",
                "would be worth this much today",
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
                "here’s why"
        );
    }

    private boolean containsOptionsFlowLanguage(
            String text
    ) {
        return containsAny(
                text,
                "whale alert",
                "whale activity",
                "stocks with whale",
                "whale alerts",
                "options activity",
                "options alert",
                "unusual options",
                "option sweep",
                "call option",
                "put option",
                "put/call",
                "options activity scanner",
                "options alert terminology"
        );
    }

    private boolean containsAutomatedContentLanguage(
            String text
    ) {
        return containsAny(
                text,
                "generated by benzinga's automated content engine",
                "generated by benzinga’s automated content engine",
                "this article was generated by benzinga",
                "automated content engine"
        );
    }

    private boolean containsGeneratedRecapLanguage(
            String text
    ) {
        return containsAny(
                text,
                "stocks moving in monday",
                "stocks moving in tuesday",
                "stocks moving in wednesday",
                "stocks moving in thursday",
                "stocks moving in friday",
                "stocks moving in today's",
                "stocks moving in today",
                "intraday session",
                "what is going on with",
                "why is ",
                "stock market today",
                "earnings scheduled",
                "stocks to watch",
                "top gainers",
                "top losers",
                "market clubhouse",
                "investor day",
                "stock is trending",
                "shares are trading higher",
                "shares are trading lower",
                "analyst maintains",
                "raises price target",
                "lowers price target",
                "price target to",
                "deadline alert",
                "lead plaintiff deadline",
                "securities fraud lawsuit",
                "investor alert",
                "shareholder alert",
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
                "here’s why"
        ) || (text.contains("gainers") && text.contains("losers"));
    }

    private boolean containsChannel(
            JsonNode content,
            String target
    ) {
        JsonNode channels =
                content.path("channels");

        if (!channels.isArray()) {
            return false;
        }

        for (JsonNode channel : channels) {
            if (target.equalsIgnoreCase(channel.asText())) {
                return true;
            }
        }

        return false;
    }

    private boolean containsTag(
            JsonNode content,
            String target
    ) {
        JsonNode tags =
                content.path("tags");

        if (!tags.isArray()) {
            return false;
        }

        for (JsonNode tag : tags) {
            if (target.equalsIgnoreCase(tag.asText())) {
                return true;
            }
        }

        return false;
    }

    private boolean containsAny(
            String text,
            String... phrases
    ) {
        if (text == null || phrases == null) {
            return false;
        }

        String normalized =
                text.toLowerCase(Locale.ROOT);

        for (String phrase : phrases) {
            if (phrase != null && !phrase.isBlank() && normalized.contains(phrase.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

    private int intEnv(
            String key,
            int defaultValue
    ) {
        try {
            return Integer.parseInt(System.getenv().getOrDefault(key, String.valueOf(defaultValue)));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String safe(
            String value
    ) {
        return value == null ? "" : value;
    }
}
