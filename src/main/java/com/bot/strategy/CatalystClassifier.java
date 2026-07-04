package com.bot.strategy;

import com.bot.model.CatalystResult;
import com.bot.model.CatalystType;
import com.bot.model.NewsEvent;

import java.util.Locale;

public class CatalystClassifier {

    public CatalystResult classify(NewsEvent news) {
        if (news == null) {
            return result(CatalystType.UNKNOWN, "No news event provided");
        }

        String headlineText =
                normalize(news.getHeadline());

        String text =
                normalize(news.fullText());

        CatalystResult headlineOnlyMatched =
                matchHeadlineOnlyNonTradeAndFinancingSafeguards(headlineText);
        if (headlineOnlyMatched != null) return headlineOnlyMatched;

        CatalystResult verifiedLiveCandidate =
                matchVerifiedLiveCandidateBeforeBroadNoise(headlineText, text);
        if (verifiedLiveCandidate != null) return verifiedLiveCandidate;

        if (isWeakCommentary(text)) {
            return result(
                    CatalystType.STOCK_PICK_ARTICLE,
                    "Weak commentary / stock-pick article rejected before catalyst scoring"
            );
        }

        CatalystResult matched;

        // Highest-priority financing / listing-compliance gates. These must run before
        // generic administrative filters because headlines such as "at-the-market equity
        // program" or "minimum bid price deficiency" can otherwise get swallowed as
        // harmless corporate updates.
        matched = matchHighPriorityFinancingAndComplianceBeforeAdmin(text);
        if (matched != null) return matched;

        // Explicit class-action / securities-fraud headlines must be captured
        // before generic administrative or conference filters. This prevents
        // legal notices from falling into NO_MATERIAL_NEWS just because they
        // contain harmless-looking phrases such as "notice", "on behalf of",
        // "firm", or dates.
        matched = matchExplicitSecuritiesLitigationBeforeAdmin(text);
        if (matched != null) return matched;

        // Highest-priority no-trade administrative gate. This must run before
        // litigation, offering, M&A, clinical, or partnership scoring because routine
        // dividend/fund/conference headlines contain words such as common, preferred,
        // stock, investment, adviser, and distribution that can accidentally trip
        // positive or negative catalyst rules.
        matched = matchRoutineDividendFundAndConferenceNoTrade(text);
        if (matched != null) return matched;

        // High-priority operational/public-awareness/partner-tier noise gate.
        // These headlines contain words such as traffic, partner, status, water,
        // customer, and report that can accidentally trip M&A, strategic-investment,
        // partnership, or litigation rules even though they are not tradeable catalysts.
        matched = matchOperationalMetricsPartnerStatusAndPublicAwarenessNoTrade(text);
        if (matched != null) return matched;

        // Mining technical reports / mineral-resource filings are common PR items.
        // They are not securities-litigation headlines and should never be routed
        // through the law-firm filter just because they contain words like filing,
        // report, district, or resources.
        matched = matchMiningTechnicalReportNoTrade(text);
        if (matched != null) return matched;

        // Highest-priority non-trade administrative gate. These headlines contain
        // words such as voting, shareholders, approval, pharmaceutical, or annual
        // meeting that can accidentally trip FDA/offering/M&A rules, but they are
        // not actionable trading catalysts.
        matched = matchCorporateAdministrativeNoTrade(text);
        if (matched != null) return matched;

        // Highest-priority safety gate: negative/legal/financing headlines often contain
        // words like acquire, offer, merger, price, premium, investigation, or data.
        // Those terms must be classified before any positive catalyst rule can see them.
        matched = matchNegativeHeadlineCamouflage(text);
        if (matched != null) return matched;

        // Law-firm and shareholder-investigation copy often contains merger / offer / shares language.
        // Classify it before award/admin filters too. Phrases like "M&A Class Action Firm"
        // must never be swallowed as generic no-material news or converted into M&A.
        matched = matchSecuritiesLitigation(text);
        if (matched != null) return matched;

        // These are common press-release false positives that were showing up in live logs.
        // They must be removed before broad offering, M&A, or clinical keyword matching.
        matched = matchTickerChangeAndLowMaterialityCorporateAdmin(text);
        if (matched != null) return matched;

        matched = matchAwardReviewAndPublicityNoise(text);
        if (matched != null) return matched;

        matched = matchBoardAppointmentAndExecutiveAdmin(text);
        if (matched != null) return matched;

        // Debt tender / note offering / securities-offer press releases were being misread
        // as clinical/data or M&A catalysts because of generic words like offer, data,
        // securities, purchase, and pricing. Keep them out of all long-catalyst paths.
        matched = matchDebtSecuritiesOffering(text);
        if (matched != null) return matched;

        // Capital raises / offerings must be separated before M&A.
        // Press releases often contain "offer", "shares", "subscription", or "preferred" language,
        // but those are dilution / financing events, not takeover bids.
        matched = matchDilutionOfferingsAndFinancing(text);
        if (matched != null) return matched;

        matched = matchHighPriorityAssetSale(text);
        if (matched != null) return matched;

        matched = matchShareholderApprovedAcquisition(text);
        if (matched != null) return matched;

        matched = matchHighPriorityMergerAcquisition(text);
        if (matched != null) return matched;

        matched = matchPropertyAcquisitionBeforeBroadMerger(text);
        if (matched != null) return matched;

        matched = matchHighPriorityInformationalNoise(text);
        if (matched != null) return matched;

        matched = matchPrivateCompanyValuationAndFundingArticle(text);
        if (matched != null) return matched;

        matched = matchFundEtfThemeCommentary(text);
        if (matched != null) return matched;

        matched = matchHighPriorityIndustryReadthrough(text);
        if (matched != null) return matched;

        matched = matchProductLaunchFalsePositiveNoise(text);
        if (matched != null) return matched;

        matched = matchRegulatoryPoliticalCommentary(text);
        if (matched != null) return matched;

        matched = matchMiningProjectUpdateBeforeGeopolitical(text);
        if (matched != null) return matched;

        matched = matchGovernmentPaymentRateCatalyst(text);
        if (matched != null) return matched;

        matched = matchStrategicInvestmentArticle(text);
        if (matched != null) return matched;

        matched = matchExecutiveAiAndIndustryCommentary(text);
        if (matched != null) return matched;

        matched = matchHighPriorityExplainerArticle(text);
        if (matched != null) return matched;

        matched = matchHighPriorityEarningsTranscript(text);
        if (matched != null) return matched;

        matched = matchHighPriorityGovernanceWarrantCancellation(text);
        if (matched != null) return matched;

        matched = matchHighPriorityAutonomousRoadmap(text);
        if (matched != null) return matched;

        matched = matchHighPriorityGovernmentSelection(text);
        if (matched != null) return matched;

        matched = matchHighPriorityComparativeStudy(text);
        if (matched != null) return matched;

        matched = matchHighPriorityExpansionDeal(text);
        if (matched != null) return matched;

        matched = matchHighPriorityActivistInvestorArticle(text);
        if (matched != null) return matched;

        matched = matchHighPriorityOperationalGrowth(text);
        if (matched != null) return matched;

        matched = matchCriticalNegative(text);
        if (matched != null) return matched;

        matched = matchTradingStatus(text);
        if (matched != null) return matched;

        matched = matchListingCompliance(text);
        if (matched != null) return matched;

        matched = matchHighPriorityBroadMarketConcentration(text);
        if (matched != null) return matched;

        matched = matchHighPriorityCreditFacility(text);
        if (matched != null) return matched;

        matched = matchHighPriorityNonBiotechPartnership(text);
        if (matched != null) return matched;

        matched = matchHighPriorityClinicalTrialInitiation(text);
        if (matched != null) return matched;

        matched = matchHighPriorityClinicalData(text);
        if (matched != null) return matched;

        matched = matchHighPriorityAnalystForecastArticle(text);
        if (matched != null) return matched;

        matched = matchHighPriorityProductUpgrade(text);
        if (matched != null) return matched;

        matched = matchHighPriorityPermitApplication(text);
        if (matched != null) return matched;

        matched = matchDilutionOfferingsAndFinancing(text);
        if (matched != null) return matched;

        matched = matchBiotechAndHealthcare(text);
        if (matched != null) return matched;

        matched = matchEarningsGuidanceAndCompanyOutlook(text);
        if (matched != null) return matched;

        matched = matchDealsMergersStrategicActionsAndAssetSales(text);
        if (matched != null) return matched;

        matched = matchContractsOrdersAndCustomers(text);
        if (matched != null) return matched;

        matched = matchOperationsProductionAndResourceDevelopment(text);
        if (matched != null) return matched;

        matched = matchCapitalReturnsAndBalanceSheet(text);
        if (matched != null) return matched;

        matched = matchProductTechnologyAndLaunches(text);
        if (matched != null) return matched;

        matched = matchPartnershipsLicensingAndDistribution(text);
        if (matched != null) return matched;

        matched = matchAiInfrastructureAndCompute(text);
        if (matched != null) return matched;

        matched = matchIpoIndexAndListings(text);
        if (matched != null) return matched;

        matched = matchAnalystAndInstitutional(text);
        if (matched != null) return matched;

        matched = matchSecInsiderOptionsAndTradingMentions(text);
        if (matched != null) return matched;

        matched = matchManagementGovernanceAndCorporateUpdates(text);
        if (matched != null) return matched;

        matched = matchLegalRegulatoryAndRisk(text);
        if (matched != null) return matched;

        matched = matchMacroPoliticsCryptoAndCommodities(text);
        if (matched != null) return matched;

        matched = matchTechnicalInformationalAndMediaArticles(text);
        if (matched != null) return matched;

        matched = matchPositiveBusinessMomentum(text);
        if (matched != null) return matched;

        return result(
                CatalystType.UNKNOWN,
                "No strong catalyst phrase found"
        );
    }



    private CatalystResult matchRegulatoryPoliticalCommentary(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        boolean policyOrPoliticalActor = containsAny(
                text,
                "ted cruz",
                "senator",
                "senators",
                "house lawmakers",
                "lawmakers",
                "congress",
                "congressional",
                "white house",
                "administration",
                "backs act",
                "backs bill",
                "new bill",
                "bipartisan bill",
                "legislation",
                "criminalize",
                "to criminalize",
                "act to criminalize",
                "take it down act",
                "online safety act"
        );

        boolean broadPolicyTopic = containsAny(
                text,
                "ai deepfake",
                "ai deepfakes",
                "deepfake",
                "deepfakes",
                "child or family",
                "no child or family",
                "platform accountability",
                "social media regulation",
                "content moderation",
                "data privacy bill",
                "privacy legislation"
        );

        boolean companySpecificMaterialRegulatoryAction = containsAny(
                text,
                "fda approves",
                "fda clears",
                "sec approves",
                "wins approval",
                "granted approval",
                "receives approval",
                "awarded contract",
                "selected by",
                "government contract"
        );

        if ((policyOrPoliticalActor || broadPolicyTopic) && !companySpecificMaterialRegulatoryAction) {
            return result(
                    CatalystType.REGULATORY_POLITICAL_COMMENTARY,
                    "Broad political/regulatory commentary rejected before product-launch scoring"
            );
        }

        return null;
    }

    private CatalystResult matchMiningProjectUpdateBeforeGeopolitical(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        boolean miningCompanyOrProjectContext = containsAny(
                text,
                "collective mining",
                "paramount gold",
                "banyan gold",
                "cerrado gold",
                "generation mining",
                "mining announces",
                "gold project",
                "silver project",
                "copper project",
                "lithium project",
                "uranium project",
                "mineral project",
                "mine project",
                "development of the",
                "support development of",
                "project development",
                "workstreams required to support development",
                "considerable progress on workstreams",
                "npv of $",
                "net present value",
                "preliminary economic assessment",
                "feasibility study",
                "technical report",
                "resource estimate",
                "mineral resource",
                "drilling",
                "intersects",
                "g/t au",
                "guayables project",
                "sleeper gold project"
        );

        boolean actualGeopoliticalConflict = containsAny(
                text,
                "iran",
                "israel",
                "missile strike",
                "ceasefire",
                "war",
                "sanctions",
                "strait of hormuz",
                "putin",
                "russia",
                "ukraine"
        );

        if (miningCompanyOrProjectContext && !actualGeopoliticalConflict) {
            return result(
                    CatalystType.EXPLORATION_PROGRAM,
                    "Mining/resource project update detected before geopolitical scoring"
            );
        }

        return null;
    }



    private CatalystResult matchPropertyAcquisitionBeforeBroadMerger(String text) {
        if (containsAny(
                text,
                "property acquisition",
                "acquires property",
                "acquired property",
                "acquires land",
                "acquired land",
                "acquires real estate",
                "acquired real estate",
                "realty corporation acquires",
                "reit acquires",
                "acquires industrial property",
                "acquires warehouse",
                "acquires distribution center",
                "land acquisition",
                "mineral property acquisition"
        )) {
            return result(
                    CatalystType.PROPERTY_ACQUISITION,
                    "Property / real-estate acquisition language separated before broad M&A or litigation scoring"
            );
        }

        return null;
    }

    private CatalystResult matchTickerChangeAndLowMaterialityCorporateAdmin(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        if (containsAny(
                text,
                "ticker symbol change",
                "symbol change",
                "changes ticker",
                "change its ticker",
                "new ticker symbol",
                "name and ticker change",
                "corporate name change",
                "changes corporate name",
                "changed its name to",
                "will trade under the symbol",
                "to begin trading under",
                "reverse stock split and name change"
        )) {
            return result(
                    CatalystType.NAME_TICKER_CHANGE,
                    "Ticker/name-change administrative news separated before litigation, offering, or M&A scoring"
            );
        }

        if (containsAny(
                text,
                "annual stockholder meeting results",
                "annual shareholder meeting results",
                "annual general meeting",
                "annual general meeting of shareholders",
                "voting results of its annual general meeting",
                "results of annual general meeting",
                "results of annual meeting",
                "annual meeting of shareholders",
                "shareholders meeting results",
                "files annual report",
                "files quarterly report",
                "conference participation",
                "to participate at",
                "to present at",
                "investor conference",
                "fireside chat"
        )) {
            return result(
                    CatalystType.NO_MATERIAL_NEWS,
                    "Low-materiality corporate administration / conference news separated before catalyst scoring"
            );
        }

        return null;
    }

    private CatalystResult matchBoardAppointmentAndExecutiveAdmin(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        boolean boardOnly = containsAny(
                text,
                "appointed to the board",
                "appoints to board",
                "appoints independent director",
                "appoints new director",
                "joins board of directors",
                "joins the board",
                "elected to board",
                "board appointment",
                "announces board appointment",
                "named to board",
                "named independent director"
        );

        boolean executiveRole = containsAny(
                text,
                "chief executive officer",
                "chief financial officer",
                "chief operating officer",
                "chief technology officer",
                "chief medical officer",
                "president",
                "ceo",
                "cfo",
                "coo",
                "cto",
                "cmo"
        );

        if (boardOnly) {
            return result(
                    CatalystType.GOVERNANCE_CHANGE,
                    "Board/director appointment separated before broad investment or M&A scoring"
            );
        }

        if (containsAny(
                text,
                "names chief",
                "named chief",
                "appoints chief",
                "appointed chief",
                "promotes cfo",
                "promotes chief",
                "names president",
                "appointed president"
        ) && executiveRole) {
            return result(
                    CatalystType.EXECUTIVE_HIRE,
                    "Executive appointment separated before broad investment or M&A scoring"
            );
        }

        return null;
    }




    private CatalystResult matchHeadlineOnlyNonTradeAndFinancingSafeguards(String headline) {
        if (headline == null || headline.isBlank()) {
            return null;
        }

        /*
         * Use headline-only safety gates before full-body matching. Benzinga PR
         * bodies often contain boilerplate investor/legal language that can
         * poison otherwise benign headlines when the classifier scans fullText().
         * These headline patterns are not buy catalysts and should never become
         * SECURITIES_LITIGATION or M&A because of body boilerplate.
         */
        if (containsAny(
                headline,
                "technical report",
                "updated technical report",
                "filing of updated technical report",
                "files technical report",
                "filed technical report",
                "mineral resource estimate",
                "resource estimate",
                "mining district",
                "annual information form",
                "national instrument 43-101",
                "ni 43-101"
        ) && !containsAny(
                headline,
                "class action",
                "lawsuit",
                "securities fraud",
                "investor alert",
                "shareholder alert",
                "deadline"
        )) {
            return result(
                    CatalystType.NO_MATERIAL_NEWS,
                    "Mining technical-report/resource filing headline blocked before litigation or M&A scoring"
            );
        }

        if (containsAny(
                headline,
                "issuance of public financial bills",
                "public financial bills",
                "financial bills in brazil",
                "issuance of financial bills",
                "financial bills"
        ) && !containsAny(
                headline,
                "class action",
                "lawsuit",
                "securities fraud",
                "investor alert",
                "shareholder alert",
                "deadline"
        )) {
            return result(
                    CatalystType.NO_MATERIAL_NEWS,
                    "Bank funding / public financial-bills issuance headline blocked before litigation scoring"
            );
        }

        if (containsAny(
                headline,
                "top rankings",
                "earns top rankings",
                "named a leader",
                "named leader",
                "recognized as a leader"
        )) {
            return result(
                    CatalystType.NO_MATERIAL_NEWS,
                    "Award/ranking/analyst-report publicity headline blocked before thematic catalyst scoring"
            );
        }

        if (containsAny(
                headline,
                "partners with",
                "partnership with",
                "to help homeowners",
                "prevent costly water damage",
                "customer awareness",
                "encourages customers",
                "wise water use"
        ) && containsAny(
                headline,
                "homeowners",
                "water damage",
                "customers",
                "consumer",
                "awareness",
                "campaign"
        )) {
            return result(
                    CatalystType.NO_MATERIAL_NEWS,
                    "Consumer/public-awareness partnership headline blocked before tradeable partnership scoring"
            );
        }

        if (containsAny(
                headline,
                "voting results following annual meeting",
                "announces voting results",
                "annual meeting",
                "annual general meeting",
                "results following annual meeting",
                "renewal of normal course issuer bid",
                "normal course issuer bid"
        ) && !containsAny(
                headline,
                "class action",
                "lawsuit",
                "securities fraud",
                "investor alert",
                "shareholder alert",
                "deadline",
                "special dividend",
                "strategic review",
                "merger",
                "acquisition"
        )) {
            return result(
                    CatalystType.NO_MATERIAL_NEWS,
                    "Routine governance/issuer-bid headline blocked using headline-only text before body boilerplate can poison classification"
            );
        }

        if (containsAny(
                headline,
                "technical progress update",
                "progress update on",
                "technology progress update",
                "platform progress update"
        ) && containsAny(
                headline,
                "platform",
                "technology",
                "ai",
                ".ai"
        )) {
            return result(
                    CatalystType.BUSINESS_UPDATE,
                    "Technology/platform progress update classified as controlled business momentum"
            );
        }

        return null;
    }


