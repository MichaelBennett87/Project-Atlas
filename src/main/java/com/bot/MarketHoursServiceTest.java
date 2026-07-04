package com.bot;

import com.bot.risk.MarketHoursService;

import java.time.ZoneId;
import java.time.ZonedDateTime;

public class MarketHoursServiceTest {

    private static final ZoneId EASTERN_TIME =
            ZoneId.of("America/New_York");

    public static void main(String[] args) {

        MarketHoursService service =
                new MarketHoursService();

        runTest(service, "03:59 CLOSED", 3, 59, "CLOSED", false, false);
        runTest(service, "04:00 PRE_MARKET", 4, 0, "PRE_MARKET", true, false);
        runTest(service, "08:00 PRE_MARKET", 8, 0, "PRE_MARKET", true, false);
        runTest(service, "09:29 PRE_MARKET", 9, 29, "PRE_MARKET", true, false);
        runTest(service, "09:30 REGULAR", 9, 30, "REGULAR", true, true);
        runTest(service, "15:59 REGULAR", 15, 59, "REGULAR", true, true);
        runTest(service, "16:00 AFTER_HOURS", 16, 0, "AFTER_HOURS", true, false);
        runTest(service, "19:59 AFTER_HOURS", 19, 59, "AFTER_HOURS", true, false);
        runTest(service, "20:00 CLOSED", 20, 0, "CLOSED", false, false);

        runWeekendTest(service);
    }

    private static void runTest(
            MarketHoursService service,
            String testName,
            int hour,
            int minute,
            String expectedSession,
            boolean expectedExtendedOpen,
            boolean expectedRegularOpen
    ) {
        ZonedDateTime time =
                ZonedDateTime.of(
                        2026,
                        6,
                        15,
                        hour,
                        minute,
                        0,
                        0,
                        EASTERN_TIME
                );

        String actualSession =
                service.sessionName(time);

        boolean actualExtendedOpen =
                service.isExtendedMarketOpen(time);

        boolean actualRegularOpen =
                service.isRegularMarketOpen(time);

        boolean pass =
                expectedSession.equals(actualSession) &&
                        expectedExtendedOpen == actualExtendedOpen &&
                        expectedRegularOpen == actualRegularOpen;

        System.out.println();
        System.out.println("=== " + testName + " ===");
        System.out.println("Time: " + time);
        System.out.println("Expected session: " + expectedSession);
        System.out.println("Actual session: " + actualSession);
        System.out.println("Expected extended open: " + expectedExtendedOpen);
        System.out.println("Actual extended open: " + actualExtendedOpen);
        System.out.println("Expected regular open: " + expectedRegularOpen);
        System.out.println("Actual regular open: " + actualRegularOpen);
        System.out.println(pass ? "PASS" : "FAIL");
    }

    private static void runWeekendTest(MarketHoursService service) {
        ZonedDateTime time =
                ZonedDateTime.of(
                        2026,
                        6,
                        14,
                        10,
                        0,
                        0,
                        0,
                        EASTERN_TIME
                );

        String actualSession =
                service.sessionName(time);

        boolean actualExtendedOpen =
                service.isExtendedMarketOpen(time);

        boolean actualRegularOpen =
                service.isRegularMarketOpen(time);

        boolean pass =
                "CLOSED".equals(actualSession) &&
                        !actualExtendedOpen &&
                        !actualRegularOpen;

        System.out.println();
        System.out.println("=== WEEKEND CLOSED TEST ===");
        System.out.println("Time: " + time);
        System.out.println("Expected session: CLOSED");
        System.out.println("Actual session: " + actualSession);
        System.out.println("Expected extended open: false");
        System.out.println("Actual extended open: " + actualExtendedOpen);
        System.out.println("Expected regular open: false");
        System.out.println("Actual regular open: " + actualRegularOpen);
        System.out.println(pass ? "PASS" : "FAIL");
    }
}