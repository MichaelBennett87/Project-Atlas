package com.bot.intelligence;

/**
 * Runs only the OpenAI nightly entry/exit review and validation-gated promotion.
 * Useful after adding API credits so you can rerun the AI policy-review step
 * without repeating the full Polygon replay/research pipeline.
 */
public final class OpenAiEntryExitReviewMain {
    public static void main(String[] args) {
        System.out.println("OPENAI ENTRY/EXIT REVIEW MAIN STARTED: review->promotion only");
        OpenAiNightlyEntryExitPolicyReview.ReviewResult review = new OpenAiNightlyEntryExitPolicyReview().run();
        System.out.println("OPENAI ENTRY/EXIT REVIEW MAIN REVIEW COMPLETE: " + review.summary());
        System.out.println("OPENAI ENTRY/EXIT REVIEW MAIN PROMOTION STARTING: validation-gated");
        AutonomousEntryExitPolicyPromotionPipeline.Result promotion = new AutonomousEntryExitPolicyPromotionPipeline().run();
        System.out.println("OPENAI ENTRY/EXIT REVIEW MAIN PROMOTION COMPLETE: " + promotion.summary());
    }
}
