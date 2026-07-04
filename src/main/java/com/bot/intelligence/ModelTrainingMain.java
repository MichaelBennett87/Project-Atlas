package com.bot.intelligence;

public class ModelTrainingMain {
    public static void main(String[] args) {
        System.out.println(new ModelTrainingEngine().train().summary());
    }
}