    private CatalystResult matchHighPriorityFinancingAndComplianceBeforeAdmin(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        if (containsAny(
                text,
                "minimum bid price deficiency",
                "minimum bid price requirement",
                "minimum bid requirement",
                "bid price deficiency",
                "minimum bid deficiency",
                "nasdaq notification regarding minimum bid",
                "nasdaq minimum bid",
                "nasdaq deficiency",
                "deficiency notice",
                "notice from nasdaq",
                "notified by nasdaq",
                "nasdaq non-compliance",
                "nasdaq noncompliance",
                "not in compliance with nasdaq",
                "listing deficiency",
                "continued listing deficiency"
        )) {
            return result(
                    CatalystType.NASDAQ_NONCOMPLIANCE,
                    "Nasdaq minimum-bid/listing deficiency blocked before offering or administrative scoring"
            );
        }

        if (containsAny(
                text,
                "at-the-market equity program",
                "at the market equity program",
                "at-the-market offering",
                "at the market offering",
                "at-the-market program",
                "atm equity program",
                "atm program",
                "atm offering",
                "equity distribution agreement",
                "atm sales agreement"
        )) {
            return result(
                    CatalystType.SHELF_OFFERING,
                    "At-the-market equity/offering program blocked before administrative scoring"
            );
        }

        if (containsAny(
                text,
                "rights agreement",
                "section 382 rights agreement",
                "extension of section 382 rights agreement",
                "tax benefits preservation plan",
                "stockholder rights plan",
                "shareholder rights plan",
                "poison pill"
        ) && !containsAny(
                text,
                "class action",
                "lawsuit",
                "investor alert",
                "shareholder alert",
                "investigates",
                "investigation"
        )) {
            return result(
                    CatalystType.NO_MATERIAL_NEWS,
                    "Corporate rights-agreement/governance headline blocked before M&A or litigation scoring"
            );
        }

        return null;
    }


    private CatalystResult matchExplicitSecuritiesLitigationBeforeAdmin(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        boolean explicitClassAction = containsAny(
                text,
                "class action notice",
                "class action deadline",
                "class action alert",
                "class action lawsuit",
                "class action lawsuit filed",
                "securities fraud lawsuit",
                "securities fraud class action",
                "securities class action",
                "shareholder class action",
                "investors with losses",
                "lead plaintiff",
                "deadline alert",
                "shareholder alert",
                "investor alert",
                "investor notice",
                "urges investors to contact",
                "encourages investors to contact",
                "encourages shareholders to contact",
                "contact the firm",
                "law firm urges",
                "law firm reminds",
                "files securities fraud lawsuit",
                "filed securities fraud lawsuit",
                "lawsuit filed on behalf of",
                "lawsuit on behalf of"
        );

        boolean legalSolicitor = containsAny(
                text,
                "rosen",
                "bragar eagel",
                "kessler topaz",
                "glancy prongay",
                "holzer & holzer",
                "kahn swick",
                "robbins geller",
                "levi & korsinsky",
                "pomerantz",
                "schall law",
                "law offices of",
                "law firm",
                "class action firm"
        );

        boolean cleanCompanyDeal = containsAny(
                text,
                "enters definitive agreement to acquire",
                "enters into definitive agreement to acquire",
                "signs definitive agreement to acquire",
                "definitive agreement to acquire",
                "to acquire all outstanding shares",
                "to be acquired by",
                "will be acquired by"
        );

        if ((explicitClassAction || (legalSolicitor && containsAny(text, "investigation", "lawsuit", "securities", "shareholder", "investor")))
                && !cleanCompanyDeal) {
            return result(
                    CatalystType.SECURITIES_LITIGATION,
                    "Explicit securities-litigation/class-action headline blocked before administrative scoring"
            );
        }

        return null;
    }

    private CatalystResult matchRoutineDividendFundAndConferenceNoTrade(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        boolean routineDividend = containsAny(
                text,
                "declares quarterly dividend",
                "declares quarterly cash dividend",
                "declares second quarter dividend",
                "declares second quarter dividends",
                "announces quarterly dividend",
                "announces quarterly cash dividend",
                "announces second quarter dividend",
                "announces second quarter dividends",
                "quarterly dividend",
                "quarterly cash dividend",
                "common stock dividend",
                "common stock dividends",
                "preferred stock dividend",
                "preferred stock dividends",
                "common and preferred stock dividend",
                "common and preferred stock dividends",
                "cash distribution payment",
                "distribution payment details",
                "declares distribution",
                "declares second quarter distribution",
                "announces distribution for preferred shares",
                "distribution for preferred shares",
                "second quarter 2026 distribution",
                "dividend of",
                "dividends"
        );

        if (routineDividend && !containsAny(
                text,
                "special dividend",
                "raises dividend",
                "raises quarterly dividend",
                "increases dividend",
                "increases quarterly dividend",
                "dividend increase"
        )) {
            return result(
                    CatalystType.NO_MATERIAL_NEWS,
                    "Routine dividend/distribution headline blocked before offering, M&A, or capital-return scoring"
            );
        }

        boolean fundAdministrativeUpdate = containsAny(
                text,
                "closed-end fund",
                "income fund",
                "municipal income fund",
                "government markets income trust",
                "intermediate income trust",
                "investment adviser",
                "change of investment adviser",
                "effective date for the change of investment adviser",
                "schedule k-3",
                "release of 2025 schedule k-3",
                "final all cash distribution payment details",
                "preferred shares",
                "non-cumulative preferred stock"
        );

        boolean actionableFundDeal = containsAny(
                text,
                "definitive agreement",
                "to acquire",
                "will acquire",
                "will be acquired",
                "merger agreement",
                "business combination agreement",
                "tender offer to acquire"
        );

        if (fundAdministrativeUpdate && !actionableFundDeal) {
            return result(
                    CatalystType.NO_MATERIAL_NEWS,
                    "Fund/adviser/distribution administrative headline blocked before offering or M&A scoring"
            );
        }

        boolean investorConference = containsAny(
                text,
                "investor conference",
                "investment conference",
                "virtual best ideas",
                "best ideas summer investment conference",
                "growth conference",
                "natural resources conference",
                "to speak at",
                "to participate in",
                "to participate at",
                "participation in",
                "fireside chat",
                "conference webcast",
                "earnings webcast",
                "earnings call",
                "earnings call details",
                "conference call",
                "conference call details",
                "to host conference call",
                "host conference call",
                "financial results on",
                "to discuss second quarter",
                "to discuss q2",
                "to discuss quarterly results"
        );

        if (investorConference && !containsAny(
                text,
                "reports results",
                "reported results",
                "beats",
                "raises guidance",
                "increases guidance",
                "fda",
                "pdufa",
                "phase",
                "trial"
        )) {
            return result(
                    CatalystType.NO_MATERIAL_NEWS,
                    "Investor conference/webcast participation headline blocked before executive, clinical, or M&A scoring"
            );
        }

        return null;
    }


    private CatalystResult matchOperationalMetricsPartnerStatusAndPublicAwarenessNoTrade(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        if (containsAny(
                text,
                "passenger traffic",
                "monthly passenger traffic",
                "reports passenger traffic",
                "reports may passenger traffic",
                "reports may 2026 passenger traffic",
                "traffic report",
                "monthly traffic",
                "airport traffic",
                "airports reports",
                "reports traffic"
        )) {
            return result(
                    CatalystType.NO_MATERIAL_NEWS,
                    "Passenger traffic / operating metrics update blocked before M&A or investment scoring"
            );
        }

        if (containsAny(
                text,
                "partner status",
                "platinum partner status",
                "gold partner status",
                "silver partner status",
                "premier partner status",
                "elite partner status",
                "preferred partner status",
                "partner program's platinum partner status",
                "partner program platinum partner status",
                "achieves nice platinum partner status",
                "achieves nice 360 vision partner program",
                "receives nice 360 vision partner program",
                "certified partner",
                "partner certification"
        )) {
            return result(
                    CatalystType.NO_MATERIAL_NEWS,
                    "Partner-tier/status recognition blocked before M&A or broad partnership scoring"
            );
        }

        if (containsAny(
                text,
                "practice wise water use",
                "wise water use",
                "hotter, drier summer conditions",
                "hotter drier summer conditions",
                "summer conditions approach",
                "encourages customers",
                "customer awareness",
                "consumer safety",
                "public awareness",
                "community members impacted",
                "relief for community members",
                "help homeowners prevent costly water damage",
                "homeowners prevent costly water damage",
                "prevent costly water damage",
                "water damage",
                "mutual insurance partners with",
                "insurance partners with moen",
                "partners with moen"
        ) && !containsAny(
                text,
                "awarded contract",
                "wins contract",
                "selected by",
                "definitive agreement",
                "to acquire",
                "fda approves"
        )) {
            return result(
                    CatalystType.NO_MATERIAL_NEWS,
                    "Public awareness / customer information headline blocked before strategic-investment scoring"
            );
        }

        return null;
    }


    private CatalystResult matchMiningTechnicalReportNoTrade(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        boolean miningTechnicalReport = containsAny(
                text,
                "technical report",
                "updated technical report",
                "filing of updated technical report",
                "files updated technical report",
                "filed updated technical report",
                "technical report for the",
                "mining district",
                "mineral resource estimate",
                "mineral resources estimate",
                "independent mineral resource",
                "ni 43-101",
                "preliminary economic assessment",
                "feasibility study"
        );

        boolean legalOrEnforcement = containsAny(
                text,
                "class action",
                "lawsuit",
                "securities fraud",
                "investor alert",
                "shareholder alert",
                "investigation",
                "sec charges",
                "doj"
        );

        if (miningTechnicalReport && !legalOrEnforcement) {
            return result(
                    CatalystType.EXPLORATION_PROGRAM,
                    "Mining technical-report/resource update separated before litigation scoring"
            );
        }

        return null;
    }

    private CatalystResult matchCorporateAdministrativeNoTrade(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        boolean annualMeetingOrVoting = containsAny(
                text,
                "annual general meeting",
                "annual meeting of shareholders",
                "annual meeting",
                "general meeting of shareholders",
                "voting results",
                "results of its annual general meeting",
                "results of annual general meeting",
                "shareholder meeting",
                "shareholders meeting",
                "agm results"
        );

        boolean ordinaryGovernanceResult = containsAny(
                text,
                "announces voting results",
                "announces results of",
                "results of 2026 annual",
                "results of its 2026 annual",
                "election of directors",
                "board of directors",
                "evolution of its board",
                "appoints",
                "appointed",
                "names",
                "special advisor"
        );

        boolean explicitlyMaterialDealOrApproval = containsAny(
                text,
                "shareholder approval to acquire",
                "shareholder approval of merger",
                "shareholder approval for merger",
                "shareholders approve merger",
                "shareholders approve acquisition",
                "fda approves",
                "fda approval",
                "fda clearance",
                "receives fda",
                "awarded contract",
                "wins contract"
        );

        if ((annualMeetingOrVoting || ordinaryGovernanceResult) && !explicitlyMaterialDealOrApproval) {
            return result(
                    CatalystType.NO_MATERIAL_NEWS,
                    "Corporate administrative / annual-meeting / board-update headline blocked before offering, FDA, or M&A scoring"
            );
        }

        return null;
    }

    private CatalystResult matchNegativeHeadlineCamouflage(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        boolean cleanBindingDeal = containsAny(
                text,
                "enters definitive agreement to acquire",
                "enters into definitive agreement to acquire",
                "signs definitive agreement to acquire",
                "definitive agreement to acquire",
                "to be acquired by",
                "will be acquired by",
                "agrees to acquire",
                "all-cash transaction",
                "cash and stock consideration",
                "per share in cash"
        );

        boolean lawFirmOrShareholderAlert = containsAny(
                text,
                "shareholder alert",
                "shareholder alert:",
                "shareholder notice",
                "investor alert",
                "investor notice",
                "investor deadline",
                "deadline alert",
                "stock alert",
                "m&a class action firm",
                "m & a class action firm",
                "class action firm",
                "law firm",
                "law offices of",
                "robbins geller",
                "levi & korsinsky",
                "kessler topaz",
                "halper sadeh",
                "johnson fistel",
                "suewallst",
                "wohl & fruchter",
                "pomerantz",
                "rosen law",
                "schall law",
                "bragar eagel",
                "faruqi & faruqi",
                "glancy prongay",
                "bernstein liebhard",
                "kahn swick",
                "frank r. cruz"
        );

        boolean investigationOrFairness = containsAny(
                text,
                "investigates fairness",
                "investigating fairness",
                "investigating whether",
                "investigation of",
                "announces an investigation",
                "announces investigation",
                "possible securities law violations",
                "securities law violations",
                "obtaining a fair price",
                "fairness of the proposed sale",
                "breach of fiduciary duty",
                "fiduciary duties",
                "shareholder rights",
                "lead plaintiff",
                "lead plaintiff deadline",
                "class action lawsuit",
                "class action",
                "securities class action",
                "shareholder lawsuit",
                "lawsuit seeks recovery",
                "securities litigation"
        );

        if ((lawFirmOrShareholderAlert || investigationOrFairness) && !cleanBindingDeal) {
            return result(
                    CatalystType.SECURITIES_LITIGATION,
                    "Law-firm/shareholder-alert/investigation wording blocked before positive M&A or buyout scoring"
            );
        }

        boolean offeringOrDilution = containsAny(
                text,
                "announces pricing of",
                "announced pricing of",
                "pricing of public offering",
                "pricing of registered direct",
                "prices public offering",
                "public offering of",
                "registered direct offering",
                "registered direct and concurrent private placement",
                "private placement",
                "best-efforts offering",
                "overnight offering",
                "bought deal financing",
                "bought deal offering",
                "upsized offering",
                "upsize of previously announced bought deal",
                "subscription rights offering",
                "rights offering",
                "preferred shares",
                "common shares",
                "common stock offering",
                "ordinary shares",
                "at-the-market offering",
                "atm offering",
                "warrants",
                "pre-funded warrants",
                "convertible preferred",
                "convertible notes",
                "equity securities",
                "debt securities",
                "senior notes",
                "notes due",
                "tender offer for notes",
                "tender offers for notes",
                "offers to purchase for cash",
                "pricing terms of offers to purchase",
                "exchange offer for notes"
        );

        boolean trueTakeoverOffer = containsAny(
                text,
                "offer to acquire",
                "offers to acquire",
                "raised offer to acquire",
                "raises offer to acquire",
                "increases offer to acquire",
                "unsolicited proposal to acquire",
                "proposal to acquire all outstanding shares"
        );

        if (offeringOrDilution && !trueTakeoverOffer) {
            return result(
                    CatalystType.OFFERING_DILUTION,
                    "Offering/dilution/debt-securities wording blocked before positive offer, M&A, or clinical-data scoring"
            );
        }

        if (containsAny(
                text,
                "going concern",
                "substantial doubt",
                "material weakness",
                "restates financial statements",
                "will restate",
                "non-reliance on previously issued financial statements",
                "delays filing",
                "late filing",
                "receives notice of non-compliance",
                "receives nasdaq delisting notice",
                "fails to regain compliance",
                "bankruptcy",
                "chapter 11",
                "restructuring support agreement",
                "defaults under",
                "event of default",
                "credit facility default"
        )) {
            return result(
                    CatalystType.CREDIT_STRESS,
                    "Financial distress/accounting/default wording blocked before broad positive scoring"
            );
        }

        if (containsAny(
                text,
                "recall",
                "withdraws product",
                "voluntary withdrawal",
                "safety notice",
                "safety alert",
                "data breach",
                "cybersecurity incident",
                "ransomware",
                "production halt",
                "plant shutdown",
                "fatal accident",
                "explosion at",
                "fire at facility"
        )) {
            return result(
                    CatalystType.NEGATIVE_HEADWIND,
                    "Operational/safety/cyber negative wording blocked before positive catalyst scoring"
            );
        }

        return null;
    }

    private CatalystResult matchSecuritiesLitigation(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        boolean lawFirmSolicitation = containsAny(
                text,
                "law offices of",
                "rosen law firm",
                "levi & korsinsky",
                "schall law firm",
                "pomerantz law firm",
                "bragar eagel",
                "faruqi & faruqi",
                "glancy prongay",
                "bernstein liebhard",
                "kahn swick",
                "frank r. cruz",
                "holzer & holzer",
                "claims filer",
                "claimsfiler",
                "bronstein",
                "gewirtz",
                "m&a class action firm announces an investigation",
                "investor deadline",
                "lead plaintiff deadline",
                "reminds investors",
                "alerts investors",
                "encourages investors",
                "investors with losses",
                "shareholder alert",
                "shareholder alert:",
                "m&a class action firm",
                "m & a class action firm",
                "class action firm announces an investigation",
                "announces an investigation of",
                "announces investigation of",
                "announces that it is investigating",
                "investigation of astrosnova",
                "investigation of"
        );

        boolean securitiesCaseLanguage = containsAny(
                text,
                "securities fraud",
                "securities class action",
                "class action lawsuit",
                "shareholder class action",
                "shareholder lawsuit",
                "class action lawsuit seeks recovery",
                "lawsuit seeks recovery",
                "investor lawsuit",
                "securities litigation",
                "violated the securities laws",
                "federal securities laws",
                "investor investigation",
                "shareholder investigation",
                "investigates fairness",
                "investigating fairness",
                "investigating whether",
                "possible securities law violations",
                "stock alert:",
                "investor notice:",
                "deadline alert:",
                "shareholder alert:",
                "shareholder alert",
                "m&a class action firm",
                "m & a class action firm",
                "announces an investigation",
                "announces investigation",
                "an investigation of",
                "sued for securities fraud",
                "lawsuit leads to",
                "ftc lawsuit",
                "sec investigation",
                "doj investigation"
        );

        boolean cleanMergerAcquisitionBid = containsAny(
                text,
                "raises offer to acquire",
                "raised offer to acquire",
                "increases offer to acquire",
                "offer to acquire",
                "per share comprised of",
                "cash and one",
                "cash and stock consideration",
                "all-cash offer",
                "merger agreement",
                "definitive agreement to acquire"
        );

        if ((lawFirmSolicitation || securitiesCaseLanguage) && !cleanMergerAcquisitionBid) {
            return result(
                    CatalystType.SECURITIES_LITIGATION,
                    "Securities litigation / shareholder lawsuit article detected before historical-performance scoring"
            );
        }

        return null;
    }

