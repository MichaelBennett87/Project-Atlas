package com.bot.news;

import java.util.HashMap;
import java.util.Map;

public class CatalystScorer {

    private final Map<String, Double> weights = new HashMap<>();

    public CatalystScorer() {

        // FDA / Biotech
        weights.put("fda approval", 15.0);
        weights.put("fda approves", 15.0);
        weights.put("approved by the fda", 15.0);
        weights.put("regulatory approval", 13.0);
        weights.put("breakthrough designation", 14.0);
        weights.put("fast track designation", 12.0);
        weights.put("priority review", 12.0);
        weights.put("phase 3 success", 13.0);
        weights.put("phase iii success", 13.0);
        weights.put("positive phase 3", 13.0);
        weights.put("positive phase iii", 13.0);
        weights.put("positive trial", 12.0);
        weights.put("trial met primary endpoint", 13.0);
        weights.put("primary endpoint met", 13.0);
        weights.put("endpoint achieved", 12.0);
        weights.put("positive clinical data", 12.0);

        // Earnings / Guidance
        weights.put("raises guidance", 12.0);
        weights.put("raised guidance", 12.0);
        weights.put("guidance raised", 12.0);
        weights.put("raises outlook", 11.0);
        weights.put("raised outlook", 11.0);
        weights.put("boosts forecast", 10.0);
        weights.put("increases forecast", 10.0);
        weights.put("record revenue", 11.0);
        weights.put("record sales", 10.0);
        weights.put("record earnings", 11.0);
        weights.put("record profit", 10.0);
        weights.put("record results", 11.0);
        weights.put("reports record results", 11.0);
        weights.put("record quarterly", 10.0);
        weights.put("earnings beat", 10.0);
        weights.put("eps beats", 10.0);
        weights.put("eps beat", 10.0);
        weights.put("adj. eps", 9.0);
        weights.put("adjusted eps", 9.0);
        weights.put("beats estimates", 10.0);
        weights.put("beats expectations", 10.0);
        weights.put("revenue beat", 9.0);
        weights.put("revenue beats", 9.0);
        weights.put("sales beat", 9.0);
        weights.put("sales beats", 9.0);
        weights.put("profit beat", 9.0);
        weights.put("strong quarter", 7.0);
        weights.put("better-than-expected", 8.0);
        weights.put("better than expected", 8.0);

        // Analyst
        weights.put("upgraded", 8.0);
        weights.put("analyst upgrade", 8.0);
        weights.put("upgrades to buy", 9.0);
        weights.put("buy rating", 8.0);
        weights.put("strong buy", 9.0);
        weights.put("outperform rating", 8.0);
        weights.put("price target raised", 9.0);
        weights.put("raises price target", 9.0);
        weights.put("target raised", 8.0);
        weights.put("top pick", 9.0);
        weights.put("bullish on", 5.0);

        // M&A
        weights.put("strategic acquisition", 10.0);
        weights.put("acquisition agreement", 10.0);
        weights.put("to acquire", 9.0);
        weights.put("acquires", 9.0);
        weights.put("acquired", 9.0);
        weights.put("acquisition completed", 9.0);
        weights.put("merger agreement", 10.0);
        weights.put("merger approved", 9.0);
        weights.put("deal approved", 9.0);
        weights.put("transaction approved", 9.0);
        weights.put("buyout offer", 10.0);

        // Contracts / Partnerships
        weights.put("strategic partnership", 8.0);
        weights.put("partnership with", 7.0);
        weights.put("partners with", 7.0);
        weights.put("collaboration agreement", 7.0);
        weights.put("signs agreement", 8.0);
        weights.put("signed agreement", 8.0);
        weights.put("agreement with", 7.0);
        weights.put("contract award", 8.0);
        weights.put("awarded contract", 8.0);
        weights.put("major contract", 9.0);
        weights.put("government contract", 9.0);
        weights.put("multi-year contract", 9.0);
        weights.put("multiyear contract", 9.0);
        weights.put("supply agreement", 7.0);
        weights.put("licensing agreement", 7.0);
        weights.put("option license agreement", 7.0);
        weights.put("research collaboration", 6.0);
        weights.put("strategic research collaboration", 7.0);
        weights.put("non-binding mou", 3.5);
        weights.put("signs non-binding mou", 3.5);

        // Product / Commercial Launch
        weights.put("product launch", 7.0);
        weights.put("launches new", 6.0);
        weights.put("commercial launch", 8.0);
        weights.put("rolls out", 5.0);
        weights.put("unveils", 5.0);
        weights.put("notice of allowance", 6.0);
        weights.put("new platform", 6.0);
        weights.put("next-generation", 6.0);
        weights.put("next generation", 6.0);

        // AI / Technology
        weights.put("artificial intelligence", 5.0);
        weights.put("generative ai", 7.0);
        weights.put("ai-powered", 6.0);
        weights.put("ai platform", 6.0);
        weights.put("ai deal", 6.0);
        weights.put("ai partnership", 7.0);
        weights.put("data center expansion", 7.0);
        weights.put("cloud growth", 6.0);

        // Growth / Demand / Adoption
        weights.put("record-breaking traffic", 7.0);
        weights.put("record breaking traffic", 7.0);
        weights.put("strong demand", 6.0);
        weights.put("surging demand", 7.0);
        weights.put("demand surge", 7.0);
        weights.put("accelerating growth", 6.0);
        weights.put("market expansion", 5.0);
        weights.put("expands into", 5.0);
        weights.put("user growth", 5.0);
        weights.put("subscriber growth", 5.0);
        weights.put("customer growth", 5.0);
        weights.put("adoption growth", 5.0);

        // Shareholder Value
        weights.put("buyback", 8.0);
        weights.put("share buyback", 8.0);
        weights.put("share repurchase", 8.0);
        weights.put("stock repurchase", 8.0);
        weights.put("dividend increase", 7.0);
        weights.put("raises dividend", 7.0);
        weights.put("special dividend", 8.0);

        // Institutional / Insider
        weights.put("insider buying", 8.0);
        weights.put("insider purchase", 8.0);
        weights.put("ceo buys", 8.0);
        weights.put("director buys", 8.0);
        weights.put("stake increase", 7.0);
        weights.put("raises stake", 7.0);
        weights.put("activist stake", 8.0);

        // Capital-structure / restructuring taxonomy. These are deliberately
        // modest unless paired with true market momentum. They allow the engine
        // to distinguish a neutral/possibly constructive strategic investment
        // from a clearly toxic public offering, without letting news alone trade.
        weights.put("strategic investment", 5.0);
        weights.put("investment by", 4.0);
        weights.put("equity investment", 4.0);
        weights.put("completion of restructuring", 3.5);
        weights.put("restructuring conditions", 3.5);
        weights.put("debt restructuring completed", 3.5);
    }

