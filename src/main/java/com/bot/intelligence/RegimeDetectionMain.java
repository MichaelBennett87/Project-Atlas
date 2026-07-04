package com.bot.intelligence;

public class RegimeDetectionMain {
    public static void main(String[] args) {
        System.out.println(new RegimeDetectionEngine().detect().summary());
    }
}