    private CatalystResult matchAwardReviewAndPublicityNoise(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        boolean awardOrReviewLanguage = containsAny(
                text,
                "buyer choice award",
                "buyer's choice award",
                "buyers choice award",
                "customer reviews",
                "consumeraffairs",
                "best-in-state",
                "best in state",
                "greatest workplaces",
                "greatest workplace",
                "most loved workplace",
                "top workplace",
                "top workplaces",
                "workplace award",
                "visionary spotlight award",
                "wins award",
                "wins two",
                "earns award",
                "earns two",
                "recognized as",
                "named to newsweek",
                "named one of",
                "customer choice",
                "customers choice",
                "peer insights",
                "award from",
                "awards from"
        );

        boolean explicitlyMaterialContractAward = containsAny(
                text,
                "awarded contract",
                "awarded a contract",
                "awarded new contract",
                "awarded government contract",
                "awarded u.s. government contract",
                "selected for contract",
                "wins contract",
                "purchase order",
                "task order"
        );

        if (awardOrReviewLanguage && !explicitlyMaterialContractAward) {
            return result(
                    CatalystType.NO_MATERIAL_NEWS,
                    "Award/review/publicity wording blocked before clinical, contract, AI, or M&A scoring"
            );
        }

        if (containsAny(
                text,
                "lease signing",
                "five-year lease",
                "5-year lease",
                "new lease for",
                "signed a lease",
                "lease agreement for"
        ) && !containsAny(
                text,
                "major customer",
                "purchase order",
                "awarded contract",
                "supply agreement"
        )) {
            return result(
                    CatalystType.BUSINESS_UPDATE,
                    "Lease/ordinary facility update separated before M&A scoring"
            );
        }

        return null;
    }

    private CatalystResult matchHighPriorityInformationalNoise(String text) {
        if (containsAny(
                text,
                "generated by benzinga's automated content engine",
                "generated by benzinga’s automated content engine",
                "this article was generated by benzinga",
                "automated content engine"
        )) {
            return result(
                    CatalystType.HISTORICAL_PERFORMANCE,
                    "Automated/historical-performance article detected before catalyst scoring"
            );
        }

        if (containsAny(
                text,
                "here's how much you would have made owning",
                "here is how much you would have made owning",
                "how much you would have made owning",
                "would have made owning",
                "if an investor had bought",
                "if an investor had invested",
                "worth today based on a price",
                "worth today based on the price",
                "performance over last 20 years",
                "outperformed the market over the past 20 years",
                "compounded returns can make",
                "invested in",
                "would be worth this much today",
                "would be worth",
                "performance over last 5 years",
                "performance over last 10 years",
                "performance over last 20 years"
        )) {
            return result(
                    CatalystType.HISTORICAL_PERFORMANCE,
                    "Historical return / compound-performance article detected before catalyst scoring"
            );
        }

        if (containsAny(
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
                "the arora report"
        )) {
            return result(
                    CatalystType.BROAD_MARKET,
                    "Broad macro/geopolitical market commentary detected before catalyst scoring"
            );
        }

        if (containsAny(
                text,
                "retail traders should stop watching",
                "start watching the calendar",
                "what you actually bought",
                "market-structure event",
                "market structure event",
                "etf takeover",
                "theme etf",
                "thematic etf",
                "thematic etfs",
                "value funds",
                "funds to thematic etfs",
                "etf holdings",
                "etf exposure",
                "a bit of a trap",
                "things that will actually move",
                "mainstream coverage",
                "most holders are betting"
        )) {
            return result(
                    CatalystType.INVESTOR_COMMENTARY,
                    "Opinion/calendar/retail-trader commentary detected before launch scoring"
            );
        }

        if (containsAny(
                text,
                "traded more than apple",
                "stocks combined",
                "more than apple, microsoft",
                "more than apple microsoft",
                "apple, microsoft, tesla",
                "apple microsoft tesla",
                "top 2 downgrades",
                "top downgrades for",
                "top upgrades for",
                "analyst is no longer bullish; here are"
        )) {
            return result(
                    CatalystType.NEWS_ROUNDUP,
                    "Broad multi-company comparison/roundup article detected before catalyst scoring"
            );
        }

        return null;
    }

    private CatalystResult matchPrivateCompanyValuationAndFundingArticle(String text) {
        if (containsAny(
                text,
                "bull case",
                "worth $",
                "could be worth",
                "what could be worth",
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
        ) && !containsAny(
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
        )) {
            return result(
                    CatalystType.VALUATION_ARTICLE,
                    "Valuation/private-company funding article blocked before partnership or IPO catalyst scoring"
            );
        }

        return null;
    }

    private CatalystResult matchProductLaunchFalsePositiveNoise(String text) {
        if (containsAny(
                text,
                "rocket launch",
                "actual rocket launch",
                "watching the rockets",
                "stop watching the rockets",
                "spacex went public",
                "went public on",
                "public on june",
                "ipo calendar",
                "start watching the calendar",
                "retail traders should",
                "market-structure event",
                "market structure event"
        )) {
            return result(
                    CatalystType.INVESTOR_COMMENTARY,
                    "Rocket/IPO-calendar commentary blocked from generic product-launch scoring"
            );
        }

        return null;
    }



    private CatalystResult matchFundEtfThemeCommentary(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        boolean etfOrFundArticle = containsAny(
                text,
                " etf",
                "etf ",
                "etfs",
                "exchange-traded fund",
                "exchange traded fund",
                "fund holdings",
                "fund flow",
                "fund flows",
                "value funds",
                "thematic funds",
                "thematic etfs",
                "etf takeover",
                "is suddenly everywhere",
                "spcx is suddenly everywhere",
                "from value funds to thematic etfs"
        );

        if (!etfOrFundArticle) {
            return null;
        }

        return result(
                CatalystType.FUND_ETF_COMMENTARY,
                "Fund/ETF/theme commentary blocked before product-launch or business-momentum scoring"
        );
    }

    private CatalystResult matchHighPriorityIndustryReadthrough(String text) {
        if (containsAny(
                text,
                "ai-led rally",
                "ai led rally",
                "ai optimism",
                "ai frenzy",
                "ai obsession",
                "ai-driven rally",
                "ai driven rally",
                "tech rally",
                "chip-heavy market",
                "software rout",
                "software stocks",
                "ai disruption fears",
                "traditional software",
                "foreign outflows from india's information technology stocks",
                "hedge funds sold broader tech",
                "hedge funds creep back into tech",
                "world stocks rally",
                "global stocks rallied",
                "stocks up, rebounding as anthropic",
                "nvidia chief to asia",
                "we're still supply constrained",
                "supply constrained",
                "anthropic unveils",
                "anthropic announcement",
                "anthropic ipo filing",
                "openai",
                "anthropic"
        )) {
            return result(
                    CatalystType.INDUSTRY_READTHROUGH,
                    "Industry/AI readthrough article blocked as non-company-specific catalyst"
            );
        }

        return null;
    }

    private CatalystResult matchGovernmentPaymentRateCatalyst(String text) {
        if (containsAny(
                text,
                "medicare advantage payment rates",
                "lifts 2027 medicare advantage payment rates",
                "payment rates above expectations",
                "additional payments in 2027",
                "cms payment rates",
                "medicare payment rates",
                "government set medicare advantage payment rates"
        )) {
            return result(
                    CatalystType.GOVERNMENT_PAYMENT_RATE,
                    "Government healthcare payment-rate catalyst found"
            );
        }

        return null;
    }

    private CatalystResult matchStrategicInvestmentArticle(String text) {
        if (containsAny(
                text,
                "invests $",
                "invests in",
                "takes a stake",
                "taking a stake",
                "stakeholder",
                "large investor",
                "committed $",
                "committed ",
                "strategic investment",
                "minority ownership investment",
                "portfolio reshuffling",
                "berkshire",
                "tci taking a stake"
        ) && !containsAny(
                text,
                "agrees to acquire",
                "to acquire",
                "acquires",
                "merger",
                "buyout",
                "takeover offer"
        )) {
            return result(
                    CatalystType.STRATEGIC_INVESTMENT,
                    "Strategic investment/stake article separated from clean M&A catalyst"
            );
        }

        return null;
    }


    private CatalystResult matchHighPriorityEarningsTranscript(String text) {
        if (containsAny(
                text,
                "full earnings call transcript",
                "earnings call transcript",
                "reports q1 2026 results",
                "reports q2 2026 results",
                "reports q3 2026 results",
                "reports q4 2026 results"
        ) && containsAny(
                text,
                "transcript",
                "earnings call"
        )) {
            return result(
                    CatalystType.EARNINGS_TRANSCRIPT,
                    "Earnings transcript/results article detected before bankruptcy/restructuring scoring"
            );
        }

        return null;
    }

    private CatalystResult matchHighPriorityGovernanceWarrantCancellation(String text) {
        if (containsAny(
                text,
                "cancels",
                "cancelled",
                "canceled"
        ) && containsAny(
                text,
                "pre-funded warrants",
                "prefunded warrants",
                "shareholder demand",
                "corporate transparency",
                "transfer agent discrepancies",
                "acting in concert",
                "4.99% rule"
        )) {
            return result(
                    CatalystType.GOVERNANCE_CHANGE,
                    "Governance / warrant cancellation language detected before clinical scoring"
            );
        }

        return null;
    }

    private CatalystResult matchHighPriorityAutonomousRoadmap(String text) {
        if (containsAny(
                text,
                "shares advance as ceo outlines",
                "ceo outlines aggressive",
                "autonomous-driving roadmap",
                "autonomous driving roadmap",
                "roadmap"
        ) && containsAny(
                text,
                "autonomous",
                "driving",
                "roadmap"
        )) {
            return result(
                    CatalystType.POSITIVE_BUSINESS_MOMENTUM,
                    "Autonomous/product roadmap momentum language detected before asset-sale scoring"
            );
        }

        return null;
    }

    private CatalystResult matchHighPriorityGovernmentSelection(String text) {
        if (containsAny(
                text,
                "selected by",
                "awarded by",
                "chosen by",
                "contracted by"
        ) && containsAny(
                text,
                "city council",
                "department of",
                "u.s. navy",
                "department of navy",
                "government",
                "municipal",
                "125 sites",
                "safety systems"
        )) {
            return result(
                    CatalystType.MAJOR_CONTRACT,
                    "Government/customer selection or implementation contract language found before clinical scoring"
            );
        }

        return null;
    }

    private CatalystResult matchHighPriorityBroadMarketConcentration(String text) {
        if (containsAny(
                text,
                "s&p 500 hasn't been this concentrated",
                "sp 500 hasn't been this concentrated",
                "s&p 500 hasnt been this concentrated",
                "railroad era",
                "non-ai stocks are up just",
                "non ai stocks are up just",
                "market concentration",
                "index concentration",
                "s&p 500 concentration",
                "sp 500 concentration",
                "magnificent seven concentration",
                "market breadth",
                "breadth has narrowed",
                "broad market concentration"
        )) {
            return result(
                    CatalystType.BROAD_MARKET,
                    "Broad market concentration / market breadth article detected before clinical scoring"
            );
        }

        return null;
    }

    private CatalystResult matchHighPriorityCreditFacility(String text) {
        if (containsAny(
                text,
                "secures credit approval",
                "credit approval for",
                "credit facility",
                "replace existing credit facility",
                "new bmo financing",
                "bmo loan",
                "loan facility",
                "financing facility",
                "refinancing",
                "revolving credit facility",
                "term loan",
                "debt facility"
        )) {
            return result(
                    CatalystType.SEC_FILING,
                    "Credit facility / financing approval language found before geopolitical scoring"
            );
        }

        return null;
    }

    private CatalystResult matchHighPriorityClinicalData(String text) {
        if (!hasHealthcareContext(text) ||
                isDebtSecuritiesFinancingText(text) ||
                isOfferingOrDilutionText(text) ||
                isNonHealthcareIndustrialOrConsumerProductText(text)) {
            return null;
        }

        boolean hasSpecificClinicalContext = containsAny(
                text,
                "clinical trial",
                "phase 1",
                "phase 2",
                "phase 3",
                "patient",
                "patients",
                "oncology",
                "cancer",
                "tumor",
                "therapy",
                "therapeutic",
                "treatment",
                "endpoint",
                "survival",
                "response rate",
                "remission",
                "antibody",
                "vaccine",
                "fda"
        );

        boolean hasClinicalDataPhrase = containsAny(
                text,
                "interim phase 1 clinical data",
                "interim phase 2 clinical data",
                "interim phase 3 clinical data",
                "announces interim phase 1 clinical data",
                "announces interim phase 2 clinical data",
                "announces interim phase 3 clinical data",
                "clinical data for",
                "phase 1 clinical data",
                "phase 2 clinical data",
                "phase 3 clinical data",
                "clinical data",
                "advanced late-line",
                "platinum-resistant ovarian cancer",
                "t cell engaging bispecific antibody",
                "bispecific antibody",
                "ovarian cancer",
                "lung cancers",
                "pancreatic cancers"
        );

        if (hasSpecificClinicalContext && hasClinicalDataPhrase) {
            return result(
                    CatalystType.DRUG_DATA_POSITIVE,
                    "Specific clinical/healthcare data headline detected before FDA registration scoring"
            );
        }

        return null;
    }

    private CatalystResult matchHighPriorityProductUpgrade(String text) {
        if (containsAny(
                text,
                "adds tetra radio testing option",
                "radio testing option",
                "upgrade speeds testing",
                "adds testing option",
                "product upgrade",
                "upgrade speeds",
                "new testing option",
                "mission-critical radios",
                "cx300 monitor",
                "can now be installed",
                "system can now be installed",
                "high performance connectivity system",
                "installed on the dassault falcon",
                "falcon 7x and 8x"
        )) {
            return result(
                    CatalystType.PRODUCT_LAUNCH,
                    "Product upgrade / new product option detected before unknown fallback"
            );
        }

        return null;
    }

    private CatalystResult matchHighPriorityPermitApplication(String text) {
        if (containsAny(
                text,
                "submits section 404 permit application",
                "section 404 permit application",
                "permit application to u.s. army corps of engineers",
                "permit application to us army corps of engineers",
                "army corps of engineers",
                "coosa graphite deposit",
                "graphite deposit",
                "submits permit application",
                "environmental permit application",
                "mining permit application"
        )) {
            return result(
                    CatalystType.EXPLORATION_PROGRAM,
                    "Resource project permit application detected before unknown fallback"
            );
        }

        return null;
    }

    private CatalystResult matchHighPriorityNonBiotechPartnership(String text) {
        if (containsAny(
                text,
                "partners with",
                "partnership with",
                "expands collaboration with",
                "collaboration with",
                "cross-border payments",
                "credible finance",
                "benchmark electronics",
                "high-volume production",
                "production of its new",
                "industrial, robotics, automotive and smart infrastructure"
        ) && !containsAny(
                text,
                "phase 1",
                "phase 2",
                "phase 3",
                "clinical trial",
                "patient dosed",
                "patient enrolled",
                "tumor",
                "cancer",
                "biotherapeutics",
                "biosciences"
        )) {
            return result(
                    CatalystType.PARTNERSHIP,
                    "Non-biotech partnership/collaboration language found before clinical scoring"
            );
        }

        return null;
    }

    private CatalystResult matchHighPriorityClinicalTrialInitiation(String text) {
        if (!hasHealthcareContext(text)) {
            return null;
        }

        if (containsAny(
                text,
                "enrolls first patient",
                "enrolled first patient",
                "first patient enrolled",
                "first patient dosed",
                "doses first patient",
                "initiates phase 1",
                "initiates phase 2",
                "initiates phase 3",
                "begins phase 1",
                "begins phase 2",
                "begins phase 3",
                "starts phase 1",
                "starts phase 2",
                "starts phase 3",
                "phase 1 trial of",
                "phase 2 trial of",
                "phase 3 trial of"
        )) {
            return result(
                    CatalystType.CLINICAL_TRIAL_INITIATION,
                    "Clinical trial initiation / first patient enrolled language found"
            );
        }

        return null;
    }



    private CatalystResult matchHighPriorityAssetSale(String text) {
        if (containsAny(
                text,
                "to sell most of its",
                "sell most of its",
                "to sell its",
                "sells its",
                "sale of its",
                "sale of most of its",
                "satellite and space communications segment",
                "to sell most of its satellite",
                "sell most of its satellite",
                "to gilat for",
                "cash proceeds",
                "net cash proceeds",
                "asset sale",
                "sale of segment",
                "sell segment",
                "sale of division",
                "sell division",
                "divestiture",
                "divestiture deal",
                "divests",
                "to acquire select strategic",
                "acquire select strategic",
                "strategic adcolony assets",
                "adcolony assets",
                "select strategic assets"
        )) {
            return result(
                    CatalystType.ASSET_SALE,
                    "Asset sale / segment sale language found before financing scoring"
            );
        }

        return null;
    }


    private CatalystResult matchShareholderApprovedAcquisition(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        boolean shareholderApprovedDeal = containsAny(
                text,
                "shareholder approval to acquire",
                "shareholder approval of acquisition",
                "shareholders approve acquisition",
                "shareholders approved acquisition",
                "shareholder approval to be acquired",
                "shareholders approve merger",
                "shareholders approved merger",
                "announces shareholder approval to acquire",
                "announces shareholder approval of the acquisition"
        );

        boolean actualAcquisition = containsAny(
                text,
                "acquire",
                "acquisition",
                "merger",
                "proposed merger",
                "proposed acquisition"
        );

        if (shareholderApprovedDeal && actualAcquisition && !isLawFirmOrInvestigationText(text)) {
            return result(
                    CatalystType.MERGER_ACQUISITION,
                    "Shareholder-approved merger/acquisition separated before litigation and admin filters"
            );
        }

        return null;
    }

    private CatalystResult matchHighPriorityMergerAcquisition(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        boolean cleanDealLanguage = containsAny(
                text,
                "definitive agreement to acquire",
                "enters definitive agreement to acquire",
                "enters into definitive agreement to acquire",
                "signs definitive agreement to acquire",
                "agrees to acquire",
                "to acquire 100%",
                "acquires 100%",
                "announces proposed acquisition of",
                "proposed acquisition of",
                "proposed merger with",
                "merger agreement",
                "business combination agreement",
                "take-private transaction",
                "all-cash transaction",
                "all stock transaction",
                "cash and stock transaction",
                "cash and stock consideration",
                "per share in cash",
                "per share comprised of",
                "offer to acquire",
                "offer to buy",
                "raises offer to acquire",
                "raised offer to acquire",
                "increases offer to acquire",
                "improved offer to acquire",
                "revised offer to acquire"
        );

        boolean lawsuitOrFairnessReview = containsAny(
                text,
                "law firm",
                "investigates",
                "investigation",
                "investor notice",
                "stock alert",
                "deadline alert",
                "class action",
                "lawsuit",
                "fairness of the proposed sale",
                "obtaining a fair price",
                "shareholder rights"
        );

        boolean genericInvestmentOnly = containsAny(
                text,
                "strategic investment",
                "minority investment",
                "takes a stake",
                "taking a stake",
                "anchor investor",
                "serves as anchor investor",
                "investment vehicle",
                "fund expanding",
                "financing to accelerate",
                "committed capital"
        );

        if (cleanDealLanguage && !lawsuitOrFairnessReview && !genericInvestmentOnly) {
            return result(
                    CatalystType.MERGER_ACQUISITION,
                    "Clean M&A / acquisition / merger language detected after litigation and financing filters"
            );
        }

        return null;
    }

