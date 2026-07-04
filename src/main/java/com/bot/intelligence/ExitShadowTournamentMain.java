package com.bot.intelligence;

public final class ExitShadowTournamentMain {
    private ExitShadowTournamentMain() {
    }

    public static void main(String[] args) {
        System.out.println("EXIT SHADOW TOURNAMENT STARTED");
        ExitShadowTournamentEngine.Result result = new ExitShadowTournamentEngine().run();
        System.out.println("EXIT SHADOW TOURNAMENT COMPLETE: " + result.summary());
    }
}
