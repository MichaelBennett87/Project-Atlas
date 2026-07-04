package com.bot.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MarketDataCache {

    private final Map<String, Deque<Bar>> bars = new HashMap<>();

    public void addBar(String ticker, Bar bar) {
        bars.computeIfAbsent(ticker, t -> new ArrayDeque<>()).addLast(bar);

        while (bars.get(ticker).size() > 200) {
            bars.get(ticker).removeFirst();
        }
    }

    public void addBar(
            String ticker,
            double open,
            double high,
            double low,
            double close,
            long volume
    ) {
        Bar bar = new Bar();
        bar.ticker = ticker;
        bar.timestamp = System.currentTimeMillis();
        bar.open = open;
        bar.high = high;
        bar.low = low;
        bar.close = close;
        bar.volume = volume;

        addBar(ticker, bar);
    }

    public List<Bar> recentBars(
            String ticker,
            int limit
    ) {
        Deque<Bar> b = bars.get(ticker);

        if (b == null || b.isEmpty()) {
            return new ArrayList<>();
        }

        List<Bar> list =
                new ArrayList<>(b);

        if (limit <= 0 || list.size() <= limit) {
            return list;
        }

        return new ArrayList<>(
                list.subList(
                        list.size() - limit,
                        list.size()
                )
        );
    }

    public double percentChange(String ticker, int minutes) {
        Deque<Bar> b = bars.get(ticker);

        if (b == null || b.size() < minutes) {
            return 0;
        }

        List<Bar> list = new ArrayList<>(b);

        Bar latest = list.get(list.size() - 1);
        Bar old = list.get(Math.max(0, list.size() - minutes));

        if (old.close == 0) {
            return 0;
        }

        return (latest.close - old.close) / old.close;
    }

    public double percentChangeBars(
            String ticker,
            int barsBack
    ) {
        Deque<Bar> b = bars.get(ticker);

        if (b == null || b.size() <= barsBack) {
            return 0;
        }

        List<Bar> list = new ArrayList<>(b);

        Bar latest =
                list.get(list.size() - 1);

        Bar old =
                list.get(list.size() - 1 - barsBack);

        if (old.close == 0) {
            return 0;
        }

        return (latest.close - old.close)
                / old.close;
    }

    public double volumeRatio(String ticker, int lookback) {
        Deque<Bar> b = bars.get(ticker);

        if (b == null || b.size() < lookback) {
            return 0;
        }

        List<Bar> list = new ArrayList<>(b);

        double avg = list.stream()
                .limit(list.size() - 1)
                .mapToDouble(x -> x.volume)
                .average()
                .orElse(0);

        if (avg == 0) {
            return 0;
        }

        return list.get(list.size() - 1).volume / avg;
    }

    public double volumeRatioBars(
            String ticker,
            int lookbackBars
    ) {
        Deque<Bar> b = bars.get(ticker);

        if (b == null || b.size() <= lookbackBars) {
            return 0;
        }

        List<Bar> list = new ArrayList<>(b);

        int start =
                Math.max(
                        0,
                        list.size() - 1 - lookbackBars
                );

        double avg =
                list.subList(start, list.size() - 1)
                        .stream()
                        .mapToDouble(bar -> bar.volume)
                        .average()
                        .orElse(0);

        long latestVolume =
                list.get(list.size() - 1).volume;

        if (avg <= 0) {
            return 0;
        }

        return latestVolume / avg;
    }

    public boolean hasUsableVolume(
            String ticker,
            int lookbackBars
    ) {
        Deque<Bar> b = bars.get(ticker);

        if (b == null || b.size() <= lookbackBars) {
            return false;
        }

        List<Bar> list = new ArrayList<>(b);

        int start =
                Math.max(
                        0,
                        list.size() - 1 - lookbackBars
                );

        for (int i = start; i < list.size(); i++) {
            if (list.get(i).volume > 0) {
                return true;
            }
        }

        return false;
    }

    public double atr(String ticker, int period) {
        Deque<Bar> b = bars.get(ticker);

        if (b == null || b.size() < period + 1) {
            return 0;
        }

        List<Bar> list = new ArrayList<>(b);

        double sum = 0;

        for (int i = list.size() - period; i < list.size(); i++) {
            Bar current = list.get(i);
            Bar previous = list.get(i - 1);

            double tr = Math.max(
                    current.high - current.low,
                    Math.max(
                            Math.abs(current.high - previous.close),
                            Math.abs(current.low - previous.close)
                    )
            );

            sum += tr;
        }

        return sum / period;
    }


    public double latestClose(String ticker) {
        Deque<Bar> b = bars.get(ticker);

        if (b == null || b.isEmpty()) {
            return 0.0;
        }

        Bar latest = b.peekLast();

        if (latest == null || latest.close <= 0) {
            return 0.0;
        }

        return latest.close;
    }

}