    private CatalystResult matchHighPriorityActivistInvestorArticle(String text) {
        if (containsAny(
                text,
                "board pushes back against activist investor campaign",
                "activist investor campaign",
                "pushes back against activist",
                "activist campaign"
        )) {
            return result(
                    CatalystType.INVESTOR_COMMENTARY,
                    "Activist investor / board response article detected before product scoring"
            );
        }

        return null;
    }

    private CatalystResult matchHighPriorityOperationalGrowth(String text) {
        if (containsAny(
                text,
                "starts next cannabis grow phase",
                "next cannabis grow phase",
                "commissions hydrometallurgical demonstration facility",
                "targets production of",
                "demonstration facility",
                "production of heavy rare earth oxide",
                "strong interest in its michigan data center campus",
                "expansion to 52mw",
                "total valuation of $2.5b",
                "15-year mou",
                "expected to contribute",
                "$2.34b in revenue",
                "initial 100mw deployment"
        )) {
            return result(
                    CatalystType.POSITIVE_BUSINESS_MOMENTUM,
                    "High-priority operational growth / facility / large revenue pipeline language detected"
            );
        }

        return null;
    }

    private CatalystResult matchCriticalNegative(String text) {
        if (containsAny(
                text,
                "bankruptcy",
                "files for bankruptcy",
                "filed for bankruptcy",
                "chapter 11",
                "chapter 7",
                "restructuring support agreement",
                "going concern warning",
                "substantial doubt about its ability to continue",
                "liquidation",
                "voluntary petition"
        )) {
            return result(
                    CatalystType.BANKRUPTCY,
                    "Bankruptcy/restructuring language found"
            );
        }

        if (containsAny(
                text,
                "restatement filing",
                "restate financial",
                "restating financial",
                "restatement of financial",
                "will restate",
                "audited financial results",
                "material weakness",
                "internal control weakness",
                "accounting error",
                "non-reliance on previously issued financial statements",
                "financial statements should not be relied upon",
                "statements should not be relied upon",
                "announces restatement"
        )) {
            return result(
                    CatalystType.RESTATEMENT,
                    "Restatement/accounting issue language found"
            );
        }

        if (containsAny(
                text,
                "short seller report",
                "short report",
                "hindenburg",
                "culper",
                "grizzly research",
                "spruce point",
                "kerrisdale",
                "muddy waters",
                "wolfpack research",
                "accuses company of fraud",
                "fraud allegations"
        )) {
            return result(
                    CatalystType.SHORT_SELLER_REPORT,
                    "Short-seller report language found"
            );
        }

        return null;
    }

    private CatalystResult matchHighPriorityExplainerArticle(String text) {
        if (containsAny(
                text,
                "why is",
                "why are",
                "why shares",
                "why stock",
                "stock gaining",
                "stock falling",
                "stock moving",
                "shares gaining",
                "shares falling",
                "shares moving",
                "what's going on",
                "whats going on",
                "here's why",
                "here is why"
        )) {
            return result(
                    CatalystType.WHY_MOVING,
                    "Why-moving / explainer article detected before catalyst scoring"
            );
        }

        return null;
    }

    private CatalystResult matchHighPriorityComparativeStudy(String text) {
        if (containsAny(
                text,
                "comparative study",
                "comparative analysis",
                "industry competitors",
                "competitors in",
                "peer comparison",
                "company compared to industry competitors"
        )) {
            return result(
                    CatalystType.INDUSTRY_COMPARISON,
                    "Comparative study / industry comparison article detected before catalyst scoring"
            );
        }

        return null;
    }

    private CatalystResult matchHighPriorityExpansionDeal(String text) {
        if (containsAny(
                text,
                "climbs on silicon photonics expansion deal",
                "silicon photonics expansion deal",
                "expansion deal",
                "capacity expansion deal",
                "manufacturing expansion deal",
                "photonics expansion",
                "silicon photonics"
        )) {
            return result(
                    CatalystType.FACILITY_EXPANSION,
                    "Expansion deal / silicon photonics expansion detected before earnings scoring"
            );
        }

        return null;
    }

    private CatalystResult matchExecutiveAiAndIndustryCommentary(String text) {
        if (containsAny(
                text,
                "ceo says",
                "ceo warns",
                "ceo argues",
                "ceo predicts",
                "ceo expects",
                "ceo believes",
                "ceo comments",
                "ceo discusses",
                "executive says",
                "executive warns",
                "executive argues",
                "executive predicts",
                "comments on",
                "speaks about",
                "discusses",
                "opines on",
                "says ai restrictions",
                "ai restrictions could",
                "open-weight models",
                "open weight models",
                "shift to open-weight",
                "shift to open weight",
                "ai industry commentary",
                "ai optimism",
                "ai restrictions",
                "ai policy",
                "ai regulation",
                "global shift to",
                "box ceo aaron levie says"
        )) {
            return result(
                    CatalystType.MARKET_COMMENTARY,
                    "Executive / AI industry commentary detected"
            );
        }

        return null;
    }

    private CatalystResult matchTradingStatus(String text) {
        if (containsAny(
                text,
                "trading halt",
                "halt news pending",
                "halted at",
                "halt: news pending",
                "halted pending news",
                "volatility halt"
        )) {
            return result(
                    CatalystType.TRADING_HALT,
                    "Trading halt language found"
            );
        }

        if (containsAny(
                text,
                "shares resume trade",
                "shares resumed trade",
                "resumes trading",
                "resume trading",
                "trading resumes",
                "resumed trading"
        )) {
            return result(
                    CatalystType.TRADING_RESUMPTION,
                    "Trading resumption language found"
            );
        }

        if (containsAny(
                text,
                "circuit breaker",
                "halted on circuit breaker",
                "luld pause",
                "limit up limit down"
        )) {
            return result(
                    CatalystType.CIRCUIT_BREAKER,
                    "Circuit breaker language found"
            );
        }

        return null;
    }

    private CatalystResult matchHighPriorityAnalystForecastArticle(String text) {
        if (containsAny(
                text,
                "forecast changes from wall street",
                "recent forecast changes",
                "most accurate analysts",
                "gears up for q4 print",
                "gears up for q1 print",
                "gears up for q2 print",
                "gears up for q3 print",
                "ahead of q4 print",
                "ahead of q1 print",
                "ahead of q2 print",
                "ahead of q3 print",
                "top wall street forecasters revamp",
                "forecasters revamp",
                "revamp jabil expectations",
                "ahead of q3 earnings",
                "ahead of q4 earnings",
                "ahead of q1 earnings",
                "ahead of q2 earnings"
        )) {
            return result(
                    CatalystType.ANALYST_COMMENTARY,
                    "Analyst forecast-change / earnings preview article detected before earnings scoring"
            );
        }

        return null;
    }

    private CatalystResult matchListingCompliance(String text) {
        if (containsAny(
                text,
                "delisting proceedings",
                "commence delisting",
                "commencing proceedings to delist",
                "trading will be suspended",
                "trading suspended immediately",
                "will be delisted",
                "to be delisted",
                "delist its securities",
                "delist ordinary shares",
                "listing risk",
                "risk of delisting",
                "continued listing risk"
        )) {
            return result(
                    CatalystType.DELISTING_RISK,
                    "Delisting risk language found"
            );
        }

        if (containsAny(
                text,
                "notice of delinquency from nasdaq",
                "delinquency from nasdaq",
                "nasdaq delinquency",
                "noncompliance notice",
                "non-compliance notice",
                "nasdaq noncompliance",
                "nasdaq non-compliance",
                "nasdaq deficiency",
                "deficiency notice",
                "listing deficiency",
                "continued listing deficiency",
                "minimum bid price deficiency",
                "minimum bid requirement",
                "minimum bid price requirement",
                "bid price deficiency",
                "below the minimum bid price",
                "does not satisfy continued listing",
                "not in compliance with nasdaq",
                "notified by nasdaq",
                "received notice from nasdaq",
                "nasdaq staff determination",
                "listing qualifications department"
        )) {
            return result(
                    CatalystType.NASDAQ_NONCOMPLIANCE,
                    "Nasdaq noncompliance/delinquency language found"
            );
        }

        if (containsAny(
                text,
                "regains nasdaq compliance",
                "regained nasdaq compliance",
                "nasdaq compliance regained",
                "regains compliance with nasdaq",
                "regained compliance with nasdaq",
                "regains compliance with minimum bid",
                "regained compliance with minimum bid",
                "regains nasdaq minimum bid",
                "regained nasdaq minimum bid",
                "regains nasdaq minimum bid listing requirements",
                "currently complies with the minimum stockholders' equity requirement",
                "complies with the minimum stockholders' equity requirement",
                "nasdaq confirms compliance",
                "nasdaq confirmed compliance",
                "nasdaq grants compliance",
                "nasdaq grants continued listing",
                "meets nasdaq continued listing requirements",
                "satisfies nasdaq continued listing requirements",
                "notice of compliance from nasdaq",
                "has regained compliance with all applicable nasdaq listing standards",
                "boosts equity to regain nasdaq compliance"
        )) {
            return result(
                    CatalystType.NASDAQ_COMPLIANCE,
                    "Nasdaq compliance regained/confirmed language found"
            );
        }

        if (containsAny(
                text,
                "nasdaq grants extension",
                "nasdaq granted extension",
                "nasdaq extension",
                "extension from nasdaq",
                "granted an extension by nasdaq",
                "granted additional time by nasdaq",
                "additional time to regain compliance",
                "additional 180-day period",
                "180-day extension",
                "180 day extension",
                "compliance extension",
                "extension to regain compliance",
                "period to regain compliance",
                "grace period to regain compliance",
                "nasdaq hearing panel grants",
                "hearing panel grants extension",
                "continued listing extension",
                "stay of suspension",
                "suspension stayed",
                "delisting stayed",
                "receives nasdaq exception"
        )) {
            return result(
                    CatalystType.NASDAQ_COMPLIANCE_EXTENSION,
                    "Nasdaq compliance extension/continued listing language found"
            );
        }

        if (containsAny(
                text,
                "regains nyse compliance",
                "regained nyse compliance",
                "nyse compliance regained",
                "regains compliance with nyse",
                "regained compliance with nyse",
                "nyse american compliance",
                "regains compliance with nyse american",
                "regained compliance with nyse american",
                "nyse confirms compliance",
                "nyse confirmed compliance",
                "compliance plan with nyse american accepted"
        )) {
            return result(
                    CatalystType.NYSE_COMPLIANCE,
                    "NYSE compliance language found"
            );
        }

        if (containsAny(
                text,
                "regains exchange compliance",
                "regained exchange compliance",
                "continued listing requirements satisfied",
                "satisfies continued listing requirements",
                "meets continued listing requirements",
                "listing compliance regained",
                "regains listing compliance",
                "regained listing compliance"
        )) {
            return result(
                    CatalystType.EXCHANGE_COMPLIANCE,
                    "Exchange compliance language found"
            );
        }

        if (containsAny(
                text,
                "transfers listing",
                "transfer its public securities from nasdaq to nyse",
                "listing transfer",
                "moves listing to",
                "changes listing venue",
                "to transfer listing to nasdaq from nyse"
        )) {
            return result(
                    CatalystType.LISTING_TRANSFER,
                    "Listing transfer language found"
            );
        }

        if (containsAny(
                text,
                "name change",
                "ticker change",
                "changes name",
                "changes ticker",
                "new ticker symbol",
                "begins trading under",
                "rebranding to"
        )) {
            return result(
                    CatalystType.NAME_TICKER_CHANGE,
                    "Name/ticker change language found"
            );
        }

        return null;
    }

    private CatalystResult matchDebtSecuritiesOffering(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        boolean debtTenderOrNoteOffering = containsAny(
                text,
                "debt securities",
                "outstanding debt securities",
                "offers to purchase for cash",
                "pricing terms of offers to purchase",
                "terms of offers to purchase",
                "cash tender offer",
                "tender offers for notes",
                "tender offer for notes",
                "exchange offer for notes",
                "offers to exchange notes",
                "senior notes",
                "notes due",
                "convertible notes",
                "subordinated notes",
                "debt offering",
                "offering of notes",
                "offering of senior notes",
                "prices offering of notes",
                "prices offering of senior notes",
                "pricing of senior notes",
                "pricing of notes",
                "private offering of notes",
                "registered offering of notes",
                "repurchase of notes",
                "redeem notes",
                "redemption of notes",
                "note repurchase",
                "debt refinancing",
                "refinancing transaction",
                "liability management transaction"
        );

        if (debtTenderOrNoteOffering) {
            return result(
                    CatalystType.OFFERING_DILUTION,
                    "Debt securities / notes / tender-offer financing language blocked before clinical or M&A scoring"
            );
        }

        return null;
    }

    private CatalystResult matchDilutionOfferingsAndFinancing(String text) {
        if (containsAny(
                text,
                "convertible preferred offering",
                "preferred stock offering",
                "convertible preferred stock",
                "series a preferred stock offering",
                "series b preferred stock offering",
                "convertible notes",
                "convertible notes due",
                "4.125% convertible notes"
        )) {
            return result(
                    CatalystType.CONVERTIBLE_PREFERRED_OFFERING,
                    "Convertible preferred/convertible note financing language found"
            );
        }

        if (containsAny(
                text,
                "private placement",
                "common shares at $",
                "warrants approved",
                "private placement of",
                "registered direct and concurrent private placement",
                "pipe financing",
                "priced private placement",
                "subscription receipts",
                "subscription receipt",
                "class a ordinary shares",
                "pre-delivery shares",
                "streeterville capital"
        )) {
            return result(
                    CatalystType.PRIVATE_PLACEMENT,
                    "Private placement/subscription receipt language found"
            );
        }

        if (containsAny(
                text,
                "structured financing transaction",
                "issue shares at",
                "shares at $",
                "institutional investment fund in exchange for preferred units"
        )) {
            return result(
                    CatalystType.PRIVATE_PLACEMENT,
                    "Structured equity financing/private placement language found"
            );
        }

        if (containsAny(
                text,
                "shelf offering",
                "mixed shelf",
                "shelf registration",
                "files shelf",
                "filed shelf",
                "universal shelf",
                "s-3 registration statement",
                "form s-3"
        )) {
            return result(
                    CatalystType.SHELF_OFFERING,
                    "Shelf offering language found"
            );
        }

        if (containsAny(
                text,
                "best-efforts offering",
                "public offering",
                "common stock offering",
                "registered direct",
                "at-the-market",
                "atm program",
                "equity distribution agreement",
                "files prospectus",
                "filed prospectus",
                "stock offering",
                "secondary offering",
                "securities offering",
                "securities purchase agreement",
                "atm sales agreement",
                "terminates atm issuance sales pact",
                "may offer up to",
                "resale of up to",
                "resale shares",
                "resale prospectus",
                "selling stockholder",
                "selling shareholders",
                "warrant exercise",
                "prices offering",
                "priced offering",
                "equity line agreements"
        )) {
            return result(
                    CatalystType.OFFERING_DILUTION,
                    "Offering/dilution language found"
            );
        }

        if (containsAny(
                text,
                "senior notes offering",
                "commences senior notes offering",
                "plans to commence senior notes offering",
                "senior secured term loan",
                "term loan facility",
                "credit agreement",
                "revolving credit facility",
                "first lien term loan",
                "repricing of",
                "financing facility",
                "gpu financing facility",
                "debt exchange agreement",
                "new bond sale",
                "bond sale",
                "raises money for debt repayment",
                "private credit facility",
                "closes $300m private credit facility",
                "closes $300m financing",
                "upsizes and replaces",
                "term facility",
                "extends geely backed term facility"
        )) {
            return result(
                    CatalystType.SEC_FILING,
                    "Debt financing / credit facility language found"
            );
        }

        return null;
    }

