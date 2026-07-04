package com.bot;

import com.bot.model.CatalystResult;
import com.bot.model.CatalystType;
import com.bot.model.NewsEvent;
import com.bot.strategy.CatalystClassifier;

public class CatalystClassifierTest {

    public static void main(String[] args) {

        CatalystClassifier classifier =
                new CatalystClassifier();

        runTest(
                classifier,
                "WEAK COMMENTARY TEST",
                "Micron Stock Has Become Volatile, But Key Metric Hints at Further Gains",
                "Analysts say a key metric may point to further gains.",
                CatalystType.STOCK_PICK_ARTICLE
        );

        runTest(
                classifier,
                "GUIDANCE RAISE TEST",
                "Apple raises guidance after record revenue and strong demand",
                "The company raised full-year guidance after record revenue.",
                CatalystType.GUIDANCE_RAISE
        );

        runTest(
                classifier,
                "FDA APPROVAL TEST",
                "Biotech company receives FDA approval for new treatment",
                "The FDA approved the treatment for commercial use.",
                CatalystType.FDA_APPROVAL
        );

        runTest(
                classifier,
                "MAJOR CONTRACT TEST",
                "Defense company wins major contract from U.S. Space Force",
                "The contract is valued at more than $500 million.",
                CatalystType.MAJOR_CONTRACT
        );

        runTest(
                classifier,
                "DEBT SECURITIES FALSE POSITIVE TEST",
                "Broadcom Inc. Announces Pricing Terms of Offers to Purchase for Cash Certain of its Outstanding Debt Securities",
                "The company announced pricing terms for offers to purchase outstanding debt securities and senior notes.",
                CatalystType.OFFERING_DILUTION
        );

        runTest(
                classifier,
                "SHAREHOLDER ALERT M&A INVESTIGATION TEST",
                "SHAREHOLDER ALERT: The M&A Class Action Firm Announces An Investigation of AstroNova, Inc. (NASDAQ: ALOT)",
                "A law firm announces an investigation of whether shareholders are obtaining a fair price in a proposed transaction.",
                CatalystType.SECURITIES_LITIGATION
        );

        runTest(
                classifier,
                "ALOT M&A CLASS ACTION CONTINUES INVESTIGATION TEST",
                "$HAREHOLDER ALERT: The M&A Class Action Firm Continues to Investigate the Merger--SUNE, NUVL, CCRN, and BGMS",
                "A law firm says it continues to investigate whether shareholders are receiving a fair price.",
                CatalystType.SECURITIES_LITIGATION
        );

        runTest(
                classifier,
                "PAYO FAIRNESS INVESTIGATION TEST",
                "PAYO Alert: Monsey Firm of Wohl & Fruchter Investigating Fairness of the Proposed Sale of Payoneer Global to Nuvei",
                "The law firm is investigating fairness of the proposed sale and shareholder rights.",
                CatalystType.SECURITIES_LITIGATION
        );

        runTest(
                classifier,
                "BOUGHT DEAL FINANCING TEST",
                "1911 Gold Announces Upsize of Previously Announced Bought Deal Financing to $31 Million",
                "The company announced an upsized bought deal financing and offering terms.",
                CatalystType.OFFERING_DILUTION
        );

        runTest(
                classifier,
                "OVERNIGHT PREFERRED OFFERING TEST",
                "Financial 15 Split Corp. Announces Successful Overnight Offering of Preferred Shares",
                "The company completed an overnight offering of preferred shares.",
                CatalystType.OFFERING_DILUTION
        );

        runTest(
                classifier,
                "PROPERTY ACQUISITION TEST",
                "Terreno Realty Corporation Acquires Property in Landover MD for $77.1 Million",
                "The company acquired a real estate property in Maryland.",
                CatalystType.PROPERTY_ACQUISITION
        );

        runTest(
                classifier,
                "SHARE REPURCHASE TEST",
                "TGE Announces Share Repurchase Program and Voluntary Lock-Up by Controlling Shareholder",
                "The company announced a share repurchase program and lock-up by its controlling shareholder.",
                CatalystType.SHARE_BUYBACK
        );

        runTest(
                classifier,
                "BEAM IND CLEARANCE TEST",
                "Beam Therapeutics Announces Clearance of Investigational New Drug Application for BEAM-304",
                "The company announced FDA clearance of its investigational new drug application.",
                CatalystType.FDA_REGISTRATION
        );

        runTest(
                classifier,
                "NVCR PHASE 3 TOPLINE TEST",
                "Novocure Announces Topline Data from the Phase 3 TRIDENT Trial Evaluating Earlier Use of Therapy",
                "The company announced topline data from a Phase 3 clinical trial in patients.",
                CatalystType.CLINICAL_TRIAL_SUCCESS
        );

        runTest(
                classifier,
                "HIVE AI GPU CONTRACT TEST",
                "HIVE's BUZZ HPC Closes USD $220 Million Sovereign AI GPU Contract with Bell AI Fabric",
                "The company closes a customer contract for AI GPU infrastructure deployment.",
                CatalystType.MAJOR_CONTRACT
        );

        runTest(
                classifier,
                "IPFX DEPARTMENT OF WAR CONTRACT TEST",
                "Quantum Space Awarded a Department of War Contract to Advance On-Orbit Refueling Capabilities",
                "The company was awarded a Department of War contract.",
                CatalystType.MAJOR_CONTRACT
        );

        runTest(
                classifier,
                "DLX CLEAN ACQUISITION TEST",
                "Deluxe to Acquire Celero Commerce, Accelerating Transformation Toward Payments and Data Solutions",
                "The company announced an acquisition of a payments business.",
                CatalystType.MERGER_ACQUISITION
        );


    }

    private static void runTest(
            CatalystClassifier classifier,
            String testName,
            String headline,
            String content,
            CatalystType expected
    ) {
        NewsEvent news =
                new NewsEvent(
                        "test-" + testName,
                        "TEST",
                        headline,
                        content,
                        System.currentTimeMillis()
                );

        CatalystResult result =
                classifier.classify(news);

        System.out.println();
        System.out.println("=== " + testName + " ===");
        System.out.println("Headline: " + headline);
        System.out.println("Expected: " + expected);
        System.out.println("Actual: " + result.type);
        System.out.println("Reason: " + result.reason);
        System.out.println(expected == result.type ? "PASS" : "FAIL");
    }
}