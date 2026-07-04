package com.bot.risk;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class MarketHoursService {

    private static final ZoneId EASTERN_TIME =
            ZoneId.of("America/New_York");

    private static final LocalTime EXTENDED_OPEN =
            LocalTime.of(4, 0);

    private static final LocalTime REGULAR_OPEN =
            LocalTime.of(9, 30);

    private static final LocalTime REGULAR_CLOSE =
            LocalTime.of(16, 0);

    private static final LocalTime EXTENDED_CLOSE =
            LocalTime.of(20, 0);

    public boolean isMarketOpenNow() {
        return isExtendedMarketOpenNow();
    }

    public boolean isRegularMarketOpenNow() {
        String override =
                sessionOverride();

        if ("REGULAR".equals(override)) {
            return true;
        }

        if ("PRE_MARKET".equals(override) ||
                "AFTER_HOURS".equals(override) ||
                "CLOSED".equals(override)) {
            return false;
        }

        return isRegularMarketOpen(
                ZonedDateTime.now(EASTERN_TIME)
        );
    }

    public boolean isExtendedMarketOpenNow() {
        String override =
                sessionOverride();

        if ("REGULAR".equals(override) ||
                "PRE_MARKET".equals(override) ||
                "AFTER_HOURS".equals(override)) {
            return true;
        }

        if ("CLOSED".equals(override)) {
            return false;
        }

        return isExtendedMarketOpen(
                ZonedDateTime.now(EASTERN_TIME)
        );
    }

    public boolean isPreMarketNow() {
        String override =
                sessionOverride();

        if ("PRE_MARKET".equals(override)) {
            return true;
        }

        if ("REGULAR".equals(override) ||
                "AFTER_HOURS".equals(override) ||
                "CLOSED".equals(override)) {
            return false;
        }

        return isPreMarket(
                ZonedDateTime.now(EASTERN_TIME)
        );
    }

    public boolean isAfterHoursNow() {
        String override =
                sessionOverride();

        if ("AFTER_HOURS".equals(override)) {
            return true;
        }

        if ("REGULAR".equals(override) ||
                "PRE_MARKET".equals(override) ||
                "CLOSED".equals(override)) {
            return false;
        }

        return isAfterHours(
                ZonedDateTime.now(EASTERN_TIME)
        );
    }

    public boolean isExtendedOnlyNow() {
        return isPreMarketNow() || isAfterHoursNow();
    }

    public String currentSessionName() {
        String override =
                sessionOverride();

        if (override != null) {
            return override;
        }

        return sessionName(
                ZonedDateTime.now(EASTERN_TIME)
        );
    }

    public boolean isExtendedMarketOpen(ZonedDateTime time) {
        ZonedDateTime eastern =
                toEastern(time);

        return isWeekday(eastern) &&
                !eastern.toLocalTime().isBefore(EXTENDED_OPEN) &&
                eastern.toLocalTime().isBefore(EXTENDED_CLOSE);
    }

    public boolean isRegularMarketOpen(ZonedDateTime time) {
        ZonedDateTime eastern =
                toEastern(time);

        return isWeekday(eastern) &&
                !eastern.toLocalTime().isBefore(REGULAR_OPEN) &&
                eastern.toLocalTime().isBefore(REGULAR_CLOSE);
    }

    public boolean isPreMarket(ZonedDateTime time) {
        ZonedDateTime eastern =
                toEastern(time);

        return isWeekday(eastern) &&
                !eastern.toLocalTime().isBefore(EXTENDED_OPEN) &&
                eastern.toLocalTime().isBefore(REGULAR_OPEN);
    }

    public boolean isAfterHours(ZonedDateTime time) {
        ZonedDateTime eastern =
                toEastern(time);

        return isWeekday(eastern) &&
                !eastern.toLocalTime().isBefore(REGULAR_CLOSE) &&
                eastern.toLocalTime().isBefore(EXTENDED_CLOSE);
    }

    public String sessionName(ZonedDateTime time) {
        ZonedDateTime eastern =
                toEastern(time);

        if (isPreMarket(eastern)) {
            return "PRE_MARKET";
        }

        if (isRegularMarketOpen(eastern)) {
            return "REGULAR";
        }

        if (isAfterHours(eastern)) {
            return "AFTER_HOURS";
        }

        return "CLOSED";
    }

    private String sessionOverride() {
        String raw =
                System.getenv("TRADING_SESSION_OVERRIDE");

        if (raw == null || raw.isBlank()) {
            return null;
        }

        String normalized =
                raw.trim()
                        .toUpperCase()
                        .replace('-', '_')
                        .replace(' ', '_');

        if (normalized.equals("REGULAR") ||
                normalized.equals("PRE_MARKET") ||
                normalized.equals("AFTER_HOURS") ||
                normalized.equals("CLOSED")) {
            return normalized;
        }

        return null;
    }

    private ZonedDateTime toEastern(ZonedDateTime time) {
        if (time == null) {
            return ZonedDateTime.now(EASTERN_TIME);
        }

        return time.withZoneSameInstant(EASTERN_TIME);
    }

    private boolean isWeekday(ZonedDateTime time) {
        DayOfWeek day =
                time.getDayOfWeek();

        return day != DayOfWeek.SATURDAY &&
                day != DayOfWeek.SUNDAY;
    }
}