    private CatalystResult matchBiotechAndHealthcare(String text) {
        if (!hasHealthcareContext(text) ||
                isOfferingOrDilutionText(text) ||
                isDebtSecuritiesFinancingText(text) ||
                isCorporateAdministrativeHealthcareFalsePositive(text) ||
                isNonHealthcareIndustrialOrConsumerProductText(text)) {
            return null;
        }

        if (containsAny(
                text,
                "fda rejects",
                "complete response letter",
                "crl from the fda",
                "not approved",
                "failed to receive approval"
        )) {
            return result(
                    CatalystType.FDA_REJECTION,
                    "FDA rejection language found"
            );
        }

        if (containsAny(
                text,
                "clinical hold",
                "fda places hold",
                "trial hold",
                "study hold",
                "partial clinical hold"
        )) {
            return result(
                    CatalystType.CLINICAL_HOLD,
                    "Clinical hold language found"
            );
        }

        if (containsAny(
                text,
                "clinical delay",
                "trial delay",
                "delays trial",
                "delayed trial",
                "delays enrollment",
                "enrollment delay",
                "pauses enrollment"
        )) {
            return result(
                    CatalystType.CLINICAL_DELAY,
                    "Clinical delay language found"
            );
        }

        if (containsAny(
                text,
                "fails late-stage trial",
                "failed late-stage trial",
                "late-stage trial fails",
                "skin cancer treatment fails",
                "treatment fails late-stage trial",
                "misses main goal",
                "missed the main goal",
                "trial failed",
                "failed trial",
                "missed primary endpoint",
                "did not meet primary endpoint",
                "did not achieve primary endpoint",
                "did not achieve the primary endpoint",
                "failed to achieve primary endpoint",
                "failed to achieve the primary endpoint",
                "company discontinued its development",
                "discontinued its development",
                "development discontinued",
                "study failed",
                "failed to meet",
                "no statistically significant",
                "terminated study",
                "discontinues trial",
                "stops study",
                "fda flags concerns",
                "malignancy risk",
                "discontinues program",
                "elevated serious and grade 5 adverse events",
                "benefit-risk scrutiny"
        )) {
            return result(
                    CatalystType.CLINICAL_TRIAL_FAILURE,
                    "Clinical trial/program failure or FDA concern language found"
            );
        }

        if (containsAny(
                text,
                "rolling nda submission",
                "completes rolling nda submission",
                "completed rolling nda submission",
                "pre-nda meeting",
                "pre-nda",
                "on track to submit nda",
                "nda submission",
                "submits nda",
                "submitted nda",
                "new drug application",
                "bla submission",
                "submits bla",
                "submitted bla",
                "biologics license application",
                "marketing authorization application",
                "maa submission",
                "submits maa",
                "submitted maa",
                "regulatory submission",
                "submits regulatory application",
                "submitted regulatory application",
                "fda filing accepted",
                "fda accepts filing",
                "fda accepts nda",
                "fda accepts bla",
                "pdufa date",
                "priority review",
                "fast track designation",
                "breakthrough therapy designation",
                "regenerative medicine advanced therapy",
                "rmat designation",
                "rare pediatric disease designation",
                "generic design assessment process",
                "submits application to the uk's generic design assessment",
                "moves up key fda review timeline",
                "fda review timeline",
                "critical trial results",
                "ind clearance",
                "receives fda ind clearance",
                "submits protocol",
                "under existing ind",
                "510(k) submission",
                "submits 510(k)",
                "submits 510(k) filings",
                "support fda 510(k)",
                "registrational development",
                "market clearance"
        )) {
            return result(
                    CatalystType.FDA_REGISTRATION,
                    "FDA/NDA/BLA/IND/510(k) regulatory submission or review language found"
            );
        }

        if (containsAny(
                text,
                "orphan drug designation",
                "orphan designation",
                "receives orphan drug",
                "granted orphan drug"
        )) {
            return result(
                    CatalystType.ORPHAN_DRUG_DESIGNATION,
                    "Orphan drug designation language found"
            );
        }

        if (containsAny(
                text,
                "met primary endpoint",
                "meets primary endpoint",
                "met secondary endpoint",
                "meets secondary endpoint",
                "trial met its primary endpoint",
                "study met its primary endpoint",
                "statistically significant",
                "successful trial",
                "positive topline",
                "positive top-line",
                "positive interim data",
                "safety review clears next stage",
                "safety review clears",
                "meets safety goal",
                "experimental treatment meets safety goal",
                "significantly more patients achieving"
        )) {
            return result(
                    CatalystType.CLINICAL_TRIAL_SUCCESS,
                    "Positive clinical trial endpoint/safety milestone language found"
            );
        }

        if (containsAny(
                text,
                "strong clinical data",
                "positive clinical data",
                "promising clinical data",
                "encouraging clinical data",
                "highlights leukemia treatment data",
                "highlights tumor response data",
                "highlights cancer-detection data",
                "highlights pancreatic cancer drug progress",
                "highlights cancer drug results",
                "clinical data across",
                "clinical data featuring",
                "clinical execution milestones",
                "key clinical execution milestones",
                "first-in-human clinical data",
                "late-stage lupus trial results",
                "late-stage breast cancer immunotherapy trial",
                "late-stage trial results",
                "late-stage revumenib",
                "updated efficacy and safety data",
                "updated efficacy",
                "updated phase 1",
                "updated phase 2",
                "updated phase 3",
                "extended data",
                "long-term results",
                "exploratory imaging analyses",
                "mri imaging analyses",
                "early results from",
                "interim safety and efficacy data",
                "interim efficacy data",
                "new interim data",
                "interim analysis findings",
                "interim analyses",
                "interim analysis",
                "positive trial data",
                "strong trial data",
                "phase 1 data",
                "phase 2 data",
                "phase 3 data",
                "phase i data",
                "phase ii data",
                "phase iii data",
                "phase 1 results",
                "phase 2 results",
                "phase 3 results",
                "phase i results",
                "phase ii results",
                "phase iii results",
                "phase 1 study",
                "phase 2 study",
                "phase 3 study",
                "phase 1a trial",
                "phase 1b/2 trial",
                "phase 1/2 clinical trial",
                "multicenter phase 1/2",
                "pivotal phase 3 trial",
                "phase 2/3",
                "phase iii",
                "phase ii",
                "phase i",
                "phaseb clinicla trial",
                "first-in-human phase 1",
                "first-in-human dosing",
                "first patient dosed",
                "first patient enrollment",
                "starts infant study",
                "doses first",
                "doses first brain tumor patient",
                "begins human testing",
                "initial clinical trial",
                "initiates first-in-human dosing",
                "dose escalation data",
                "dose escalation",
                "enrolls final participant",
                "completes enrollment",
                "patient enrollment update",
                "patients have been dosed",
                "activated trial sites",
                "108 patients enrolled",
                "top-line data expected",
                "topline data expected",
                "clinical trial on",
                "clinical trial evaluating",
                "ongoing randomized",
                "proof-of-concept study",
                "phase 2a human proof-of-concept",
                "weight-loss drug results",
                "obesity treatment pipeline",
                "obesity medicines move into late-stage testing",
                "new combination therapy trial",
                "rare hypoglycemia treatment",
                "real-world data",
                "near real-time real-world data",
                "response rate",
                "hematologic response",
                "tumor response",
                "molecular activity",
                "spleen volume reduction",
                "symptom improvement",
                "cr rate",
                "orr",
                "overall response rate",
                "objective response rate",
                "complete response rate",
                "complete response",
                "partial response",
                "disease control rate",
                "duration of response",
                "durable response",
                "stable disease rate",
                "disease stabilization",
                "monotherapy achieves",
                "combination with",
                "mean reduction",
                "risk reduction",
                "reduction in risk of disease progression",
                "reduction in new",
                "achieving physiologic",
                "remained angioedema-free",
                "independence from active vitamin d",
                "strong efficacy",
                "superior efficacy",
                "improved survival",
                "progression-free survival",
                "progression free survival",
                "overall survival",
                "event-free survival",
                "minimal residual disease",
                "mrd negativity",
                "tumor reduction",
                "lesion volume reduction",
                "viral load reduction",
                "a1c reduction",
                "ldl reduction",
                "pain score reduction",
                "anemia measures",
                "hepcidin",
                "higher iron levels",
                "clinical impact",
                "meaningful clinical benefit",
                "biomarker",
                "diagnostic accuracy",
                "superior diagnostic accuracy",
                "acute in vivo implant study",
                "preclinical data",
                "preclinical results",
                "preclinical activity",
                "preclinical filovirus activity",
                "cancer models",
                "anti-tumor activity",
                "car-t expansion",
                "combination synergy",
                "mutation-agnostic potential",
                "animal was immunized",
                "computational prediction",
                "published jacc study",
                "peer-reviewed study showing safety gains",
                "study showing safety gains",
                "plant biotechnology journal study",
                "co-authors plant biotechnology journal study",
                "biological factors associated",
                "new research study"
        )) {
            return result(
                    CatalystType.DRUG_DATA_POSITIVE,
                    "Positive clinical/preclinical data or clinical milestone language found"
            );
        }

        if (containsAny(
                text,
                "clinical data presentation",
                "to present data",
                "will present data",
                "presents data at",
                "presented new radiochemistry data",
                "presents new data",
                "present multiple new analyses",
                "announces presentation of phase",
                "presentation of phase",
                "poster presentation",
                "oral presentation",
                "conference presentation",
                "late-breaking presentation",
                "announces published abstract",
                "published abstract",
                "published abstract and poster",
                "poster on its phase",
                "publication of peer-reviewed manuscript",
                "peer-reviewed manuscript",
                "published new manuscript",
                "publishes post-hoc pooled analysis",
                "post-hoc pooled analysis",
                "to host r&d day",
                "host r&d day",
                "r&d day",
                "to host conference call",
                "conference call and live webcast to review results",
                "at asco",
                "at eha",
                "at eular",
                "at endo",
                "at snmmi",
                "at snmmi 2026",
                "snmmi 2026 annual meeting",
                "asco annual meeting"
        )) {
            return result(
                    CatalystType.CLINICAL_DATA_PRESENTATION,
                    "Clinical data presentation/publication language found"
            );
        }

        if (containsAny(
                text,
                "fda device alert",
                "device alert",
                "safety communication from fda"
        )) {
            return result(
                    CatalystType.FDA_DEVICE_ALERT,
                    "FDA device alert language found"
            );
        }

        if (containsAny(
                text,
                "fda clears",
                "fda clearance",
                "cleared by the fda",
                "receives fda clearance",
                "wins fda clearance",
                "510(k) clearance",
                "fda-510(k)-cleared",
                "de novo clearance",
                "over-the-counter continuous glucose monitor"
        )) {
            return result(
                    CatalystType.FDA_CLEARANCE,
                    "FDA clearance language found"
            );
        }

        if (containsAny(
                text,
                "fda approval",
                "approved by the fda",
                "receives fda approval",
                "wins fda approval",
                "fda approves",
                "approved in the u.s.",
                "approved in the us",
                "u.s. approval",
                "us approval",
                "canada approval",
                "receives canada approval",
                "successful canada approval",
                "european commission approves",
                "mhra says",
                "authorized to treat",
                "grants approval with conditions",
                "therapeutic goods administration of australia grants approval",
                "approved for treatment",
                "treatment approved",
                "approved as first",
                "regulatory approval",
                "approval to treat"
        )) {
            return result(
                    CatalystType.FDA_APPROVAL,
                    "FDA/regulatory approval language found"
            );
        }

        if (containsAny(
                text,
                "commercial european launch",
                "first commercial european launch",
                "u.s. launch",
                "us launch",
                "launch of",
                "clinical availability",
                "upcoming clinical availability",
                "commercial launch",
                "commercial release",
                "officially renames"
        )) {
            return result(
                    CatalystType.PRODUCT_LAUNCH,
                    "Healthcare product launch/commercial availability language found"
            );
        }

        return null;
    }

    private CatalystResult matchEarningsGuidanceAndCompanyOutlook(String text) {
        if (containsAny(
                text,
                "eps beats",
                "adj. eps beats",
                "adj eps beats",
                "sales beat",
                "sales beats",
                "revenue beat",
                "revenue beats",
                "beats estimate",
                "beats estimates",
                "beats expectations",
                "beat estimate",
                "beat estimates",
                " beats $",
                " beat $",
                "sales $",
                "revenue $",
                "upbeat earnings",
                "posts upbeat earnings",
                "better-than-expected earnings",
                "better than expected earnings",
                "beat-and-raise",
                "beat-and-raise q1",
                "q1 beat driven",
                "q1 beat"
        )) {
            if (!containsAny(text, "misses", " miss ", "down from")) {
                return result(
                        CatalystType.EARNINGS_BEAT,
                        "Earnings/sales beat language found"
                );
            }
        }

        if (containsAny(
                text,
                "eps $(",
                "misses $(",
                "misses estimate",
                "misses estimates",
                "miss estimate",
                "miss estimates",
                "sales miss",
                "sales misses",
                "revenue miss",
                "revenue misses",
                "earnings miss",
                "misses earnings",
                "misses revenue",
                "misses eps",
                "wider loss than expected",
                "lower-than-expected earnings",
                "lower than expected earnings"
        )) {
            if (containsAny(text, "miss")) {
                return result(
                        CatalystType.EARNINGS_MISS,
                        "Earnings or sales miss language found"
                );
            }
        }

        if (containsAny(
                text,
                "mixed earnings",
                "mixed quarterly results",
                "mixed q1 results",
                "mixed q2 results",
                "mixed q3 results",
                "mixed q4 results",
                "mixed results",
                "inline",
                "beats",
                "miss"
        )) {
            if (containsAny(text, "beat") && containsAny(text, "miss", "lowers", "inline")) {
                return result(
                        CatalystType.EARNINGS_MIXED,
                        "Mixed earnings/guidance language found"
                );
            }
        }

        if (containsAny(
                text,
                "eps up from",
                "sales up from",
                "revenue up from",
                "sales $",
                "q1 sales $",
                "q2 sales $",
                "q3 sales $",
                "q4 sales $",
                "q1 eps $",
                "q2 eps $",
                "q3 eps $",
                "q4 eps $",
                "net sales",
                "increase of",
                "up from",
                "rose",
                "grew"
        )) {
            if (containsAny(text, "up from", "increase of", "rose", "grew", "beat") &&
                    !containsAny(text, "miss", "down from", "declines", "lowers")) {
                return result(
                        CatalystType.EARNINGS_BEAT,
                        "Positive year-over-year earnings/sales update language found"
                );
            }
        }

        if (containsAny(
                text,
                "eps down from",
                "sales down from",
                "revenue down from",
                "earnings decline",
                "profit declines",
                "revenue declines",
                "lower revenue",
                "lower earnings",
                "drop in revenue",
                "profit falls",
                "net loss widens",
                "down from"
        )) {
            return result(
                    CatalystType.EARNINGS_DECLINE,
                    "Earnings decline language found"
            );
        }

        if (containsAny(
                text,
                "earnings call transcript",
                "earnings transcript",
                "q1 transcript",
                "q2 transcript",
                "q3 transcript",
                "q4 transcript"
        )) {
            return result(
                    CatalystType.EARNINGS_TRANSCRIPT,
                    "Earnings transcript language found"
            );
        }

        if (containsAny(
                text,
                "scheduled to report",
                "expected to report earnings",
                "reports earnings after the bell",
                "reports earnings before the bell",
                "earnings preview",
                "likely to report",
                "ahead of earnings call",
                "top wall street forecasters maintain"
        )) {
            return result(
                    CatalystType.EARNINGS_SCHEDULED,
                    "Scheduled earnings/preview language found"
            );
        }

        if (containsAny(
                text,
                "raises guidance",
                "raised guidance",
                "raises outlook",
                "raised outlook",
                "boosts guidance",
                "boosted guidance",
                "increases guidance",
                "increased guidance",
                "increases outlook",
                "increased outlook",
                "lifts guidance",
                "lifted guidance",
                "raises full-year guidance",
                "raises annual guidance",
                "raises fy guidance",
                "raises fy2026",
                "raises fy26",
                "raises q2 guidance",
                "raises q3 guidance",
                "raises revenue outlook",
                "raises fy26 revenue outlook",
                "raises fy2027",
                "expects fy",
                "expects q2",
                "expects q3",
                "sees fy",
                "sees q1",
                "sees q2",
                "sees q3",
                "sees q4",
                "sees sales",
                "sees revenue",
                "preliminary adj eps expected to be more than",
                "expected to be more than",
                "reaffirms q2",
                "reaffirms fy",
                "affirms fy",
                "affirms fy2026",
                "reiterates q2 guidance",
                "reiterates fy guidance",
                "exceeds top-end guidance",
                "on track to achieve",
                "expected to reach",
                "expected to grow",
                "expects organic sales growth"
        )) {
            if (!containsAny(text, "lowers", "cuts", "cut", "reduces", "down from")) {
                return result(
                        CatalystType.GUIDANCE_RAISE,
                        "Positive guidance/outlook or reaffirmation language found"
                );
            }
        }

        if (containsAny(
                text,
                "cuts guidance",
                "cut guidance",
                "lowers guidance",
                "lowered guidance",
                "reduces guidance",
                "reduced guidance",
                "cuts outlook",
                "cut outlook",
                "lowers outlook",
                "lowered outlook",
                "guidance cut",
                "outlook cut",
                "lowers q2 preliminary sales guidance",
                "lowers q2 guidance",
                "lowers fy2026",
                "cuts revenue guidance",
                "lowers free cash flow conversion",
                "lowers fy26"
        )) {
            return result(
                    CatalystType.GUIDANCE_CUT,
                    "Guidance/outlook cut language found"
            );
        }

        return null;
    }

    private CatalystResult matchDealsMergersStrategicActionsAndAssetSales(String text) {
        if (containsAny(
                text,
                "wants",
                "suggests",
                "proposes",
                "calls for",
                "urges",
                "speculates",
                "could merge",
                "should merge",
                "idea of merging",
                "wants elon musk to merge",
                "give us one company to bet on"
        ) && containsAny(
                text,
                "merge",
                "merger",
                "space x",
                "spacex",
                "tesla"
        )) {
            return result(
                    CatalystType.TAKEOVER_SPECULATION,
                    "Speculative merger/commentary language found"
            );
        }

        if (containsAny(
                text,
                "buyout offer",
                "takeover offer",
                "preparing an offer to buy",
                "planning to bid",
                "going private",
                "private equity offer",
                "unsolicited offer",
                "all-cash offer",
                "offer to buy"
        )) {
            return result(
                    CatalystType.BUYOUT_OFFER,
                    "Buyout/takeover offer language found"
            );
        }

        if (containsAny(
                text,
                "takeover speculation",
                "takeover rumors",
                "takeover chatter",
                "buyout speculation",
                "deal speculation",
                "weighs london listing",
                "negotiates africa business sale",
                "said to near",
                "near €3b deal",
                "sources say",
                "watching wynn resorts"
        )) {
            return result(
                    CatalystType.TAKEOVER_SPECULATION,
                    "Takeover/listing/deal speculation language found"
            );
        }

        if (isLawFirmDealInvestigationOrShareholderAlert(text) || isDebtSecuritiesFinancingText(text)) {
            return null;
        }

        if (containsAny(
                text,
                "merger",
                "merge with",
                "merged with",
                "reverse merger",
                "going-public transaction",
                "acquisition",
                "to acquire",
                "acquires",
                "acquires 100%",
                "consolidates 100% ownership",
                "majority stake",
                "takes majority stake",
                "definitive agreement",
                "sale talks",
                "possible media tie-up",
                "deal consideration",
                "business combination",
                "scheme of arrangement",
                "signs deal to buy",
                "deal to buy",
                "buy 76% stake",
                "stake for $",
                "minority ownership investment",
                "investment in ai market intelligence firm",
                "invests in two privately held companies",
                "ventures invests"
        )) {
            return result(
                    CatalystType.MERGER_ACQUISITION,
                    "M&A/stake acquisition/investment language found"
            );
        }

        if (containsAny(
                text,
                "asset sale",
                "sells assets",
                "sale of assets",
                "divests",
                "divestiture",
                "sells business unit",
                "sells operations",
                "sells obagi medical",
                "sells its wealth management",
                "sells wealth management",
                "sells manhattan property",
                "to sell an atlanta asset",
                "intends to sell",
                "asset dispositions",
                "sale of 35 senior housing",
                "sale tower portfolio",
                "sale of tower portfolio",
                "sale of co's remaining",
                "remaining 19%",
                "exits jv",
                "initial closing of sale",
                "completes initial closing of sale"
        )) {
            return result(
                    CatalystType.ASSET_SALE,
                    "Asset sale/divestiture language found"
            );
        }

        if (containsAny(
                text,
                "property acquisition",
                "acquires property",
                "land acquisition",
                "mineral property acquisition"
        )) {
            return result(
                    CatalystType.PROPERTY_ACQUISITION,
                    "Property acquisition language found"
            );
        }

        if (containsAny(
                text,
                "strategic review",
                "exploring strategic alternatives",
                "exploration of strategic alternatives",
                "review of strategic alternatives",
                "evaluating strategic alternatives",
                "including sale of company",
                "strategic evolution of corporate direction",
                "strategic development initiative",
                "expands strategic focus"
        )) {
            return result(
                    CatalystType.STRATEGIC_REVIEW,
                    "Strategic review/evolution language found"
            );
        }

        if (containsAny(
                text,
                "spin off",
                "spinoff",
                "spin-off",
                "separation of",
                "separate into"
        )) {
            return result(
                    CatalystType.SPINOFF,
                    "Spinoff language found"
            );
        }

        return null;
    }

