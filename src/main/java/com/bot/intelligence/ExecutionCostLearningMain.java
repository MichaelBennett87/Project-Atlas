package com.bot.intelligence;

public final class ExecutionCostLearningMain {
    private ExecutionCostLearningMain() {
    }

    public static void main(String[] args) {
        System.out.println("EXECUTION COST LEARNING STARTED");
        ExecutionCostLearningEngine.Result result = new ExecutionCostLearningEngine().run();
        System.out.println("EXECUTION COST LEARNING COMPLETE: " + result.summary());
    }
}
