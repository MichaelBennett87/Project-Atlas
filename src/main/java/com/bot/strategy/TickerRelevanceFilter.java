package com.bot.strategy;

import com.bot.model.NewsEvent;
import com.bot.model.RelevanceDecision;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class TickerRelevanceFilter {

    private static final Map<String, List<String>> COMPANY_KEYWORDS = Map.ofEntries(
            Map.entry("AAPL", List.of("apple", "iphone", "ipad", "mac", "app store", "tim cook")),
            Map.entry("MSFT", List.of("microsoft", "azure", "windows", "xbox", "satya nadella")),
            Map.entry("NVDA", List.of("nvidia", "gpu", "cuda", "jensen huang")),
            Map.entry("TSLA", List.of("tesla", "cybertruck", "model y", "model 3")),
            Map.entry("AMZN", List.of("amazon", "aws", "prime", "andy jassy")),
            Map.entry("GOOGL", List.of("google", "alphabet", "youtube", "gemini", "sundar pichai")),
            Map.entry("GOOG", List.of("google", "alphabet", "youtube", "gemini", "sundar pichai")),
            Map.entry("META", List.of("meta", "facebook", "instagram", "whatsapp", "zuckerberg")),
            Map.entry("HIMS", List.of("hims", "hims & hers", "hims and hers", "hims & hers health")),
            Map.entry("BE", List.of("bloom energy", "bloom")),

            Map.entry("MU", List.of("micron", "micron technology", "dram", "nand", "memory chip")),
            Map.entry("AMD", List.of("advanced micro devices", "amd", "ryzen", "epyc")),
            Map.entry("MRVL", List.of("marvell", "marvell technology")),
            Map.entry("SNDK", List.of("sandisk", "western digital", "nand")),
            Map.entry("TSM", List.of("taiwan semiconductor", "tsmc", "taiwan semiconductor manufacturing")),
            Map.entry("TSEM", List.of("tower semiconductor", "tower semi", "silicon photonics")),
            Map.entry("SMCI", List.of("super micro", "supermicro", "super micro computer")),
            Map.entry("CRWV", List.of("coreweave")),
            Map.entry("NBIS", List.of("nebius")),

            Map.entry("RKLB", List.of("rocket lab")),
            Map.entry("SOUN", List.of("soundhound")),

            Map.entry("IDYA", List.of("ideaya biosciences", "ideaya", "ide892", "ide397")),
            Map.entry("SABS", List.of("sab biotherapeutics", "sab therapeutics")),
            Map.entry("ASND", List.of("ascendis pharma", "ascendis", "transcon pth")),
            Map.entry("GMAB", List.of("genmab", "epcoritamab")),
            Map.entry("INCY", List.of("incyte", "inca033989")),
            Map.entry("LLY", List.of("eli lilly", "lilly")),
            Map.entry("BLTE", List.of("belite bio", "belite", "tinlarebant")),
            Map.entry("AAPG", List.of("ascentage pharma", "olverembatinib", "lisaftoclax")),
            Map.entry("QGEN", List.of("qiagen", "qiacuity")),
            Map.entry("CMTL", List.of("comtech telecom", "comtech")),

            Map.entry("BOX", List.of("box", "aaron levie")),
            Map.entry("ADP", List.of("automatic data processing", "adp")),
            Map.entry("LZB", List.of("la-z-boy", "lazboy", "la z boy")),
            Map.entry("NIPG", List.of("nip group")),
            Map.entry("ROMA", List.of("roma green finance", "blueflare group", "roma")),
            Map.entry("FFAI", List.of("faraday future", "ff", "futurist")),
            Map.entry("EGY", List.of("vaalco energy", "vaalco")),
            Map.entry("ACIW", List.of("aci worldwide", "aci")),
            Map.entry("TDY", List.of("teledyne", "teledyne flir")),
            Map.entry("BLOX", List.of("blox")),
            Map.entry("GEME", List.of("geme")),
            Map.entry("OSEA", List.of("osea")),
            Map.entry("SOXX", List.of("soxx")),

            Map.entry("GILT", List.of("gilat", "gilat satellite networks")),
            Map.entry("AIOT", List.of("powerfleet", "power fleet")),
            Map.entry("BWAY", List.of("brainsway", "brainsway")),
            Map.entry("BHE", List.of("benchmark electronics", "benchmark")),
            Map.entry("OUST", List.of("ouster", "outer", "rev8", "os sensor")),
            Map.entry("BNAI", List.of("brand engagement network", "brand engagement")),
            Map.entry("EC", List.of("ecopetrol")),
            Map.entry("HALO", List.of("halozyme", "enhanz", "enhanze")),
            Map.entry("HITI", List.of("high tide", "northern helm")),
            Map.entry("BDSX", List.of("biodesix")),
            Map.entry("CLDX", List.of("celldex therapeutics", "celldex")),
            Map.entry("FTXL", List.of("first trust nasdaq semiconductor", "ftxl")),
            Map.entry("IGPT", List.of("invesco ai", "igpt")),
            Map.entry("DIA", List.of("dow jones", "dia")),
            Map.entry("QQQ", List.of("nasdaq 100", "qqq")),
            Map.entry("SPY", List.of("s&p 500", "sp 500", "spy")),
            Map.entry("AKAM", List.of("akamai")),
            Map.entry("PSN", List.of("parsons", "department of navy", "intelligence carry-on program")),
            Map.entry("LEU", List.of("centrus energy", "centrus energy corp", "section 382 rights agreement", "rights agreement")),
            Map.entry("GPUS", List.of("hyperscale data", "michigan data center campus")),
            Map.entry("CNTX", List.of("context therapeutics", "ctim-76", "cldn6")),
            Map.entry("VIAV", List.of("viavi", "cx300", "tetra radio")),
            Map.entry("WWR", List.of("westwater resources", "coosa graphite", "coosa county")),
            Map.entry("DSX", List.of("diana shipping")),
            Map.entry("GNK", List.of("genco", "genco's board")),
            Map.entry("SVA", List.of("sinovac", "sinovac biotech")),
            Map.entry("SYRE", List.of("spyre therapeutics", "spy002", "skyline study", "ulcerative colitis")),
            Map.entry("SNX", List.of("td synnex", "synnex", "td synnex to help", "post-quantum security")),
            Map.entry("NGS", List.of("natural gas services", "flatrock compression")),
            Map.entry("USAR", List.of("usa rare earth", "usa rare earths", "hydrometallurgical demonstration facility", "rare earth oxide")),
            Map.entry("LXEO", List.of("lexeo therapeutics", "sunrise-fa", "lx2006", "gene therapy")),
            Map.entry("GOGO", List.of("gogo", "galileo high performance connectivity", "falcon 7x", "falcon 8x")),
            Map.entry("AXSM", List.of("axsome", "axs-12", "solriamfetol")),
            Map.entry("JBL", List.of("jabil", "jbl")),
            Map.entry("ZVIA", List.of("zevia", "zevia pbc")),
            Map.entry("VFF", List.of("village farms", "delta greenhouse")),
            Map.entry("AXP", List.of("american express", "thefork")),
            Map.entry("TRIP", List.of("tripadvisor", "thefork")),
            Map.entry("PTCT", List.of("ptc therapeutics")),
            Map.entry("ALKS", List.of("alkermes", "alixorexton")),
            Map.entry("VENU", List.of("venu holding", "sunset amphitheater")),
            Map.entry("LFCR", List.of("lifecore biomedical", "global pharmaceutical company")),
            Map.entry("GNRC", List.of("generac", "large-mw generators")),
            Map.entry("HON", List.of("honeywell", "honeywell aerospace")),
            Map.entry("NIXX", List.of("nixxy", "tachyon9", "yotta data services", "nidar infrastructure")),
            Map.entry("ELTX", List.of("eltx", "quotation resumption", "trading halt")),
            Map.entry("NNDM", List.of("nano dimension", "infinite epigenetics")),
            Map.entry("LEN", List.of("lennar", "lennar corporation")),
            Map.entry("DDOG", List.of("datadog")),
            Map.entry("NOW", List.of("servicenow", "service now")),
            Map.entry("HEI", List.of("heico")),
            Map.entry("VNCE", List.of("vince holding", "vince")),
            Map.entry("CUZ", List.of("cousins props", "cousins properties")),
            Map.entry("SLG", List.of("sl green", "sl green realty")),
            Map.entry("VNO", List.of("vornado", "vornado realty trust")),
            Map.entry("NOK", List.of("nokia")),
            Map.entry("CUPR", List.of("cuprina")),
            Map.entry("IPX", List.of("iperionx", "camden silica sand", "covia solutions")),
            Map.entry("SBEV", List.of("splash beverage", "avicanna")),
            Map.entry("AIB", List.of("blockchain digital infrastructure", "aib data centers", "high-performance computing infrastructure")),
            Map.entry("CVKD", List.of("cadrenal therapeutics", "cad-1005")),
            Map.entry("KRC", List.of("kilroy realty")),
            Map.entry("NCEL", List.of("newcelx", "astrorx", "stem cell reports")),
            Map.entry("DEI", List.of("douglas emmett")),
            Map.entry("SKBL", List.of("cove kaz capital", "akbulak rare earth")),
            Map.entry("BNRG", List.of("brenmiller energy", "innova", "industrial decarbonization")),
            Map.entry("NBIX", List.of("neurocrine biosciences", "crenicity", "cahtalyst")),
            Map.entry("BAND", List.of("bandwidth")),
            Map.entry("TECH", List.of("bio-techne", "bio techne")),
            Map.entry("CSTM", List.of("castellum")),
            Map.entry("CTM", List.of("castellum")),
            Map.entry("SG", List.of("sweetgreen")),
            Map.entry("TOL", List.of("toll brothers")),
            Map.entry("BCC", List.of("boise cascade")),
            Map.entry("MCRI", List.of("atlantis", "monarch casino")),
            Map.entry("ORBN", List.of("oregon bancorp")),
            Map.entry("NECB", List.of("northeast community bancorp")),
            Map.entry("WASH", List.of("washington trust bancorp")),
            Map.entry("VSH", List.of("vishay", "vishay intertechnology")),
            Map.entry("ALRM", List.of("alarm.com", "alarmcom", "alarm dot com")),
            Map.entry("GPH", List.of("graphite one", "ohio battery materials facility")),
            Map.entry("GPHOF", List.of("graphite one", "ohio battery materials facility")),
            Map.entry("MRNA", List.of("moderna", "mrna-1010", "seasonal influenza vaccine")),
            Map.entry("DPZ", List.of("domino's", "dominos", "domino's pizza", "domino's®")),
            Map.entry("FTW", List.of("presidio production", "presidio production company")),
            Map.entry("FBIN", List.of("fortune brands", "fortune brands innovations", "moen")),
            Map.entry("INTC", List.of("intel", "intel corp", "intel corporation", "foundry", "advanced packaging")),
            Map.entry("SPCX", List.of("spacex", "space x", "space exploration technologies", "cursor acquisition", "spacex's")),
            Map.entry("ACN", List.of("accenture")),
            Map.entry("QS", List.of("quantumscape")),
            Map.entry("BFLY", List.of("butterfly network")),
            Map.entry("CAST", List.of("freecast")),
            Map.entry("TNON", List.of("tenon medical", "tenon")),
            Map.entry("SAGT", List.of("sagtec", "sagtec global")),
            Map.entry("STGW", List.of("stagwell", "media machine", "agentic media operating system")),
            Map.entry("IHT", List.of("innsuites", "innsuites hospitality", "reverse merger exploration")),
            Map.entry("BIAF", List.of("bioaffinity", "bioaffinity technologies", "gene silencing", "squamous skin cancer")),
            Map.entry("BIAFW", List.of("bioaffinity", "bioaffinity technologies", "gene silencing", "squamous skin cancer")),
            Map.entry("VTAK", List.of("catheter precision", "key intellectual property")),
            Map.entry("GNS", List.of("genius group", "company shares", "buys back")),
            Map.entry("CTRI", List.of("centuri", "customer awards")),
            Map.entry("ADCO", List.of("adcore", "tiktok", "channel sales partner")),
            Map.entry("ADCOF", List.of("adcore", "tiktok", "channel sales partner")),
            Map.entry("BTGO", List.of("bitgo", "defi vault", "morpho")),
            Map.entry("HYPD", List.of("hyperion defi", "blockdaemon", "hyperliquid staking"))
    );

    public boolean isRelevant(NewsEvent news) {
        RelevanceDecision decision =
                evaluate(news);

        return decision == RelevanceDecision.PRIMARY_SUBJECT ||
                decision == RelevanceDecision.POSSIBLE_SUBJECT;
    }

    public boolean isPrimarySubject(NewsEvent news) {
        return evaluate(news) == RelevanceDecision.PRIMARY_SUBJECT;
    }

    public RelevanceDecision evaluate(NewsEvent news) {
        if (isEtfBasketThemeArticle(news) && !hasDirectTickerOrCompanyEvidenceInHeadline(news)) {
            return RelevanceDecision.NOT_RELEVANT;
        }

        if (isPrivateCompanyValuationArticle(news) && !hasDirectTickerOrCompanyEvidenceInHeadline(news)) {
            return RelevanceDecision.NOT_RELEVANT;
        }

        if (isIndirectEcosystemMention(news) && !hasDirectTickerOrCompanyEvidenceInHeadline(news)) {
            return RelevanceDecision.NOT_RELEVANT;
        }

        if (isBroadMultiSymbolMentionArticle(news) && !hasDirectTickerOrCompanyEvidenceInHeadline(news)) {
            return RelevanceDecision.NOT_RELEVANT;
        }

        if (isFounderOrCelebrityCommentaryWithoutCompanyEvidence(news)) {
            return RelevanceDecision.NOT_RELEVANT;
        }

        if (hasDirectTickerOrCompanyEvidenceInHeadline(news)) {
            return RelevanceDecision.PRIMARY_SUBJECT;
        }

        int score =
                relevanceScore(news);

        if (isBroadMultiSymbolMentionArticle(news) && !hasDirectTickerOrCompanyEvidenceInHeadline(news)) {
            return score >= 2
                    ? RelevanceDecision.POSSIBLE_SUBJECT
                    : RelevanceDecision.NOT_RELEVANT;
        }

        if (score >= 4) {
            return RelevanceDecision.PRIMARY_SUBJECT;
        }

        if (score >= 3 && hasDirectCompanyKeywordInHeadline(news)) {
            return RelevanceDecision.PRIMARY_SUBJECT;
        }

        if (score >= 3 && isDirectAnalystActionHeadline(news)) {
            return RelevanceDecision.PRIMARY_SUBJECT;
        }

        if (score >= 2) {
            return RelevanceDecision.POSSIBLE_SUBJECT;
        }

        if (score >= 1 && hasObviousCompanySpecificCatalyst(news)) {
            return RelevanceDecision.POSSIBLE_SUBJECT;
        }

        if (score == 0 && hasDirectActionableHeadline(news) && !isBroadMultiSymbolMentionArticle(news)) {
            return RelevanceDecision.PRIMARY_SUBJECT;
        }

        if (score == 0 && hasDirectActionableHeadline(news)) {
            return RelevanceDecision.POSSIBLE_SUBJECT;
        }

        if (score == 0 && hasDirectCompanyGovernanceHeadline(news)) {
            return RelevanceDecision.POSSIBLE_SUBJECT;
        }

        return RelevanceDecision.NOT_RELEVANT;
    }

    public int relevanceScore(NewsEvent news) {
        if (news == null || news.getTicker() == null || news.getTicker().isBlank()) {
            return 0;
        }

        String ticker =
                news.getTicker().toUpperCase(Locale.ROOT);

        String headline =
                safe(news.getHeadline()).toLowerCase(Locale.ROOT);

        String content =
                safe(news.getContent()).toLowerCase(Locale.ROOT);

        String fullText =
                headline + " " + content;

        int score = 0;

        if (containsTickerToken(headline, ticker)) {
            score += 3;
        }

        if (containsTickerToken(content, ticker)) {
            score += 1;
        }

        int tickerMentions =
                countTickerToken(fullText, ticker);

        if (tickerMentions >= 2) {
            score += 2;
        }

        List<String> keywords =
                COMPANY_KEYWORDS.get(ticker);


        if (keywords != null) {
            int keywordHits = 0;

            for (String keyword : keywords) {
                String normalizedKeyword =
                        keyword.toLowerCase(Locale.ROOT);

                if (headline.contains(normalizedKeyword)) {
                    score += 3;
                    keywordHits++;
                } else if (content.contains(normalizedKeyword)) {
                    score += 1;
                    keywordHits++;
                }
            }

            if (keywordHits >= 2) {
                score += 2;
            }
        }

        if (!hasDirectTickerOrCompanyEvidenceInHeadline(news) && isEtfBasketThemeArticle(news)) {
            score = 0;
        }

        if (!hasDirectTickerOrCompanyEvidenceInHeadline(news) && isPrivateCompanyValuationArticle(news)) {
            score = 0;
        }

        if (!hasDirectTickerOrCompanyEvidenceInHeadline(news) && isIndirectEcosystemMention(news)) {
            score = Math.max(0, score - 4);
        }

        return score;
    }

    private boolean hasDirectTickerOrCompanyEvidenceInHeadline(NewsEvent news) {
        if (news == null || news.getTicker() == null) {
            return false;
        }

        String ticker = news.getTicker().toUpperCase(Locale.ROOT);
        String headline = safe(news.getHeadline()).toLowerCase(Locale.ROOT);

        return containsTickerToken(headline, ticker) || hasDirectCompanyKeywordInHeadline(news);
    }


    private boolean isEtfBasketThemeArticle(NewsEvent news) {
        if (news == null) {
            return false;
        }

        String headline = safe(news.getHeadline()).toLowerCase(Locale.ROOT);
        String content = safe(news.getContent()).toLowerCase(Locale.ROOT);
        String text = headline + " " + content;

        boolean etfThemeLanguage = containsAny(
                text,
                "etf takeover",
                "thematic etf",
                "thematic etfs",
                "value funds",
                "fund holdings",
                "fund flow",
                "fund flows",
                "exchange-traded fund",
                "exchange traded fund",
                "is suddenly everywhere",
                "from value funds to thematic etfs"
        );

        if (!etfThemeLanguage) {
            return false;
        }

        String ticker = news.getTicker() == null ? "" : news.getTicker().toUpperCase(Locale.ROOT);
        Set<String> commonBasketTickers = Set.of(
                "HALX",
                "NASA",
                "SCHV",
                "SPCI",
                "UFO",
                "UFOD",
                "SPY",
                "QQQ",
                "DIA",
                "SOXX",
                "FTXL",
                "IGPT"
        );

        return commonBasketTickers.contains(ticker) || text.contains("etf") || text.contains("fund");
    }

    private boolean isPrivateCompanyValuationArticle(NewsEvent news) {
        if (news == null) {
            return false;
        }

        String text =
                (safe(news.getHeadline()) + " " + safe(news.getContent()))
                        .toLowerCase(Locale.ROOT);

        boolean privateValuationLanguage = containsAny(
                text,
                "valuation ahead of potential ipo",
                "ahead of potential ipo",
                "potential 2027 ipo",
                "potential ipo",
                "pre-ipo",
                "pre ipo",
                "new funding round",
                "funding round",
                "private funding",
                "secondary share sales",
                "raise private funding",
                "raising private funding",
                "venture funding",
                "staggering valuation",
                "reportedly eyes",
                "eyes valuation",
                "valued at",
                "valuation to between",
                "valuation of between"
        );

        if (!privateValuationLanguage) {
            return false;
        }

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

    private boolean isIndirectEcosystemMention(NewsEvent news) {
        if (news == null) {
            return false;
        }

        String headline = safe(news.getHeadline()).toLowerCase(Locale.ROOT);
        String content = safe(news.getContent()).toLowerCase(Locale.ROOT);
        String text = headline + " " + content;

        if (hasDirectTickerOrCompanyEvidenceInHeadline(news)) {
            return false;
        }

        if (containsAny(
                headline,
                "reportedly eyes",
                "ahead of potential ipo",
                "potential ipo",
                "funding round",
                "valuation"
        )) {
            return true;
        }

        return containsAny(
                text,
                "competitor",
                "rival",
                "customers include",
                "investors include",
                "backed by",
                "alongside",
                "ecosystem",
                "market for",
                "similar to",
                "competes with"
        );
    }


    private boolean isBroadMultiSymbolMentionArticle(NewsEvent news) {
        if (news == null) {
            return false;
        }

        String text =
                (safe(news.getHeadline()) + " " + safe(news.getContent()))
                        .toLowerCase(Locale.ROOT);

        int quoteMentions =
                countOccurrences(text, "/quote/") +
                        countOccurrences(text, "data-ticker=") +
                        countOccurrences(text, "nasdaq:") +
                        countOccurrences(text, "nyse:");

        if (quoteMentions >= 3) {
            return true;
        }

        return containsAny(
                text,
                "symbols=[",
                "stocks to watch",
                "stocks are moving",
                "why these stocks",
                "tickers mentioned",
                "among other stocks",
                "basket of stocks",
                "sector peers",
                "peer stocks",
                "ecosystem stocks"
        );
    }

    private boolean isFounderOrCelebrityCommentaryWithoutCompanyEvidence(NewsEvent news) {
        if (news == null || news.getTicker() == null) {
            return false;
        }

        String ticker =
                news.getTicker().toUpperCase(Locale.ROOT);

        String headline =
                safe(news.getHeadline()).toLowerCase(Locale.ROOT);

        if (hasDirectTickerOrCompanyEvidenceInHeadline(news)) {
            return false;
        }

        if ("TSLA".equals(ticker) && containsAny(headline, "elon musk", "musk says", "musk reaffirms")) {
            return true;
        }

        if (("AMZN".equals(ticker) || "MSFT".equals(ticker) || "NVDA".equals(ticker)) &&
                containsAny(headline, "elon musk", "musk says", "musk reaffirms", "space x", "spacex")) {
            return true;
        }

        return false;
    }

    private int countOccurrences(String text, String needle) {
        if (text == null || needle == null || needle.isBlank()) {
            return 0;
        }

        int count = 0;
        int index = 0;

        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }

        return count;
    }

    private boolean containsAny(String text, String... phrases) {
        if (text == null || phrases == null) {
            return false;
        }

        String normalized = text.toLowerCase(Locale.ROOT);

        for (String phrase : phrases) {
            if (phrase != null && !phrase.isBlank() && normalized.contains(phrase.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

    private boolean hasDirectCompanyKeywordInHeadline(NewsEvent news) {
        if (news == null || news.getTicker() == null) {
            return false;
        }

        String ticker = news.getTicker().toUpperCase(Locale.ROOT);
        String headline = safe(news.getHeadline()).toLowerCase(Locale.ROOT);
        List<String> keywords = COMPANY_KEYWORDS.get(ticker);

        if (keywords == null) {
            return false;
        }

        for (String keyword : keywords) {
            if (headline.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

    private boolean isDirectAnalystActionHeadline(NewsEvent news) {
        if (news == null) {
            return false;
        }

        String text = (safe(news.getHeadline()) + " " + safe(news.getContent()))
                .toLowerCase(Locale.ROOT);

        return text.contains("upgrades") ||
                text.contains("initiates coverage") ||
                text.contains("raises price target") ||
                text.contains("maintains buy") ||
                text.contains("maintains outperform") ||
                text.contains("maintains overweight") ||
                text.contains("reiterates buy") ||
                text.contains("reiterates outperform") ||
                text.contains("reiterates overweight");
    }

    private boolean hasDirectCompanyGovernanceHeadline(NewsEvent news) {
        if (news == null) {
            return false;
        }

        String text =
                (safe(news.getHeadline()) + " " + safe(news.getContent()))
                        .toLowerCase(Locale.ROOT);

        if (containsAny(
                text,
                "investor alert",
                "shareholder alert",
                "class action",
                "lawsuit",
                "investigates",
                "investigation"
        )) {
            return false;
        }

        return containsAny(
                text,
                "rights agreement",
                "section 382 rights agreement",
                "extension of section 382 rights agreement",
                "tax benefits preservation plan",
                "stockholder rights plan",
                "shareholder rights plan",
                "minimum bid price deficiency",
                "nasdaq notification regarding minimum bid",
                "nasdaq deficiency",
                "deficiency notice",
                "at-the-market equity program",
                "at the market equity program"
        );
    }

    private boolean hasDirectActionableHeadline(NewsEvent news) {
        if (news == null) {
            return false;
        }

        String text =
                (safe(news.getHeadline()) + " " + safe(news.getContent()))
                        .toLowerCase(Locale.ROOT);

        if (containsAny(
                text,
                "investor alert",
                "shareholder alert",
                "class action",
                "lawsuit",
                "offering",
                "private placement",
                "annual meeting",
                "quarterly dividend",
                "named one of",
                "recognized as",
                "award"
        )) {
            return false;
        }

        return containsAny(
                text,
                "new order",
                "contract awarded",
                "awarded contract",
                "purchase order",
                "selected by",
                "signs agreement",
                "strategic partnership",
                "receives fda",
                "fda approves",
                "phase 3",
                "positive topline",
                "meets primary endpoint",
                "to be acquired",
                "completes acquisition",
                "raises guidance",
                "introduces",
                "launches",
                "unveils",
                "commercial availability",
                "new product",
                "new platform"
        );
    }

    private boolean hasObviousCompanySpecificCatalyst(NewsEvent news) {
        if (news == null) {
            return false;
        }

        String text =
                (safe(news.getHeadline()) + " " + safe(news.getContent()))
                        .toLowerCase(Locale.ROOT);

        return text.contains("acquires") ||
                text.contains("acquisition") ||
                text.contains("merger") ||
                text.contains("regains compliance") ||
                text.contains("nasdaq listing requirements") ||
                text.contains("awarded") ||
                text.contains("contract") ||
                text.contains("agreement") ||
                text.contains("collaborates") ||
                text.contains("partnership") ||
                text.contains("phase 1") ||
                text.contains("phase 2") ||
                text.contains("phase 3") ||
                text.contains("meets primary endpoint") ||
                text.contains("clinical data") ||
                text.contains("fda") ||
                text.contains("accelerated approval") ||
                text.contains("orphan status") ||
                text.contains("rare disease designation") ||
                text.contains("commissioned") ||
                text.contains("facility") ||
                text.contains("join russell") ||
                text.contains("index addition") ||
                text.contains("production") ||
                text.contains("sale-leaseback") ||
                text.contains("spin-off") ||
                text.contains("spinoff") ||
                text.contains("shares are trading higher") ||
                text.contains("convertible notes offering") ||
                text.contains("activist investor") ||
                text.contains("urges") ||
                text.contains("strategic investment") ||
                text.contains("proof-of-concept clinical trial") ||
                text.contains("peer-reviewed study");
    }

    private boolean containsTickerToken(String text, String ticker) {
        return countTickerToken(text, ticker) > 0;
    }

    private int countTickerToken(String text, String ticker) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        String normalized =
                text.toUpperCase(Locale.ROOT);

        String target =
                ticker.toUpperCase(Locale.ROOT);

        int count = 0;
        int index = 0;

        while ((index = normalized.indexOf(target, index)) >= 0) {
            boolean leftBoundary =
                    index == 0 ||
                            !Character.isLetterOrDigit(normalized.charAt(index - 1));

            int end =
                    index + target.length();

            boolean rightBoundary =
                    end >= normalized.length() ||
                            !Character.isLetterOrDigit(normalized.charAt(end));

            if (leftBoundary && rightBoundary) {
                count++;
            }

            index = end;
        }

        return count;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}