    private CatalystResult matchContractsOrdersAndCustomers(String text) {
        if (containsAny(
                text,
                "major contract",
                "wins contract",
                "awarded contract",
                "government contract",
                "contract worth",
                "contract valued",
                "multi-year contract",
                "secures contract",
                "secures first contract",
                "homebuilder contracts",
                "major homebuilder contracts",
                "mro contracts",
                "long-term mro contracts",
                "german contract",
                "unnamed ai enterprise",
                "$15m annual contract",
                "defense contract",
                "army contract",
                "navy contract",
                "air force contract",
                "dod contract",
                "task order",
                "air force support task order",
                "wins $8b",
                "20-year nsf contract",
                "selected by",
                "selected as",
                "to provide campus hospitality services",
                "provide high-bandwidth satellite communications",
                "support noaa",
                "department of energy-funded research program",
                "doe-funded research program",
                "program manager role",
                "extends program manager role",
                "limited notification to proceed",
                "notification to proceed"
        )) {
            if (containsAny(text, "renewal")) {
                return result(
                        CatalystType.CONTRACT_RENEWAL,
                        "Contract renewal language found"
                );
            }

            return result(
                    CatalystType.MAJOR_CONTRACT,
                    "Major contract/selection language found"
            );
        }

        if (containsAny(
                text,
                "receives purchase order",
                "received purchase order",
                "purchase order from",
                "customer order",
                "new order from",
                "booked order",
                "production order",
                "new production order",
                "billion order",
                "large order",
                "follow-on order",
                "cumulative orders",
                "initial purchase orders",
                "receives cumulative orders",
                "receives ~$",
                "receives ~",
                "multi-system order",
                "orders of over",
                "order totaling",
                "security division order",
                "$142m in orders",
                "receives $142m in orders"
        )) {
            return result(
                    CatalystType.MAJOR_ORDER,
                    "Major order/purchase order language found"
            );
        }

        if (containsAny(
                text,
                "customer order",
                "customer orders",
                "order from customer",
                "customer h&h group is continuing to expand its use",
                "adopts platform",
                "adopts clarivate",
                "adopts",
                "adoption of",
                "implements platform",
                "deploys platform",
                "rolls out platform",
                "selects platform",
                "unify patent and trademark management",
                "to support u.s. distribution",
                "support u.s. distribution",
                "distribution of",
                "commercial use"
        )) {
            return result(
                    CatalystType.CUSTOMER_ORDER,
                    "Customer adoption/order/platform selection language found"
            );
        }

        if (containsAny(
                text,
                "contract renewal",
                "renews contract",
                "renewed contract",
                "extends contract",
                "contract extension",
                "multi-year renewal",
                "multi-year renewal"
        )) {
            return result(
                    CatalystType.CONTRACT_RENEWAL,
                    "Contract renewal language found"
            );
        }

        if (containsAny(
                text,
                "product sale",
                "sells product",
                "first sale",
                "commercial sale",
                "initial sale"
        )) {
            return result(
                    CatalystType.PRODUCT_SALE,
                    "Product sale language found"
            );
        }

        return null;
    }

    private CatalystResult matchOperationsProductionAndResourceDevelopment(String text) {
        if (containsAny(
                text,
                "capital investment",
                "invests $",
                "investment in facility",
                "new investment",
                "commits chf",
                "commits additional funding",
                "additional funding",
                "investment-grade gpu financing facility",
                "€175m investment",
                "$2 billion investment",
                "$15m to expand",
                "plans to invest",
                "to invest $",
                "supporting $60m investment",
                "site commitment",
                "accelerate ai transformation"
        )) {
            return result(
                    CatalystType.CAPITAL_INVESTMENT,
                    "Capital investment/funding language found"
            );
        }

        if (containsAny(
                text,
                "facility expansion",
                "expands facility",
                "expanding facility",
                "expanded manufacturing facility",
                "advanced manufacturing facility",
                "manufacturing and operations network",
                "magnet manufacturing",
                "new magnet manufacturing",
                "refined metals operation",
                "plant expansion",
                "manufacturing expansion",
                "opens new missile assembly building",
                "new missile assembly building",
                "newly expanded",
                "battery plant",
                "solid-state battery plant",
                "new rng facilities",
                "construction of two new rng facilities",
                "powerbank's 3.1 mw",
                "solar project",
                "approved for incentives"
        )) {
            return result(
                    CatalystType.FACILITY_EXPANSION,
                    "Facility/manufacturing/energy project expansion language found"
            );
        }

        if (containsAny(
                text,
                "headquarters expansion",
                "expands headquarters",
                "new headquarters"
        )) {
            return result(
                    CatalystType.HEADQUARTERS_EXPANSION,
                    "Headquarters expansion language found"
            );
        }

        if (containsAny(
                text,
                "partner tier status",
                "premier partner",
                "elite partner status",
                "preferred partner status"
        )) {
            return result(
                    CatalystType.PARTNER_TIER_STATUS,
                    "Partner tier status language found"
            );
        }

        if (containsAny(
                text,
                "service milestone",
                "milestone achieved",
                "reaches milestone",
                "operational milestone",
                "completion of development",
                "completes logistics transformation",
                "announces completion",
                "completes initial purchase",
                "delivery and receipt",
                "completing production",
                "completion of production",
                "takes direct control of drone manufacturing"
        )) {
            return result(
                    CatalystType.SERVICE_MILESTONE,
                    "Service/operational milestone language found"
            );
        }

        if (containsAny(
                text,
                "material qualification",
                "qualified by",
                "qualification from",
                "qualified supplier"
        )) {
            return result(
                    CatalystType.MATERIAL_QUALIFICATION,
                    "Material qualification language found"
            );
        }

        if (containsAny(
                text,
                "exploration program",
                "drilling program",
                "commences drilling",
                "begins drilling",
                "drilling results",
                "reports drilling results",
                "initial exploration drilling",
                "rock sampling program",
                "encouraging observations from rock sampling",
                "well results",
                "gold intercept",
                "copper intercept",
                "lithium intercept",
                "uranium-copper-vanadium",
                "mineral resource estimate",
                "updated mineral resource estimate",
                "mineral resources",
                "mine life",
                "mine production growth",
                "critical path construction",
                "surface construction",
                "construction activities",
                "project in utah",
                "gold project",
                "mill expansion project",
                "civil works on the mill installation",
                "tanbreez rare earth project",
                "project acceleration initiatives",
                "considerable progress on workstreams",
                "support development of",
                "project development",
                "guayables project",
                "sleeper gold project",
                "npv of $",
                "net present value",
                "preliminary economic assessment",
                "feasibility study",
                "technical report"
        )) {
            return result(
                    CatalystType.EXPLORATION_PROGRAM,
                    "Exploration/mineral resource/construction language found"
            );
        }

        if (containsAny(
                text,
                "production growth",
                "targets production growth",
                "record grain movement",
                "average daily volume",
                "reports may average daily volume",
                "ltl growth",
                "shipments up",
                "tonnage rising",
                "revenue per day rises",
                "operating metrics",
                "vehicle deliveries jump",
                "delivered 33,350 vehicles",
                "monthly deliveries",
                "vehicles delivered overall",
                "car registrations",
                "new car registrations",
                "new registrations",
                "may sales increase",
                "vehicle sales",
                "electrified vehicles sales",
                "loan marketplace volume rose",
                "consumer loan volume grew",
                "may btc production",
                "annual international revenues to grow",
                "preliminary unaudited revenue",
                "adds 387 new community locations",
                "posts q1 deliveries",
                "up 57%",
                "revenue surges",
                "gains traction",
                "tops 37k monthly deliveries"
        )) {
            return result(
                    CatalystType.POSITIVE_BUSINESS_MOMENTUM,
                    "Operational KPI / delivery growth language found"
            );
        }

        if (containsAny(
                text,
                "planned turnaround",
                "begin planned turnaround",
                "facility turnaround",
                "no impact on expected duration or cost"
        )) {
            return result(
                    CatalystType.BUSINESS_UPDATE,
                    "Facility turnaround / operating update language found"
            );
        }

        return null;
    }

    private CatalystResult matchCapitalReturnsAndBalanceSheet(String text) {
        if (containsAny(
                text,
                "share buyback",
                "stock buyback",
                "buys back",
                "repurchases company shares",
                "repurchases shares",
                "buyback program",
                "buyback plan",
                "repurchase program",
                "repurchase plan",
                "board approves share buyback",
                "board approves stock buyback",
                "to buy back",
                "normal course issuer bid",
                "amend normal course issuer bid",
                "own funds to purchase company stock",
                "share repurchase authorization",
                "authorizes repurchase",
                "authorizes concurrent share repurchase"
        )) {
            return result(
                    CatalystType.SHARE_BUYBACK,
                    "Share buyback/repurchase language found"
            );
        }

        if (containsAny(
                text,
                "note repurchase",
                "repurchases notes",
                "debt repurchase",
                "senior notes repurchase"
        )) {
            return result(
                    CatalystType.NOTE_REPURCHASE,
                    "Note repurchase language found"
            );
        }

        if (containsAny(
                text,
                "declares dividend",
                "dividend increase",
                "raises dividend",
                "increases class a dividend",
                "increases class b dividend",
                "special dividend",
                "quarterly dividend",
                "cash dividend",
                "share dividend ratio",
                "sets share dividend ratio"
        )) {
            return result(
                    CatalystType.DIVIDEND,
                    "Dividend language found"
            );
        }

        if (containsAny(
                text,
                "mini-tender offer",
                "mini tender offer"
        )) {
            return result(
                    CatalystType.MINI_TENDER_OFFER,
                    "Mini tender offer language found"
            );
        }

        if (containsAny(
                text,
                "rights plan",
                "poison pill",
                "stockholder rights plan"
        )) {
            return result(
                    CatalystType.RIGHTS_PLAN,
                    "Rights plan language found"
            );
        }

        return null;
    }

    private CatalystResult matchProductTechnologyAndLaunches(String text) {
        CatalystResult noise = matchProductLaunchFalsePositiveNoise(text);
        if (noise != null) {
            return noise;
        }

        if (containsAny(
                text,
                "launches",
                "launched",
                "launch of",
                "u.s. launch",
                "us launch",
                "unveils",
                "to unveil",
                "introduces",
                "is introducing",
                "announces new product",
                "announces availability",
                "announces general availability",
                "general availability",
                "initiates production",
                "debut",
                "debuts",
                "rolls out",
                "rollout",
                "us rollout",
                "showcase",
                "to showcase",
                "showcases",
                "feature to split bills",
                "new feature",
                "new algorithm enhancements",
                "algorithm enhancements",
                "next-generation",
                "new oled",
                "new ai",
                "new gnss",
                "new 75-inch",
                "new energy vehicle",
                "new solution",
                "new platform",
                "new product service",
                "new service",
                "connected common data environment",
                "console manager",
                "bluetooth low energy inlays",
                "inlays and labels",
                "credit card-sized ecg device",
                "ev charging systems",
                "solar-powered ev charging",
                "battery packs",
                "electric marine propulsion",
                "robotaxi pilot",
                "autonomous micro-drone",
                "quadruped robot",
                "rugged ai systems",
                "edge computing platform",
                "satellite video",
                "out-of-band console manager",
                "adds automated ai agents",
                "automated ai agents",
                "business agent platform",
                "business agent",
                "chatgpt and codex",
                "microsoft discovery",
                "project solara",
                "web iq",
                "cobalt 200 chip",
                "surface rtx spark dev box"
        )) {
            return result(
                    CatalystType.PRODUCT_LAUNCH,
                    "Product launch/showcase/technology rollout language found"
            );
        }

        if (containsAny(
                text,
                "now available",
                "commercial availability",
                "product availability",
                "available for order",
                "available nationwide",
                "clinical availability",
                "upcoming clinical availability",
                "publicly available"
        )) {
            return result(
                    CatalystType.PRODUCT_AVAILABILITY,
                    "Product availability language found"
            );
        }

        if (containsAny(
                text,
                "patent notice",
                "receives patent",
                "new u.s. patent",
                "us patent",
                "u.s. patent",
                "patent granted",
                "patent allowance",
                "patent issued",
                "notice of allowance for a u.s. patent",
                "files patent application",
                "patent application",
                "full patent protection",
                "all ipr challenges"
        )) {
            return result(
                    CatalystType.PATENT_NOTICE,
                    "Patent language found"
            );
        }

        return null;
    }

    private CatalystResult matchPartnershipsLicensingAndDistribution(String text) {
        if (containsAny(
                text,
                "service level agreement",
                "service level agreements"
        )) {
            return result(
                    CatalystType.MARKET_COMMENTARY,
                    "Service-level-agreement commentary article found"
            );
        }

        if (containsAny(
                text,
                "license agreement",
                "licensing agreement",
                "distribution agreement",
                "content distribution agreement",
                "settlement and license agreement",
                "revised multi-territory licensing agreement",
                "strategic jv",
                "forms strategic jv",
                "joint venture",
                "joint venture with",
                "strategic cooperation agreement",
                "strategic alliance",
                "forms strategic alliance",
                "research collaboration",
                "strategic collaboration",
                "collaboration with",
                "collaborates with",
                "teams up with",
                "team up",
                "partners with",
                "partner to",
                "partnership",
                "mou with",
                "signs mou",
                "sign mou",
                "inks mou",
                "agreement with",
                "three-year agreement",
                "3-year agreement",
                "transmission connection agreement",
                "supply agreement",
                "construction agreement",
                "site readiness program",
                "strategic development initiative",
                "amended paragon license agreement",
                "expands rights beyond",
                "expands license",
                "distribution agreement with",
                "deploy",
                "deploys",
                "joins",
                "joins anthropic",
                "project glasswing",
                "expanding project glasswing",
                "obtains access to anthropic"
        )) {
            return result(
                    CatalystType.PARTNERSHIP,
                    "Partnership/licensing/distribution/collaboration language found"
            );
        }

        if (containsAny(
                text,
                "material supply agreement",
                "supply agreement with",
                "multi-year supply agreement"
        )) {
            return result(
                    CatalystType.MATERIAL_SUPPLY_AGREEMENT,
                    "Material supply agreement language found"
            );
        }

        if (containsAny(
                text,
                "sales and purchase agreement",
                "sales purchase agreement",
                "purchase minimum of",
                "tonnes per year",
                "sales agreement",
                "content distribution agreement",
                "commercial agreement"
        )) {
            return result(
                    CatalystType.SALES_AGREEMENT,
                    "Sales/commercial agreement language found"
            );
        }

        return null;
    }

    private CatalystResult matchAiInfrastructureAndCompute(String text) {
        if (containsAny(
                text,
                "in talks to purchase ai chips",
                "purchase ai chips",
                "ai chip purchase",
                "eyes ai chips",
                "orders ai chips",
                "orders gpus",
                "gpu procurement",
                "ai infrastructure procurement",
                "ai chips from",
                "iluvatar corex",
                "bytedance in talks",
                "tiktok parent bytedance eyes ai chips"
        )) {
            return result(
                    CatalystType.AI_INFRASTRUCTURE_PARTNERSHIP,
                    "AI chip procurement / AI compute expansion language found"
            );
        }

        if (containsAny(
                text,
                "nvidia",
                "ai infrastructure",
                "ai compute infrastructure",
                "hpc infrastructure",
                "ai factory",
                "ai cloud contract",
                "gpu financing facility",
                "gpu ai system",
                "gpu clusters",
                "ai supercomputer",
                "mass production of new ai supercomputer",
                "blackwell",
                "gb200",
                "gb300",
                "vera rubin",
                "nvl72",
                "nvidia omniverse",
                "digital twin",
                "ai-powered",
                "ai powered",
                "ai workloads",
                "ai-optimized memory",
                "ai optimized memory",
                "ai-optimized storage",
                "data center solutions",
                "data center capacity",
                "data center expansion",
                "modular data center",
                "data center growth",
                "data center in south australia",
                "planned 800mw data center",
                "800mw data center",
                "ai-focused edge computing",
                "edge computing facilities",
                "localized ai processing",
                "inference workloads",
                "distributed sub-50 mw",
                "behind-the-meter power generation",
                "windows pcs into ai-powered personal assistants",
                "ai-powered tool",
                "ai data cloud ecosystem",
                "snowflake's ai data cloud",
                "agentic ai-driven demand",
                "ai-driven demand",
                "robotaxi",
                "self-driving taxi",
                "autonomous driving",
                "general-purpose robots",
                "physical ai",
                "quantum computing",
                "quantum chip",
                "fault-tolerant quantum computing",
                "ai agents",
                "ai tools for finance",
                "role-specific plugins",
                "aion reasoning and planning models",
                "chips will run ai agents",
                "ai market intelligence",
                "ai-driven education",
                "smart conference solutions",
                "ai demand",
                "ai spending",
                "ai revenue",
                "ai revenue to surge"
        )) {
            return result(
                    CatalystType.AI_INFRASTRUCTURE_PARTNERSHIP,
                    "AI infrastructure/compute/robotics/quantum language found"
            );
        }

        return null;
    }

