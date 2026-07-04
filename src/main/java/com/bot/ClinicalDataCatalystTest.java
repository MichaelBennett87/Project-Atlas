package com.bot;

import com.bot.model.CatalystQualityDecision;
import com.bot.model.CatalystResult;
import com.bot.model.CatalystType;
import com.bot.model.NewsEvent;
import com.bot.model.TradeDirection;
import com.bot.sentiment.SentimentScore;
import com.bot.strategy.CatalystClassifier;
import com.bot.strategy.CatalystQualityFilter;
import com.bot.strategy.TradeDirectionService;

public class ClinicalDataCatalystTest {

    public static void main(String[] args) {
        runClinicalDataTest(
                "AAPG STRONG CLINICAL DATA TEST",
                "AAPG",
                "Ascentage Pharma's Olverembatinib And Lisaftoclax Demonstrate Strong Clinical Data Across Chronic Myeloid Leukemia, Ph+ ALL And CLL/SLL In Seventeen Updates At EHA2026 Congress",
                "The company reported strong clinical data across multiple leukemia studies.",
                0.93,
                0.02,
                0.05
        );

        runClinicalDataTest(
                "ZYME PHASE 1 DATA TEST",
                "ZYME",
                "Zymeworks Presents Phase 1 Data For ZW191 ADC Showing 78.6% cORR In FRα-Positive And 47.4% In FRα-Negative Platinum-Resistant Ovarian Cancer Patients At ESMO 2026",
                "The data showed objective response rate and clinical activity in ovarian cancer patients.",
                0.94,
                0.02,
                0.04
        );

        runClinicalDataTest(
                "MIRM PIVOTAL PHASE 2 RESULTS TEST",
                "MIRM",
                "Mirum Pharmaceuticals And Incyte Report Pivotal Phase 2 PROGRESS Study Results For Zilurgisertib Showing 99.9% Reduction In New HO Lesion Volume",
                "The pivotal phase 2 data showed significant reduction in lesion volume at week 24.",
                0.60,
                0.37,
                0.03
        );

        runClinicalDataTest(
                "CRNX PHASE 2 DATA TEST",
                "CRNX",
                "Crinetics Presents Phase 2 Data For Atumelnant In CAH Showing 67% Mean Reduction In Androstenedione Levels With 88% Of Participants Achieving Physiologic Glucocorticoid Dose",
                "The phase 2 data showed mean reduction and clinical benefit at week 12.",
                0.68,
                0.29,
                0.03
        );

        runClinicalDataTest(
                "CLDX PHASE 2 DATA TEST",
                "CLDX",
                "Celldex Presents Phase 2 Data Showing Up To 64% Of Barzolvolimab-Treated CSU Patients Remained Angioedema-Free Seven Months After Last Dose",
                "The phase 2 data showed durable benefit after the last dose.",
                0.95,
                0.02,
                0.03
        );
    }

    private static void runClinicalDataTest(
            String testName,
            String ticker,
            String headline,
            String content,
            double positive,
            double negative,
            double neutral
    ) {
        CatalystClassifier classifier =
                new CatalystClassifier();

        CatalystQualityFilter qualityFilter =
                new CatalystQualityFilter();

        TradeDirectionService directionService =
                new TradeDirectionService();

        NewsEvent news =
                new NewsEvent(
                        "clinical-data-test-" + ticker,
                        ticker,
                        headline,
                        content,
                        System.currentTimeMillis()
                );

        SentimentScore sentiment =
                new SentimentScore(
                        positive,
                        negative,
                        neutral
                );

        CatalystResult catalyst =
                classifier.classify(news);

        CatalystQualityDecision qualityDecision =
                qualityFilter.evaluate(
                        catalyst,
                        sentiment
                );

        TradeDirection direction =
                directionService.resolve(
                        catalyst,
                        sentiment
                );

        boolean expectedCatalyst =
                catalyst.type == CatalystType.DRUG_DATA_POSITIVE;

        boolean expectedQualityPassed =
                qualityDecision.passed;

        boolean expectedLongDirection =
                direction == TradeDirection.LONG_STOCK;

        boolean passed =
                expectedCatalyst &&
                        expectedQualityPassed &&
                        expectedLongDirection;

        System.out.println();
        System.out.println("=== " + testName + " ===");
        System.out.println("Ticker: " + news.getTicker());
        System.out.println("Headline: " + news.getHeadline());
        System.out.println("Sentiment: " + sentiment);
        System.out.println("Catalyst: " + catalyst);
        System.out.println("Quality Decision: " + qualityDecision);
        System.out.println("Trade Direction: " + direction);
        System.out.println("Expected catalyst DRUG_DATA_POSITIVE: true");
        System.out.println("Actual catalyst DRUG_DATA_POSITIVE: " + expectedCatalyst);
        System.out.println("Expected quality passed: true");
        System.out.println("Actual quality passed: " + expectedQualityPassed);
        System.out.println("Expected direction LONG_STOCK: true");
        System.out.println("Actual direction LONG_STOCK: " + expectedLongDirection);
        System.out.println();
        System.out.println("Expected final result: PASS");
        System.out.println("Actual final result: " + (passed ? "PASS" : "FAIL"));

        if (!passed) {
            throw new IllegalStateException(
                    testName + " failed."
            );
        }
    }
}