    public double score(String text) {

        if (text == null || text.isBlank()) {
            return 0.0;
        }

        String lower = normalize(text);

        if (isClearlyNegativeOrWeak(lower)) {
            return 0.0;
        }

        double score = 0.0;

        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            if (lower.contains(entry.getKey())) {
                score += entry.getValue();
            }
        }

        return score;
    }

    private boolean isClearlyNegativeOrWeak(String text) {
        if (containsAny(text,
                "class action",
                "lawsuit",
                "investigation",
                "probe",
                "sec probe",
                "doj probe",
                "bankruptcy",
                "going concern",
                "delisting",
                "downgrade",
                "cuts guidance",
                "lowers guidance",
                "misses estimates",
                "missed estimates")) {
            return true;
        }

        boolean toxicFinancing = containsAny(text,
                "offering prices",
                "public offering",
                "secondary offering",
                "registered direct offering",
                "share offering",
                "stock offering",
                "at-the-market offering",
                "atm offering");
        boolean strategicCapital = containsAny(text,
                "strategic investment",
                "investment by",
                "equity investment",
                "completion of restructuring",
                "restructuring conditions");
        return toxicFinancing && !strategicCapital;
    }

    private boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                return true;
            }
        }

        return false;
    }

    private String normalize(String text) {
        return text
                .toLowerCase()
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("—", " ")
                .replace("-", " ")
                .replace("_", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}