    private CatalystResult matchIpoIndexAndListings(String text) {
        if (containsAny(
                text,
                "set to join nasdaq 100",
                "join nasdaq 100",
                "inclusion in the nasdaq-100",
                "added to nasdaq-100",
                "added to index",
                "added to euronext tech leaders program and index",
                "index inclusion",
                "russell microcap index",
                "russell 2000",
                "russell 3000",
                "set to join s&p smallcap 600",
                "added to s&p",
                "s&p 500 inclusion",
                "included in list of eligible securities",
                "included in hong kong stock connect",
                "inclusion in hong kong stock connect",
                "stock connect program"
        )) {
            return result(
                    CatalystType.INDEX_ADDITION,
                    "Index addition / stock connect inclusion language found"
            );
        }

        if (containsAny(
                text,
                "ipo opens for trade",
                "ipo debut",
                "record-shattering ipo debut",
                "record-breaking ipo",
                "begins trading",
                "starts trading"
        )) {
            return result(
                    CatalystType.IPO_DEBUT,
                    "IPO debut language found"
            );
        }

        if (containsAny(
                text,
                "ipo priced at",
                "priced at $",
                "ipo priced",
                "prices ipo"
        )) {
            return result(
                    CatalystType.IPO_PRICING,
                    "IPO pricing language found"
            );
        }

        if (containsAny(
                text,
                "ipo indication",
                "indicated to open",
                "opening indication"
        )) {
            return result(
                    CatalystType.IPO_INDICATION,
                    "IPO indication language found"
            );
        }

        if (containsAny(
                text,
                "ipo demand",
                "strong ipo demand",
                "oversubscribed ipo"
        )) {
            return result(
                    CatalystType.IPO_DEMAND,
                    "IPO demand language found"
            );
        }

        if (containsAny(
                text,
                "ipo prospectus",
                "files ipo prospectus",
                "filed ipo prospectus",
                "f-1 filing",
                "s-1 filing"
        )) {
            return result(
                    CatalystType.IPO_PROSPECTUS,
                    "IPO prospectus language found"
            );
        }

        if (containsAny(
                text,
                "ipo allocation",
                "allocation of ipo",
                "allocated shares"
        )) {
            return result(
                    CatalystType.IPO_ALLOCATION,
                    "IPO allocation language found"
            );
        }

        if (containsAny(
                text,
                "foreign ipo filing",
                "hong kong ipo filing",
                "london ipo filing"
        )) {
            return result(
                    CatalystType.FOREIGN_IPO_FILING,
                    "Foreign IPO filing language found"
            );
        }

        if (containsAny(
                text,
                "space exploration technologies ipo",
                "spacex ipo",
                "targets $1.75 trillion valuation ahead of ipo",
                "targets $1.75 trillion valuation",
                "ipo roadshow",
                "ipo timing",
                "will ipo when it makes sense",
                "lead ipo",
                "anthropic said to tap",
                "record ipo",
                "ipo commentary",
                "ipo bulls",
                "eyeing the ipo",
                "stock jumped after ipo",
                "ipo sets the stage",
                "revives a 10-year-old promise",
                "large ipos"
        )) {
            return result(
                    CatalystType.IPO_COMMENTARY,
                    "IPO commentary article found"
            );
        }

        return null;
    }

    private CatalystResult matchAnalystAndInstitutional(String text) {
        if (containsAny(
                text,
                "upgrades",
                "upgraded",
                "upgrade from",
                "initiates buy",
                "initiates overweight",
                "initiates at buy",
                "initiates at overweight",
                "initiates at outperform",
                "raises to buy",
                "raises to outperform",
                "reinstates buy",
                "reinstates outperform"
        )) {
            return result(
                    CatalystType.ANALYST_UPGRADE,
                    "Analyst upgrade/buy initiation language found"
            );
        }

        if (containsAny(
                text,
                "downgrades",
                "downgraded",
                "downgrade from",
                "cuts to sell",
                "cuts to underperform",
                "lowers to sell",
                "underweight"
        )) {
            return result(
                    CatalystType.ANALYST_DOWNGRADE,
                    "Analyst downgrade/underweight language found"
            );
        }

        if (containsAny(
                text,
                "assumes",
                "assumes coverage",
                "initiates coverage",
                "starts coverage",
                "announces price target"
        )) {
            return result(
                    CatalystType.ANALYST_INITIATION,
                    "Analyst coverage initiation/assumption language found"
            );
        }

        if (containsAny(
                text,
                "reiterates buy",
                "reiterates overweight",
                "reiterates outperform",
                "reiterates sector perform",
                "maintains buy",
                "maintains neutral",
                "maintains outperform",
                "maintains overweight",
                "maintains hold",
                "maintains market perform",
                "maintains sector perform",
                "maintains equal-weight",
                "maintains underweight",
                "reaffirms",
                "s&p reaffirms",
                "reiterates hold"
        )) {
            return result(
                    CatalystType.ANALYST_REITERATION,
                    "Analyst/rating reiteration language found"
            );
        }

        if (containsAny(
                text,
                "raises price target",
                "raised price target",
                "price target raised",
                "boosts price target",
                "lifts price target"
        )) {
            return result(
                    CatalystType.PRICE_TARGET_RAISE,
                    "Price target raise language found"
            );
        }

        if (containsAny(
                text,
                "cuts price target",
                "lowered price target",
                "lowers price target",
                "price target cut",
                "reduces price target",
                "slashes price target"
        )) {
            return result(
                    CatalystType.PRICE_TARGET_CUT,
                    "Price target cut language found"
            );
        }

        if (containsAny(
                text,
                "analyst says",
                "says analyst",
                "analyst commentary",
                "most accurate analysts",
                "analysts give their take",
                "forecast changes",
                "analysts revise their forecasts",
                "top wall street forecasters"
        )) {
            return result(
                    CatalystType.ANALYST_COMMENTARY,
                    "Analyst commentary language found"
            );
        }

        return null;
    }

    private CatalystResult matchSecInsiderOptionsAndTradingMentions(String text) {
        if (containsAny(
                text,
                "form 8-k",
                "10-k",
                "10-q",
                "13d filing",
                "13g filing",
                "sec filing",
                "files form",
                "filed form",
                "hsr review",
                "clears hsr review"
        )) {
            return result(
                    CatalystType.SEC_FILING,
                    "SEC filing / regulatory filing language found"
            );
        }

        if (containsAny(
                text,
                "sec market structure",
                "market structure rule",
                "sec rule"
        )) {
            return result(
                    CatalystType.SEC_MARKET_STRUCTURE,
                    "SEC market structure language found"
            );
        }

        if (containsAny(
                text,
                "insider buying",
                "insider buys",
                "ceo buys",
                "director buys",
                "insider purchase",
                "open market share purchase",
                "open market purchases",
                "increase ownership position",
                "increases ownership position"
        )) {
            return result(
                    CatalystType.INSIDER_BUYING,
                    "Insider buying language found"
            );
        }

        if (containsAny(
                text,
                "insider selling",
                "insider sells",
                "ceo sells",
                "director sells",
                "insider sale"
        )) {
            return result(
                    CatalystType.INSIDER_SELLING,
                    "Insider selling language found"
            );
        }

        if (containsAny(
                text,
                "block trade",
                "secondary block",
                "large block sale"
        )) {
            return result(
                    CatalystType.BLOCK_TRADE,
                    "Block trade language found"
            );
        }

        if (containsAny(
                text,
                "congressional trade",
                "pelosi trade",
                "senator buys",
                "senator sells",
                "lawmakers trade",
                "officials reported financial interests",
                "financial interests in elon musk"
        )) {
            return result(
                    CatalystType.CONGRESSIONAL_TRADE,
                    "Congressional/public official trade/interest language found"
            );
        }

        if (containsAny(
                text,
                "option alert",
                "options alert",
                "unusual options",
                "options activity",
                "options flow",
                "call sweep",
                "put sweep",
                "calls sweep",
                "puts sweep",
                "near the ask",
                "call volume",
                "put volume",
                "adv of",
                "contracts, up"
        )) {
            return result(
                    CatalystType.OPTIONS_FLOW,
                    "Options flow / exchange volume language found"
            );
        }

        if (containsAny(
                text,
                "whale activity",
                "whale trade",
                "large options trade"
        )) {
            return result(
                    CatalystType.WHALE_ACTIVITY,
                    "Whale activity language found"
            );
        }

        if (containsAny(
                text,
                "hearing popular newsletter",
                "hearing popular retail traders newsletter",
                "hearing investor business daily swingtrader",
                "hearing navalier buys stock",
                "names stock as a new long",
                "mentions stock as a buy",
                "swingtrader buys stock",
                "zacks-small cap research gives stock",
                "price valuation"
        )) {
            return result(
                    CatalystType.STOCK_PICK_ARTICLE,
                    "Newsletter/stock-pick/trader mention language found"
            );
        }

        return null;
    }

    private CatalystResult matchManagementGovernanceAndCorporateUpdates(String text) {
        if (containsAny(
                text,
                "appoints ceo",
                "appoints mr.",
                "appoints mr ",
                "appoints thomas",
                "appoints anton",
                "appoints ian",
                "appoints norman",
                "appoints matthew",
                "appoints paul",
                "appoints james",
                "appoints dave",
                "appoints rob",
                "appoints sri",
                "appoints julie",
                "names ceo",
                "new ceo",
                "appointed as ceo",
                "appoints cfo",
                "names cfo",
                " as cfo",
                " as ceo",
                " as coo",
                "appointed as cfo",
                "appoints co-ceo",
                "appoints coo",
                "names coo",
                "chief operating officer",
                "president, ceo",
                "president and ceo",
                "interim ceo",
                "interom ceo",
                "interim cfo",
                "interim president",
                "executive hire",
                "named successor",
                "new chairman",
                "chairman and ceo",
                "elected to ceo and chairman",
                "promotes john",
                "promotes",
                "to ceo position",
                "to cfo effective",
                "as evp and cfo"
        )) {
            return result(
                    CatalystType.EXECUTIVE_HIRE,
                    "Executive appointment/hire language found"
            );
        }

        if (containsAny(
                text,
                "ceo resigns",
                "cfo resigns",
                "resigning",
                "executive departure",
                "steps down",
                "step down",
                "to retire",
                "retire effective",
                "following retirement",
                "departing ceo",
                "chair plans exit",
                "director resigns",
                "cfo and director resigns",
                "ceo exit looms",
                "succeeding",
                "who is named executive chair"
        )) {
            return result(
                    CatalystType.EXECUTIVE_DEPARTURE,
                    "Executive departure/retirement/succession language found"
            );
        }

        if (containsAny(
                text,
                "layoffs",
                "job cuts",
                "cuts workforce",
                "workforce reduction",
                "reducing its full-time workforce",
                "exit 22 countries",
                "people division",
                "cuts 23%",
                "restructuring plan includes layoffs"
        )) {
            return result(
                    CatalystType.LAYOFFS,
                    "Layoffs/workforce reduction language found"
            );
        }

        if (containsAny(
                text,
                "corporate restructuring",
                "restructuring plan",
                "reorganizes business",
                "business restructuring",
                "reverse stock split",
                "reverse share split",
                "share consolidation",
                "10-to-1 share consolidation",
                "1-for-5 reverse stock split",
                "1-for-20 reverse share split",
                "1-for-30 reverse share split"
        )) {
            return result(
                    CatalystType.CORPORATE_RESTRUCTURING,
                    "Corporate restructuring/reverse split language found"
            );
        }

        if (containsAny(
                text,
                "governance change",
                "board change",
                "board chairman",
                "new board chairman",
                "new chairman",
                "chairman resigns",
                "chairman of the board",
                "board approves"
        )) {
            return result(
                    CatalystType.GOVERNANCE_CHANGE,
                    "Governance/board change language found"
            );
        }

        if (containsAny(
                text,
                "client assets",
                "assets under management",
                "aum update",
                "reports may nav",
                "net assets were"
        )) {
            return result(
                    CatalystType.CLIENT_ASSETS_UPDATE,
                    "Client assets/NAV update language found"
            );
        }

        if (containsAny(
                text,
                "donation response",
                "responds to donation",
                "charitable donation"
        )) {
            return result(
                    CatalystType.DONATION_RESPONSE,
                    "Donation response language found"
            );
        }

        if (containsAny(
                text,
                "provides more information regarding its business operations",
                "business operations",
                "customer track record",
                "balance sheet update",
                "provides balance sheet update",
                "no known business reason",
                "corporate update",
                "business update",
                "operational update",
                "provides an update",
                "provides update",
                "updates manufacturing protocols",
                "officially renames",
                "establishes u.s. subsidiary",
                "foster global expansion",
                "long-term growth"
        )) {
            return result(
                    CatalystType.BUSINESS_UPDATE,
                    "Business/corporate/operational update language found"
            );
        }

        if (containsAny(
                text,
                "no material news",
                "no company-specific news",
                "no apparent news"
        )) {
            return result(
                    CatalystType.NO_MATERIAL_NEWS,
                    "No material news language found"
            );
        }

        return null;
    }

    private CatalystResult matchLegalRegulatoryAndRisk(String text) {
        if (containsAny(
                text,
                "china market regulator summons",
                "market regulator summons",
                "regulator summons",
                "summoned by regulator",
                "summons walmart",
                "food safety issues",
                "food safety investigation",
                "misleading data",
                "misleading safety data",
                "presented misleading",
                "presented misleading full self-driving safety data",
                "regulatory scrutiny",
                "regulatory concerns",
                "european regulators",
                "investigation",
                "probe into",
                "under investigation",
                "sec investigation",
                "doj investigation",
                "antitrust investigation",
                "antitrust probe",
                "ftc investigation",
                "google antitrust ruling delayed",
                "antitrust ruling delayed"
        )) {
            return result(
                    CatalystType.INVESTIGATION,
                    "Investigation/regulator summons/misleading safety data language found"
            );
        }

        if (containsAny(
                text,
                "lawsuit",
                "sued by",
                "class action",
                "securities lawsuit",
                "shareholder lawsuit",
                "patent litigation",
                "resolves all patent litigation",
                "amended complaint",
                "will defend itself"
        )) {
            return result(
                    CatalystType.LAWSUIT,
                    "Lawsuit/litigation language found"
            );
        }

        if (containsAny(
                text,
                "legal settlement",
                "settles lawsuit",
                "settlement agreement",
                "agrees to settle",
                "final judgement",
                "final judgment",
                "pre-judgement interest",
                "pre-judgment interest",
                "supreme court rules in favor",
                "rules in favor",
                "supreme court says"
        )) {
            return result(
                    CatalystType.LEGAL_SETTLEMENT,
                    "Legal settlement/judgment/ruling language found"
            );
        }

        if (containsAny(
                text,
                "recall",
                "product recall",
                "recalls product",
                "voluntary recall"
        )) {
            return result(
                    CatalystType.RECALL,
                    "Recall language found"
            );
        }

        if (containsAny(
                text,
                "cybersecurity incident",
                "data breach",
                "ransomware",
                "hacked",
                "cyber attack",
                "cyberattack"
        )) {
            return result(
                    CatalystType.CYBERSECURITY_INCIDENT,
                    "Cybersecurity incident language found"
            );
        }

        if (containsAny(
                text,
                "facility damage",
                "plant fire",
                "factory fire",
                "explosion at facility",
                "blue origin explosion",
                "explosion"
        )) {
            return result(
                    CatalystType.FACILITY_DAMAGE,
                    "Facility damage/explosion language found"
            );
        }

        if (containsAny(
                text,
                "hazmat incident",
                "chemical spill",
                "toxic release",
                "hazardous materials"
        )) {
            return result(
                    CatalystType.HAZMAT_INCIDENT,
                    "Hazmat incident language found"
            );
        }

        if (containsAny(
                text,
                "negative headwind",
                "headwinds",
                "faces pressure",
                "demand weakness",
                "supplier strike",
                "strike threatens",
                "plant shutdown",
                "shutdown within weeks",
                "stress point",
                "coverage of glp-1 obesity drugs",
                "drops coverage"
        )) {
            return result(
                    CatalystType.NEGATIVE_HEADWIND,
                    "Negative headwind/strike/shutdown risk language found"
            );
        }

        if (containsAny(
                text,
                "credit stress",
                "debt concerns",
                "liquidity concerns",
                "going concern",
                "junk bond sale",
                "loan exposure",
                "counterparty exposure",
                "sell $1.85 billion debt"
        )) {
            return result(
                    CatalystType.CREDIT_STRESS,
                    "Credit stress/debt concern language found"
            );
        }

        if (containsAny(
                text,
                "financial exposure",
                "exposure to",
                "loan exposure",
                "counterparty exposure",
                "financial commitments"
        )) {
            return result(
                    CatalystType.FINANCIAL_EXPOSURE,
                    "Financial exposure language found"
            );
        }

        if (containsAny(
                text,
                "ai cost concern",
                "ai spending concern",
                "ai capex concern",
                "massive threat to ai optimism",
                "nvidia's china woes deepen",
                "china woes deepen",
                "ai companies seek billions",
                "markets are in 'greed' mode"
        )) {
            return result(
                    CatalystType.AI_COST_CONCERN,
                    "AI cost/market/china concern language found"
            );
        }

        if (containsAny(
                text,
                "ai project concern",
                "ai project delayed",
                "ai demand concern",
                "pauses hiring for specialists to train grok"
        )) {
            return result(
                    CatalystType.AI_PROJECT_CONCERN,
                    "AI project/demand concern language found"
            );
        }

        if (containsAny(
                text,
                "regulatory crackdown",
                "government crackdown",
                "data center moratorium",
                "lawmakers plan to approve one-year data center moratorium"
        )) {
            return result(
                    CatalystType.REGULATORY_CRACKDOWN,
                    "Regulatory crackdown / moratorium language found"
            );
        }

        if (containsAny(
                text,
                "chip export restriction",
                "chip export restrictions",
                "export controls",
                "export restriction",
                "export ban",
                "advanced chip restrictions"
        )) {
            return result(
                    CatalystType.CHIP_EXPORT_RESTRICTION,
                    "Chip/export restriction language found"
            );
        }

        if (containsAny(
                text,
                "supply constraint",
                "supply constraints",
                "product shortage",
                "supply shortage",
                "component shortage"
        )) {
            return result(
                    CatalystType.PRODUCT_SUPPLY_CONSTRAINT,
                    "Product supply constraint language found"
            );
        }

        if (containsAny(
                text,
                "national security order",
                "national security review",
                "national security concerns"
        )) {
            return result(
                    CatalystType.NATIONAL_SECURITY_ORDER,
                    "National security order/review language found"
            );
        }

        return null;
    }

    private CatalystResult matchMacroPoliticsCryptoAndCommodities(String text) {
        if (containsAny(
                text,
                "tariff",
                "tariffs",
                "trade war",
                "trade dispute",
                "import tax",
                "digital services tax",
                "retaliatory tariffs",
                "threatens tariffs",
                "white house",
                "trump",
                "biden",
                "macron",
                "senate",
                "congress",
                "lawmakers issue statement",
                "ending 12% excise tax",
                "forced labor",
                "unfair trading practice",
                "trade agreement",
                "administration proposal"
        )) {
            return result(
                    CatalystType.POLITICAL_NEWS,
                    "Political/tariff/trade-policy related headline found"
            );
        }

        if (containsAny(
                text,
                "economic data",
                "jobs report",
                "jolts job openings",
                "adp nonfarm employment",
                "nonfarm employment change",
                "cpi",
                "ppi",
                "retail sales",
                "unemployment claims",
                "gdp",
                "global services pmi",
                "services pmi",
                "composite pmi",
                "manufacturing pmi",
                "ism manufacturing",
                "ism non-manufacturing",
                "factory orders",
                "durables excluding defense",
                "construction spending",
                "nonfarm payrolls",
                "consumer confidence"
        )) {
            return result(
                    CatalystType.ECONOMIC_DATA,
                    "Economic data language found"
            );
        }

        if (containsAny(
                text,
                "geopolitical",
                "iran",
                "israel",
                "strait of hormuz",
                "gulf",
                "marine traffic",
                "uranium",
                "u.s. draft pact",
                "war",
                "missile strike",
                "missile interceptor",
                "ceasefire",
                "ukraine talks",
                "putin",
                "russia",
                "china",
                "military sphere",
                "military conflict",
                "sanctions",
                "cargo vessel",
                "explosion hits cargo vessel",
                "global vaccine alliance",
                "re-engage with global vaccine alliance",
                "secretary of state",
                "central command"
        )) {
            return result(
                    CatalystType.GEOPOLITICAL,
                    "Geopolitical headline found"
            );
        }

        if (containsAny(
                text,
                "bitcoin",
                "btc production",
                "ethereum",
                "xrp",
                "dogecoin",
                "crypto",
                "polymarket",
                "kalshi",
                "ripple",
                "prediction markets",
                "stablecoin",
                "digital asset",
                "digital assets",
                "tokenizing precious metals",
                "tokenization",
                "hyperliquid",
                "solana",
                "usdai",
                "synthetic dollar"
        )) {
            return result(
                    CatalystType.CRYPTO_NEWS,
                    "Crypto/digital asset related article found"
            );
        }

        if (containsAny(
                text,
                "oil prices",
                "natural gas",
                "crude oil",
                "gold prices",
                "commodity",
                "energy prices",
                "opec",
                "venezuela wants oil firms",
                "oil firms",
                "supply their own power",
                "oil supertanker",
                "kharg island",
                "weekly distillates stocks",
                "gasoline inventories",
                "gas prices jumped"
        )) {
            return result(
                    CatalystType.COMMODITY_ENERGY,
                    "Commodity/energy related headline found"
            );
        }

        if (containsAny(
                text,
                "battery storage",
                "energy storage data",
                "storage deployment",
                "energy storage",
                "power infrastructure",
                "carbon-free energy",
                "hydrogen",
                "fusion",
                "smart grids"
        )) {
            return result(
                    CatalystType.ENERGY_STORAGE_DATA,
                    "Energy storage / power infrastructure language found"
            );
        }

        if (containsAny(
                text,
                "stock market",
                "s&p 500",
                "dow jones",
                "fed",
                "rate cut",
                "inflation",
                "leading lagging sectors",
                "sectors moving",
                "stocks moving",
                "final trades",
                "halftime report",
                "market-moving news",
                "liquidity in the system",
                "consumer behavior",
                "markets are"
        )) {
            return result(
                    CatalystType.BROAD_MARKET,
                    "Broad market/generic market language found"
            );
        }

        return null;
    }

    private CatalystResult matchTechnicalInformationalAndMediaArticles(String text) {
        if (containsAny(
                text,
                "rally momentum",
                "shares rally",
                "stock rallies",
                "shares surge",
                "stock surges"
        )) {
            return result(
                    CatalystType.RALLY_MOMENTUM,
                    "Rally momentum language found"
            );
        }

        if (containsAny(text, "golden cross")) {
            return result(
                    CatalystType.GOLDEN_CROSS,
                    "Golden cross technical article found"
            );
        }

        if (containsAny(
                text,
                "industry comparison",
                "compared with peers",
                "versus peers"
        )) {
            return result(
                    CatalystType.INDUSTRY_COMPARISON,
                    "Industry comparison article found"
            );
        }

        if (containsAny(
                text,
                "news roundup",
                "market roundup",
                "morning roundup"
        )) {
            return result(
                    CatalystType.NEWS_ROUNDUP,
                    "News roundup article found"
            );
        }

        if (containsAny(
                text,
                "reported earlier",
                "reported friday",
                "reported saturday",
                "reported sunday",
                "reported june",
                "reported may",
                "reuters exclusive",
                "bloomberg news",
                "business insider",
                "financial times",
                "nikkei asia",
                "politico",
                "cnbc",
                "handelsblatt",
                "sky news reporter",
                "posts on x"
        )) {
            return result(
                    CatalystType.REPORTED_EARLIER,
                    "Reported-earlier / media-source article found"
            );
        }

        if (containsAny(
                text,
                "rumor denial",
                "denies rumor",
                "denied rumors"
        )) {
            return result(
                    CatalystType.RUMOR_DENIAL,
                    "Rumor denial language found"
            );
        }

        if (containsAny(
                text,
                "historical performance",
                "past performance",
                "history suggests",
                "invested in",
                "would be worth",
                "how much $1000 invested",
                "how much $100 invested"
        )) {
            return result(
                    CatalystType.HISTORICAL_PERFORMANCE,
                    "Historical performance article found"
            );
        }

        if (containsAny(
                text,
                "why is",
                "what's going on",
                "whats going on",
                "what investors need to know",
                "is stock a buy now",
                "should you buy this dip",
                "stock in the spotlight",
                "here's why",
                "here is why",
                "why tower semiconductor shares are trading higher",
                "here are 20 stocks moving premarket",
                "stocks moving premarket",
                "shares are surging",
                "what's driving the action"
        )) {
            return result(
                    CatalystType.WHY_MOVING,
                    "Why-moving/premarket mover/explainer article found"
            );
        }

        if (containsAny(
                text,
                "etf",
                "fund flow",
                "fund flows",
                "fund holdings",
                "etf commentary",
                "spdr gold shares",
                "actively managed ucits funds"
        )) {
            return result(
                    CatalystType.FUND_ETF_COMMENTARY,
                    "Fund/ETF commentary language found"
            );
        }

        if (containsAny(
                text,
                "live:",
                "watch microsoft build",
                "mark cuban pushes",
                "warns failed policies",
                "service level agreements",
                "market commentary",
                "market strategist",
                "market outlook",
                "live on cnbc",
                "jim cramer",
                "cramer won't recommend",
                "ceo says",
                "cfo says",
                "executive says"
        )) {
            return result(
                    CatalystType.MARKET_COMMENTARY,
                    "Market/media/executive commentary language found"
            );
        }

        if (containsAny(
                text,
                "investor commentary",
                "investor says",
                "portfolio manager says",
                "activist toms capital",
                "activist campaign",
                "anthony pompliano wants"
        )) {
            return result(
                    CatalystType.INVESTOR_COMMENTARY,
                    "Investor/activist/commentator language found"
            );
        }

        if (containsAny(
                text,
                "private company valuation",
                "valued at",
                "private valuation",
                "$1.8trn flotation",
                "$1.75 trillion valuation"
        )) {
            return result(
                    CatalystType.PRIVATE_COMPANY_VALUATION,
                    "Private company valuation language found"
            );
        }

        if (containsAny(
                text,
                "tokenized asset",
                "tokenized stock",
                "tokenized securities"
        )) {
            return result(
                    CatalystType.TOKENIZED_ASSET_PRODUCT,
                    "Tokenized asset product language found"
            );
        }

        if (containsAny(
                text,
                "stocks to buy",
                "stocks to sell",
                "stock to buy",
                "stock to sell",
                "stock of the day",
                "top stocks",
                "watch this stock",
                "watching",
                "stock pick",
                "could follow",
                "could rise",
                "may gain",
                "may explode",
                "may reverse",
                "key metric hints",
                "hints at further gains",
                "has become volatile",
                "best stocks",
                "what you should know",
                "what you need to know",
                "reminds shareholders",
                "urge shareholders",
                "vote against proposal"
        )) {
            return result(
                    CatalystType.STOCK_PICK_ARTICLE,
                    "Stock-pick/watchlist/shareholder informational article found"
            );
        }

        return null;
    }

    private CatalystResult matchPositiveBusinessMomentum(String text) {
        if (containsAny(
                text,
                "secures central bank approval",
                "central bank approval",
                "banking branch approval",
                "new banking branch",
                "expanding eu client services",
                "receives approval",
                "wins approval",
                "regulatory approval",
                "money transmitter license",
                "42nd state",
                "license approval",
                "operating approval",
                "market expansion",
                "record revenue",
                "record earnings",
                "record growth",
                "accelerating growth",
                "higher revenue",
                "revenue growth",
                "market share gains",
                "expanding margins",
                "bullish outlook",
                "strong outlook",
                "improving outlook",
                "rapid adoption",
                "business momentum",
                "gain traction",
                "gains traction",
                "surpasses",
                "growth through diversification",
                "international expansion",
                "emerging technology initiatives",
                "commercialize",
                "clearing way for u.s. commercial launch",
                "fully places",
                "reinsurance program",
                "leasing of",
                "new tenant leasing",
                "space leased",
                "process catalyst at new rise renewables",
                "delivery and receipt of process catalyst",
                "routes outperforming",
                "demand remains strong",
                "consistent with earlier-year trends",
                "cut 40%+ of increased fuel costs",
                "recapture of 40%+"
        )) {
            return result(
                    CatalystType.POSITIVE_BUSINESS_MOMENTUM,
                    "Positive approval/business momentum/market expansion language found"
            );
        }

        if (containsAny(
                text,
                "business update",
                "corporate update",
                "operational update",
                "provides more information",
                "customer track record",
                "balance sheet update"
        )) {
            return result(
                    CatalystType.BUSINESS_UPDATE,
                    "Business update language found"
            );
        }

        return null;
    }

    private boolean isLawFirmDealInvestigationOrShareholderAlert(String text) {
        return containsAny(
                text,
                "shareholder alert",
                "shareholder alert:",
                "m&a class action firm",
                "m & a class action firm",
                "class action firm",
                "law firm",
                "investor notice",
                "deadline alert",
                "stock alert",
                "class action",
                "lawsuit",
                "investigates fairness",
                "investigating fairness",
                "announces an investigation",
                "announces investigation",
                "investigation of",
                "possible securities law violations",
                "obtaining a fair price",
                "shareholder rights"
        );
    }


    private boolean isLawFirmOrInvestigationText(String text) {
        return containsAny(
                text,
                "shareholder alert",
                "investor alert",
                "deadline alert",
                "m&a class action firm",
                "class action firm",
                "law firm",
                "law offices of",
                "rosen law",
                "pomerantz",
                "glancy prongay",
                "holzer & holzer",
                "claims filer",
                "claimsfiler",
                "investigates fairness",
                "announces an investigation",
                "class action lawsuit",
                "securities class action",
                "lead plaintiff"
        );
    }

    private boolean isOfferingOrDilutionText(String text) {
        return containsAny(
                text,
                "announces pricing of",
                "pricing of public offering",
                "prices public offering",
                "public offering",
                "registered direct",
                "private placement",
                "best-efforts offering",
                "overnight offering",
                "bought deal financing",
                "bought deal offering",
                "rights offering",
                "subscription rights offering",
                "common stock offering",
                "preferred shares",
                "ordinary shares",
                "pre-funded warrants",
                "warrants",
                "at-the-market",
                "atm program",
                "shelf offering",
                "shelf registration"
        );
    }

    private boolean isDebtSecuritiesFinancingText(String text) {
        return containsAny(
                text,
                "debt securities",
                "outstanding debt securities",
                "offers to purchase for cash",
                "pricing terms of offers to purchase",
                "cash tender offer",
                "tender offer for notes",
                "tender offers for notes",
                "exchange offer for notes",
                "senior notes",
                "notes due",
                "convertible notes",
                "debt offering",
                "offering of notes",
                "offering of senior notes",
                "repurchase of notes",
                "redemption of notes",
                "liability management transaction"
        );
    }

    private boolean isCorporateAdministrativeHealthcareFalsePositive(String text) {
        return containsAny(
                text,
                "annual general meeting",
                "annual meeting of shareholders",
                "voting results",
                "board of directors",
                "evolution of its board",
                "results of annual general meeting"
        ) && !containsAny(
                text,
                "fda",
                "clinical trial",
                "phase 1",
                "phase 2",
                "phase 3",
                "patient",
                "patients",
                "endpoint",
                "topline",
                "top-line"
        );
    }

    private boolean isNonHealthcareIndustrialOrConsumerProductText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        boolean industrialOrConsumerProduct = containsAny(
                text,
                "inductor",
                "inductors",
                "automotive and commercial",
                "compact sizes",
                "strap pro",
                "body-worn movement",
                "hyrox",
                "hybrid training",
                "distribution",
                "preferred stock",
                "mortgage rates",
                "repurchase and reverse repurchase",
                "ordinary shares and warrants"
        );

        boolean trueClinicalOrRegulatory = containsAny(
                text,
                "fda",
                "clinical trial",
                "phase 1",
                "phase 2",
                "phase 3",
                "patient",
                "patients",
                "endpoint",
                "topline",
                "top-line",
                "oncology",
                "cancer",
                "tumor",
                "therapy",
                "therapeutic",
                "treatment",
                "preclinical"
        );

        return industrialOrConsumerProduct && !trueClinicalOrRegulatory;
    }

    private boolean hasHealthcareContext(String text) {
        return containsAny(
                text,
                " fda ",
                "fda ",
                "clinical",
                "clinic",
                "trial",
                "phase 1",
                "phase 2",
                "phase 3",
                "patient",
                "patients",
                "drug",
                "therapy",
                "therapeutic",
                "treatment",
                "medicine",
                "medical",
                "biotech",
                "biopharma",
                "pharma",
                "pharmaceutical",
                "oncology",
                "cancer",
                "tumor",
                "antibody",
                "vaccine",
                "diagnostic",
                "510(k)",
                "nda",
                "bla",
                "ind clearance",
                "orphan drug",
                "pdufa",
                "endpoint",
                "survival",
                "disease",
                "adverse event",
                "safety data",
                "efficacy",
                "preclinical",
                "in vivo",
                "in vitro"
        );
    }


    private boolean isWeakCommentary(String text) {
        return containsAny(
                text,
                "key metric hints",
                "hints at further gains",
                "has become volatile",
                "could rise",
                "could follow",
                "may gain",
                "may reverse",
                "may explode",
                "stock jumped after ipo",
                "here's why",
                "here is why",
                "watch this stock",
                "stock to buy",
                "stock to sell",
                "stocks to buy",
                "stocks to sell",
                "is stock a buy now",
                "should you buy",
                "what investors need to know",
                "investors are watching",
                "shares to watch",
                "in focus",
                "stock in focus",
                "spotlight on",
                "what you need to know",
                "what you should know",
                "top analyst predicts",
                "analyst predicts",
                "analyst says",
                "says analyst"
        );
    }



    private CatalystResult matchVerifiedLiveCandidateBeforeBroadNoise(
            String headline,
            String text
    ) {
        String h = headline == null ? "" : headline;
        String full = text == null ? h : text;

        if (containsAny(
                h,
                "reverse merger exploration continues",
                "reverse merger continues",
                "reverse merger exploration"
        )) {
            return result(
                    CatalystType.STRATEGIC_REVIEW,
                    "Reverse-merger exploration headline preserved as actionable strategic-review catalyst before admin/dividend noise"
            );
        }

        if (containsAny(
                h,
                "record consolidated net income profit",
                "records consolidated net income profit",
                "record net income profit",
                "record profit"
        )) {
            return result(
                    CatalystType.EARNINGS_GROWTH,
                    "Record profit / net-income headline preserved before routine distribution or administrative scoring"
            );
        }

        if (containsAny(
                h,
                "expands manufacturing capacity",
                "expands production capacity",
                "manufacturing capacity in india",
                "new manufacturing capacity"
        ) && !containsAny(
                h,
                "class action",
                "lawsuit",
                "investigation",
                "private placement",
                "offering"
        )) {
            return result(
                    CatalystType.FACILITY_EXPANSION,
                    "Manufacturing-capacity expansion headline preserved as facility expansion before strategic-investment/noise scoring"
            );
        }

        if (containsAny(
                h,
                "launches the media machine",
                "agentic media operating system",
                "full lifecycle agentic media operating system"
        )) {
            return result(
                    CatalystType.PRODUCT_LAUNCH,
                    "New agentic software/product launch headline preserved before generic partnership scoring"
            );
        }

        if (containsAny(
                h,
                "hires former",
                "lead advanced packaging push",
                "foundry revival"
        ) && containsAny(full, "intel")) {
            return result(
                    CatalystType.POSITIVE_BUSINESS_MOMENTUM,
                    "Intel foundry/advanced-packaging leadership momentum headline preserved before generic executive/admin scoring"
            );
        }

        if (containsAny(
                h,
                "acquisition of shares"
        ) && !containsAny(
                h,
                "private placement",
                "offering",
                "class action",
                "lawsuit"
        )) {
            return result(
                    CatalystType.INSIDER_BUYING,
                    "Share acquisition headline preserved as insider/strategic buying instead of dilution"
            );
        }

        if (containsAny(
                h,
                "spacex's $60 billion cursor acquisition",
                "spacex cursor acquisition",
                "cursor acquisition"
        ) && containsAny(full, "spacex")) {
            return result(
                    CatalystType.MERGER_ACQUISITION,
                    "SpaceX Cursor acquisition headline preserved as M&A catalyst before valuation/commentary scoring"
            );
        }

        if (containsAny(
                h,
                "stock jumps nearly",
                "stock surges over",
                "stock jumped over",
                "stock jump over",
                "stock slips",
                "after hours",
                "after-hours",
                "why is it moving",
                "what is going on",
                "what you should know"
        )) {
            return result(
                    CatalystType.WHY_MOVING,
                    "After-hours why-moving recap blocked as non-actionable unless another primary catalyst is available"
            );
        }

        return null;
    }

    private CatalystResult result(
            CatalystType type,
            String reason
    ) {
        return new CatalystResult(
                type,
                type.weight(),
                reason
        );
    }

    private boolean containsAny(
            String text,
            String... phrases
    ) {
        if (text == null || text.isBlank()) {
            return false;
        }

        for (String phrase : phrases) {
            if (phrase != null && text.contains(phrase)) {
                return true;
            }
        }

        return false;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value
                .toLowerCase(Locale.ROOT)
                .replace('–', '-')
                .replace('—', '-')
                .replace('-', '-')
                .replace('’', '\'')
                .replace('‘', '\'')
                .replace('“', '"')
                .replace('”', '"')
                .replace("&#39;", "'")
                .replace("&amp;", "and")
                .replace("&nbsp;", " ")
                .replace("  ", " ");
    }
}