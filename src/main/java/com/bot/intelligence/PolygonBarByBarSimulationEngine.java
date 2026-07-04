package com.bot.intelligence;

import com.bot.master.MasterStrategyEngine;
import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;
import com.bot.master.TradingStrategy;
import com.bot.model.Bar;
import com.bot.model.MarketDataCache;
import com.bot.model.NewsEvent;
import com.bot.model.TradeDirection;
import com.bot.scanner.SharedRollingBarHistoryService;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline bar-by-bar simulator for locally cached Polygon-style OHLCV data.
 *
 * This intentionally does not place orders. It walks historical bars forward,
 * evaluates the same implemented TradingStrategy classes used by the live bot,
 * simulates conservative fills/exits, and emits a guarded policy file consumed
 * by AdaptiveTradingPolicyStore.
 */
public final class PolygonBarByBarSimulationEngine {

    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    private static final Pattern DATE_IN_FILE_NAME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final double FILE_RANK_MIN_EFFECTIVE_PRICE = 0.50;
    private static final double FILE_RANK_MAX_EFFECTIVE_PRICE = 250.0;
    private static final double DEFAULT_MIN_QUALITY_PRICE = 0.10;
    private static final double DEFAULT_MAX_QUALITY_PRICE = 750.0;

    private final HistoricalMarketDataRepository repository = new HistoricalMarketDataRepository();
    private final HistoricalNewsRepository newsRepository = new HistoricalNewsRepository();
    private final ExecutionCostModel executionCostModel = ExecutionCostModel.getInstance();
    private final Path policyPath = Path.of(env("BAR_BY_BAR_SIMULATION_POLICY_PATH", "logs/bar_by_bar_simulation_policy.properties"));
    private final Path reportPath = Path.of(env("BAR_BY_BAR_SIMULATION_REPORT_PATH", "logs/bar_by_bar_simulation_report.txt"));
    private final Path tradesPath = Path.of(env("BAR_BY_BAR_SIMULATION_TRADES_PATH", "logs/bar_by_bar_simulation_trades.csv"));
    private final Path candidateWatchlistPath = Path.of(env("BAR_BY_BAR_CANDIDATE_WATCHLIST_PATH", "logs/bar_by_bar_candidate_watchlist.csv"));

    private final int maxFiles = Math.max(1, envInt("BAR_SIM_MAX_FILES", 200));
    private final int maxRowsPerFile = Math.max(100, envInt("BAR_SIM_MAX_ROWS_PER_FILE", 250_000));
    private final boolean prioritizeHighVolumeFiles = envBool("BAR_SIM_PRIORITIZE_HIGH_VOLUME_FILES", true);
    private final int fileRankSampleRows = Math.max(10, envInt("BAR_SIM_FILE_RANK_SAMPLE_ROWS", 750));
    private final int fileRankPreviewCount = Math.max(1, envInt("BAR_SIM_FILE_RANK_PREVIEW_COUNT", 15));
    private final boolean dataQualityEnabled = envBool("BAR_SIM_DATA_QUALITY_ENABLED", true);
    private final boolean dataQualityRequireTimestamp = envBool("BAR_SIM_DATA_QUALITY_REQUIRE_TIMESTAMP", true);
    private final boolean dataQualityRequireOhlc = envBool("BAR_SIM_DATA_QUALITY_REQUIRE_OHLC", true);
    private final int minQualityAcceptedRows = Math.max(2, envInt("BAR_SIM_DATA_QUALITY_MIN_ACCEPTED_ROWS", 25));
    private final double minQualityAcceptedRatio = clamp(envDouble("BAR_SIM_DATA_QUALITY_MIN_ACCEPTED_RATIO", 0.85), 0.0, 1.0);
    private final double minQualityPrice = Math.max(0.0001, envDouble("BAR_SIM_DATA_QUALITY_MIN_PRICE", DEFAULT_MIN_QUALITY_PRICE));
    private final double maxQualityPrice = Math.max(minQualityPrice, envDouble("BAR_SIM_DATA_QUALITY_MAX_PRICE", DEFAULT_MAX_QUALITY_PRICE));
    private final double maxQualityIntrabarRange = Math.max(1.01, envDouble("BAR_SIM_DATA_QUALITY_MAX_INTRABAR_RANGE", 4.0));
    private final double maxQualityRowVolume = Math.max(1_000.0, envDouble("BAR_SIM_DATA_QUALITY_MAX_ROW_VOLUME", 2_000_000_000.0));
    private final double maxQualityRowDollarVolume = Math.max(1_000_000.0, envDouble("BAR_SIM_DATA_QUALITY_MAX_ROW_DOLLARS", 5_000_000_000.0));
    private final int maxNewsRows = Math.max(0, envInt("BAR_SIM_MAX_NEWS_ROWS", 200_000));
    private final int warmupBars = Math.max(1, envInt("BAR_SIM_WARMUP_BARS", 20));
    private final int maxHoldBars = Math.max(1, envInt("BAR_SIM_MAX_HOLD_BARS", 30));
    private final int fixedQuantity = Math.max(0, envInt("BAR_SIM_FIXED_QUANTITY", 0));
    private final int maxQuantity = Math.max(1, envInt("BAR_SIM_MAX_QUANTITY", 5_000));
    private final int maxEvaluations = Math.max(1_000, envInt("BAR_SIM_MAX_EVALUATIONS", 2_000_000));
    private final int maxTradeRows = Math.max(1_000, envInt("BAR_SIM_MAX_TRADE_ROWS", 200_000));
    private final double accountEquity = Math.max(1_000.0, envDouble("BAR_SIM_ACCOUNT_EQUITY", 100_000.0));
    private final double maxNotionalFraction = clamp(envDouble("BAR_SIM_MAX_NOTIONAL_FRACTION", 0.10), 0.001, 1.0);
    private final double slippageBps = Math.max(0.0, envDouble("BAR_SIM_SLIPPAGE_BPS", 5.0));
    private final boolean executionCostPolicyEnabled = envBool("BAR_SIM_EXECUTION_COST_POLICY_ENABLED", true);
    private final double feePerShare = Math.max(0.0, envDouble("BAR_SIM_FEE_PER_SHARE", 0.005));
    private final double minTargetMove = clamp(envDouble("BAR_SIM_MIN_TARGET_MOVE", 0.004), 0.0005, 0.50);
    private final double maxTargetMove = clamp(envDouble("BAR_SIM_MAX_TARGET_MOVE", 0.090), 0.001, 1.00);
    private final double stopLossMove = clamp(envDouble("BAR_SIM_STOP_LOSS_MOVE", 0.025), 0.001, 0.75);
    private final double trailingStartMove = clamp(envDouble("BAR_SIM_TRAILING_START_MOVE", 0.012), 0.001, 0.75);
    private final double trailingGivebackMove = clamp(envDouble("BAR_SIM_TRAILING_GIVEBACK_MOVE", 0.008), 0.001, 0.75);
    private final long maxNewsAgeMs = Math.max(60_000L, envLong("BAR_SIM_NEWS_MAX_AGE_MINUTES", 360L) * 60_000L);
    private final boolean historicalNewsEnabled = envBool("BAR_SIM_HISTORICAL_NEWS_ENABLED", true);
    private final boolean pessimisticIntrabar = envBool("BAR_SIM_PESSIMISTIC_INTRABAR", true);
    private final double trainFraction = clamp(envDouble("BAR_SIM_TRAIN_FRACTION", 0.70), 0.10, 0.90);

    private final int minPromotionTrades = Math.max(5, envInt("BAR_SIM_PROMOTION_MIN_TRADES", 20));
    private final int minValidationTrades = Math.max(3, envInt("BAR_SIM_PROMOTION_MIN_VALIDATION_TRADES", 6));
    private final double minTotalProfitFactor = Math.max(0.0, envDouble("BAR_SIM_PROMOTION_MIN_TOTAL_PROFIT_FACTOR", 1.0));
    private final double minTotalExpectancy = envDouble("BAR_SIM_PROMOTION_MIN_TOTAL_EXPECTANCY_DOLLARS", 0.0);
    private final double minValidationProfitFactor = Math.max(1.0, envDouble("BAR_SIM_PROMOTION_MIN_PROFIT_FACTOR", 1.15));
    private final double minValidationWinRate = clamp(envDouble("BAR_SIM_PROMOTION_MIN_WIN_RATE", 0.48), 0.0, 1.0);
    private final double minValidationExpectancy = envDouble("BAR_SIM_PROMOTION_MIN_EXPECTANCY_DOLLARS", 0.01);
    private final boolean walkForwardValidationEnabled = envBool("BAR_SIM_WALK_FORWARD_ENABLED", true);
    private final int minWalkForwardWindows = Math.max(1, envInt("BAR_SIM_WALK_FORWARD_MIN_WINDOWS", 2));
    private final int minWalkForwardPassingWindows = Math.max(1, envInt("BAR_SIM_WALK_FORWARD_MIN_PASSING_WINDOWS", 2));
    private final int minWalkForwardTradesPerWindow = Math.max(1, envInt("BAR_SIM_WALK_FORWARD_MIN_TRADES_PER_WINDOW", 3));
    private final double minWalkForwardProfitFactor = Math.max(1.0, envDouble("BAR_SIM_WALK_FORWARD_MIN_PROFIT_FACTOR", 1.05));
    private final double minWalkForwardExpectancy = envDouble("BAR_SIM_WALK_FORWARD_MIN_EXPECTANCY_DOLLARS", 0.0);
    private final boolean walkForwardLossVetoEnabled = envBool("BAR_SIM_WALK_FORWARD_LOSS_VETO_ENABLED", true);
    private final double maxWalkForwardWindowLossFraction = clamp(
            envDouble("BAR_SIM_WALK_FORWARD_MAX_WINDOW_LOSS_FRACTION", 0.005), 0.0, 1.0);
    private final double maxWalkForwardWindowLoss = Math.max(0.0,
            envDouble("BAR_SIM_WALK_FORWARD_MAX_WINDOW_LOSS_DOLLARS",
                    accountEquity * maxWalkForwardWindowLossFraction));
    private final double maxWalkForwardWindowDrawdownFraction = clamp(
            envDouble("BAR_SIM_WALK_FORWARD_MAX_WINDOW_DRAWDOWN_FRACTION", 0.0075), 0.0, 1.0);
    private final double maxWalkForwardWindowDrawdown = Math.max(0.0,
            envDouble("BAR_SIM_WALK_FORWARD_MAX_WINDOW_DRAWDOWN_DOLLARS",
                    accountEquity * maxWalkForwardWindowDrawdownFraction));
    private final boolean multiDaySelectionEnabled = envBool("BAR_SIM_MULTI_DAY_SELECTION_ENABLED", true);
    private final int minSelectionDaysPerTicker = Math.max(1, envInt("BAR_SIM_SELECTION_MIN_DAYS_PER_TICKER",
            defaultMinSelectionDaysPerTicker()));
    private final int maxSelectionFilesPerTicker = Math.max(1, envInt("BAR_SIM_SELECTION_MAX_FILES_PER_TICKER",
            Math.max(2, minSelectionDaysPerTicker)));
    private final boolean adaptiveEvaluationBudgetEnabled = envBool("BAR_SIM_ADAPTIVE_EVALUATION_BUDGET_ENABLED", true);
    private final int adaptiveEvaluationTargetCoverageTickers = Math.max(1,
            envInt("BAR_SIM_ADAPTIVE_EVAL_TARGET_COVERAGE_TICKERS", 5));
    private final int adaptiveEvaluationMinTargetFiles = Math.max(1,
            envInt("BAR_SIM_ADAPTIVE_EVAL_MIN_TARGET_FILES", maxSelectionFilesPerTicker));
    private final int adaptiveEvaluationEstimatedBarsPerFile = Math.max(100,
            envInt("BAR_SIM_ADAPTIVE_EVAL_ESTIMATED_BARS_PER_FILE", 8_000));
    private final double adaptiveEvaluationSafetyMultiplier = clamp(
            envDouble("BAR_SIM_ADAPTIVE_EVAL_SAFETY_MULTIPLIER", 1.25), 1.0, 5.0);
    private final double adaptiveEvaluationMaxMultiplier = Math.max(1.0,
            envDouble("BAR_SIM_ADAPTIVE_EVAL_MAX_MULTIPLIER", 4.0));
    private final int adaptiveEvaluationAbsoluteMax = Math.max(maxEvaluations,
            envInt("BAR_SIM_ADAPTIVE_EVAL_ABSOLUTE_MAX", 8_000_000));
    private final double maxPromotionBoost = clamp(envDouble("BAR_SIM_PROMOTION_MAX_BOOST", 0.20), 0.01, 0.50);
    private final boolean disableFailedStrategies = envBool("BAR_SIM_DISABLE_FAILED_STRATEGIES", false);
    private final boolean candidateWatchlistEnabled = envBool("BAR_SIM_CANDIDATE_WATCHLIST_ENABLED", true);
    private final int candidateWatchlistMaxEntries = Math.max(1, envInt("BAR_SIM_CANDIDATE_WATCHLIST_MAX_ENTRIES", 50));

    public Result run() {
        long started = System.currentTimeMillis();
        List<TradingStrategy> strategies = selectedStrategies(MasterStrategyEngine.defaultStrategies());
        HistoricalNewsRepository.LoadedNews historicalNews = historicalNewsEnabled
                ? newsRepository.load(maxNewsRows)
                : HistoricalNewsRepository.LoadedNews.empty(Path.of(env("HISTORICAL_NEWS_DATA_DIR", "logs/historical_news")));
        Map<String, StrategyStats> statsByStrategy = new LinkedHashMap<>();
        for (TradingStrategy strategy : strategies) {
            statsByStrategy.put(normalizeStrategy(strategy.name()), new StrategyStats(normalizeStrategy(strategy.name())));
        }

        int filesSeen = 0;
        int filesProcessed = 0;
        int barsProcessed = 0;
        int evaluations = 0;
        int errors = 0;
        int newsContextBars = 0;
        boolean evaluationLimitHit = false;
        DataQualityAggregate replayQuality = new DataQualityAggregate();

        SharedRollingBarHistoryService sharedBars = SharedRollingBarHistoryService.getInstance();
        FileSelectionPlan fileSelection = selectFiles(repository.csvFiles());
        EvaluationBudget evaluationBudget = evaluationBudget(fileSelection, strategies.size());
        for (FileRank fileRank : fileSelection.files) {
            if (filesSeen >= maxFiles || evaluationLimitHit) {
                break;
            }
            Path file = fileRank.path;
            filesSeen++;
            QualityBars qualifiedBars = qualifyBars(repository.loadBars(file, maxRowsPerFile));
            replayQuality.record(qualifiedBars);
            if (!qualifiedBars.usableForReplay(Math.max(2, warmupBars))) {
                continue;
            }
            List<HistoricalMarketDataRepository.HistoricalBar> historicalBars = qualifiedBars.accepted;
            if (historicalBars.size() < Math.max(2, warmupBars)) {
                continue;
            }
            filesProcessed++;
            barsProcessed += historicalBars.size();

            MarketDataCache marketData = new MarketDataCache();
            Map<String, OpenPosition> openPositions = new LinkedHashMap<>();
            Set<String> observedTickers = new HashSet<>();
            Map<String, Bar> lastBarByTicker = new LinkedHashMap<>();
            long lastTimestamp = 0L;
            int barIndex = -1;

            for (HistoricalMarketDataRepository.HistoricalBar historical : historicalBars) {
                if (evaluationLimitHit) {
                    break;
                }
                barIndex++;
                Bar bar = toBar(historical, lastTimestamp);
                lastTimestamp = bar.timestamp;
                observedTickers.add(bar.ticker);
                lastBarByTicker.put(bar.ticker, bar);

                marketData.addBar(bar.ticker, bar);
                sharedBars.observe(bar.ticker, bar);
                ParabolicTopVolumeTracker.getInstance().observeBar(bar.ticker, bar);

                closeEligiblePositions(openPositions, bar, barIndex, file, statsByStrategy);

                if (marketData.recentBars(bar.ticker, warmupBars).size() < warmupBars) {
                    continue;
                }

                NewsEvent latestNews = HistoricalNewsRepository.latestFresh(
                        historicalNews.byTicker,
                        bar.ticker,
                        bar.timestamp,
                        maxNewsAgeMs
                );
                if (latestNews != null) {
                    newsContextBars++;
                }
                StrategyContext context = new StrategyContext(bar.ticker, marketData, latestNews, null, bar.close, accountEquity);
                for (TradingStrategy strategy : strategies) {
                    if (evaluations >= evaluationBudget.effectiveMaxEvaluations) {
                        evaluationLimitHit = true;
                        break;
                    }
                    evaluations++;
                    String strategyName = normalizeStrategy(strategy.name());
                    String key = positionKey(strategyName, bar.ticker);
                    if (openPositions.containsKey(key)) {
                        continue;
                    }
                    try {
                        StrategySignal signal = strategy.evaluate(context);
                        if (!isTradable(signal)) {
                            continue;
                        }
                        ExecutionCostModel.CostReview costReview = executionCostPolicyEnabled
                                ? executionCostModel.review(bar.ticker, strategyName, signal.getExpectedMovePercent())
                                : null;
                        if (costReview != null && !costReview.approved) {
                            continue;
                        }
                        int quantity = quantityFor(signal, bar.close);
                        if (costReview != null && costReview.sizingMultiplier < 1.0) {
                            quantity = Math.max(1, (int)Math.floor(quantity * Math.max(0.05, costReview.sizingMultiplier)));
                        }
                        if (quantity <= 0) {
                            continue;
                        }
                        StrategyStats stats = statsByStrategy.computeIfAbsent(strategyName, StrategyStats::new);
                        stats.actionableSignals++;
                        double effectiveSlippageBps = executionSlippageBps(bar.ticker, strategyName);
                        OpenPosition position = OpenPosition.open(signal, strategyName, bar, barIndex, quantity,
                                targetMove(signal), stopLossMove, effectiveSlippageBps, latestNews);
                        openPositions.put(key, position);
                    } catch (Exception e) {
                        errors++;
                        statsByStrategy.computeIfAbsent(strategyName, StrategyStats::new).evaluationErrors++;
                    }
                }
            }

            if (!lastBarByTicker.isEmpty()) {
                forceClosePositions(openPositions, lastBarByTicker, Math.max(0, barIndex), file, statsByStrategy);
            }

            for (String ticker : observedTickers) {
                sharedBars.forget(ticker);
            }
        }

        List<TradeRecord> allTrades = allTrades(statsByStrategy);
        allTrades.sort(Comparator.comparingLong((TradeRecord t) -> t.exitTimestamp)
                .thenComparing(t -> t.strategy)
                .thenComparing(t -> t.ticker));
        int tradeRowsWritten = writeTrades(allTrades);
        List<CandidateWatchlistEntry> candidateWatchlist = candidateWatchlist(statsByStrategy);
        int candidateWatchlistRowsWritten = writeCandidateWatchlist(candidateWatchlist);
        PromotionSummary promotion = writePolicy(statsByStrategy, started, filesProcessed, barsProcessed,
                allTrades.size(), historicalNews, newsContextBars, fileSelection, replayQuality, evaluationBudget,
                candidateWatchlist, candidateWatchlistRowsWritten);
        writeReport(statsByStrategy, promotion, started, System.currentTimeMillis(), filesProcessed, barsProcessed,
                evaluations, errors, evaluationLimitHit, allTrades.size(), tradeRowsWritten, strategies.size(),
                historicalNews, newsContextBars, fileSelection, replayQuality, evaluationBudget,
                candidateWatchlist, candidateWatchlistRowsWritten);

        return new Result(filesProcessed, barsProcessed, evaluations, allTrades.size(), promotion.promoted,
                promotion.riskReduced, candidateWatchlist.size(), policyPath, reportPath, tradesPath,
                candidateWatchlistPath, System.currentTimeMillis() - started);
    }

    private void closeEligiblePositions(Map<String, OpenPosition> openPositions, Bar bar, int barIndex, Path file,
                                        Map<String, StrategyStats> statsByStrategy) {
        List<String> closedKeys = new ArrayList<>();
        for (Map.Entry<String, OpenPosition> entry : openPositions.entrySet()) {
            OpenPosition position = entry.getValue();
            if (!position.ticker.equals(bar.ticker)) {
                continue;
            }
            ExitDecision exit = position.exitDecision(bar, barIndex, pessimisticIntrabar, maxHoldBars,
                    trailingStartMove, trailingGivebackMove, executionSlippageBps(position.ticker, position.strategy));
            if (exit == null) {
                continue;
            }
            TradeRecord record = position.close(exit, bar, barIndex, file, feePerShare);
            statsByStrategy.computeIfAbsent(position.strategy, StrategyStats::new).record(record);
            closedKeys.add(entry.getKey());
        }
        for (String key : closedKeys) {
            openPositions.remove(key);
        }
    }

    private void forceClosePositions(Map<String, OpenPosition> openPositions, Map<String, Bar> lastBarByTicker, int barIndex, Path file,
                                     Map<String, StrategyStats> statsByStrategy) {
        for (OpenPosition position : openPositions.values()) {
            Bar finalBar = lastBarByTicker.get(position.ticker);
            if (finalBar == null) {
                continue;
            }
            ExitDecision exit = ExitDecision.of(adjustExitPrice(finalBar.close, position.direction,
                            executionSlippageBps(position.ticker, position.strategy)),
                    "FORCED_END_OF_FILE");
            TradeRecord record = position.close(exit, finalBar, barIndex, file, feePerShare);
            statsByStrategy.computeIfAbsent(position.strategy, StrategyStats::new).record(record);
        }
        openPositions.clear();
    }

    private int writeTrades(List<TradeRecord> trades) {
        try {
            Path parent = tradesPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(tradesPath, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.write("entryTime,exitTime,ticker,strategy,direction,quantity,entryPrice,exitPrice,pnlDollars,pnlPercent,barsHeld,exitReason,confidence,expectedMove,newsSource,newsAgeMinutes,catalystScore,headline,file\n");
                int written = 0;
                for (TradeRecord trade : trades) {
                    if (written >= maxTradeRows) {
                        break;
                    }
                    writer.write(trade.toCsvLine());
                    writer.write('\n');
                    written++;
                }
                return written;
            }
        } catch (Exception e) {
            System.out.println("BAR-BY-BAR SIMULATION TRADES WRITE FAILED: " + e.getMessage());
            return 0;
        }
    }

    private int writeCandidateWatchlist(List<CandidateWatchlistEntry> candidates) {
        try {
            Path parent = candidateWatchlistPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(candidateWatchlistPath, StandardCharsets.UTF_8)) {
                writer.write("updatedAt,strategy,status,gate,retestAction,priorityScore,trades,pnlDollars,profitFactor,winRate,expectancyDollars,validationTrades,validationPnlDollars,validationProfitFactor,validationWinRate,validationExpectancyDollars,walkForwardWindows,walkForwardEligible,walkForwardPassing,walkForwardWorstPnlDollars,walkForwardWorstDrawdownDollars,reason");
                writer.write('\n');
                int written = 0;
                for (CandidateWatchlistEntry candidate : candidates) {
                    writer.write(candidate.toCsvLine());
                    writer.write('\n');
                    written++;
                }
                return written;
            }
        } catch (Exception e) {
            System.out.println("BAR-BY-BAR CANDIDATE WATCHLIST WRITE FAILED: " + e.getMessage());
            return 0;
        }
    }

    private PromotionSummary writePolicy(Map<String, StrategyStats> statsByStrategy, long startedMs, int files,
                                         int bars, int trades, HistoricalNewsRepository.LoadedNews historicalNews,
                                         int newsContextBars, FileSelectionPlan fileSelection,
                                         DataQualityAggregate replayQuality,
                                         EvaluationBudget evaluationBudget,
                                         List<CandidateWatchlistEntry> candidateWatchlist,
                                         int candidateWatchlistRowsWritten) {
        Properties p = new Properties();
        p.setProperty("source", "polygon_bar_by_bar_simulation");
        p.setProperty("updatedAtMs", Long.toString(System.currentTimeMillis()));
        p.setProperty("startedAt", Instant.ofEpochMilli(startedMs).toString());
        p.setProperty("reportPath", reportPath.toString());
        p.setProperty("tradesPath", tradesPath.toString());
        p.setProperty("candidateWatchlist.path", candidateWatchlistPath.toString());
        p.setProperty("candidateWatchlist.enabled", Boolean.toString(candidateWatchlistEnabled));
        p.setProperty("candidateWatchlist.maxEntries", Integer.toString(candidateWatchlistMaxEntries));
        p.setProperty("candidateWatchlist.count", Integer.toString(candidateWatchlist.size()));
        p.setProperty("candidateWatchlist.rowsWritten", Integer.toString(candidateWatchlistRowsWritten));
        p.setProperty("historicalDataRoot", repository.root().toString());
        p.setProperty("historicalNewsRoot", historicalNews.root.toString());
        p.setProperty("historicalNewsFiles", Integer.toString(historicalNews.files));
        p.setProperty("historicalNewsRows", Integer.toString(historicalNews.rows));
        p.setProperty("newsContextBars", Integer.toString(newsContextBars));
        p.setProperty("newsMaxAgeMinutes", Long.toString(maxNewsAgeMs / 60_000L));
        p.setProperty("executionCostPolicy.enabled", Boolean.toString(executionCostPolicyEnabled));
        p.setProperty("executionCostPolicy.path", System.getenv().getOrDefault("EXECUTION_COST_POLICY_PATH", "logs/execution_cost_policy.properties"));
        p.setProperty("executionCostPolicy.baseSlippageBps", fmt(slippageBps));
        p.setProperty("files", Integer.toString(files));
        p.setProperty("bars", Integer.toString(bars));
        p.setProperty("trades", Integer.toString(trades));
        p.setProperty("evaluationBudget.adaptiveEnabled", Boolean.toString(evaluationBudget.adaptiveEnabled));
        p.setProperty("evaluationBudget.configuredMax", Integer.toString(evaluationBudget.configuredMaxEvaluations));
        p.setProperty("evaluationBudget.effectiveMax", Integer.toString(evaluationBudget.effectiveMaxEvaluations));
        p.setProperty("evaluationBudget.expanded", Boolean.toString(evaluationBudget.expanded()));
        p.setProperty("evaluationBudget.targetCoverageTickers", Integer.toString(evaluationBudget.targetCoverageTickers));
        p.setProperty("evaluationBudget.targetReplayFiles", Integer.toString(evaluationBudget.targetReplayFiles));
        p.setProperty("evaluationBudget.coverageTargetReached", Boolean.toString(files >= evaluationBudget.targetReplayFiles));
        p.setProperty("evaluationBudget.estimatedBarsPerFile", Integer.toString(evaluationBudget.estimatedBarsPerFile));
        p.setProperty("evaluationBudget.safetyMultiplier", fmt(evaluationBudget.safetyMultiplier));
        p.setProperty("evaluationBudget.requested", Long.toString(evaluationBudget.requestedEvaluations));
        p.setProperty("evaluationBudget.ceiling", Integer.toString(evaluationBudget.ceilingEvaluations));
        p.setProperty("evaluationBudget.reason", evaluationBudget.reason);
        p.setProperty("fileSelection.mode", fileSelection.mode);
        p.setProperty("fileSelection.candidates", Integer.toString(fileSelection.candidates));
        p.setProperty("fileSelection.liquidityRanked", Integer.toString(fileSelection.liquidityRanked));
        p.setProperty("fileSelection.fallbackRanked", Integer.toString(fileSelection.fallbackRanked));
        p.setProperty("fileSelection.sampleRows", Integer.toString(fileSelection.sampleRows));
        p.setProperty("fileSelection.score", "shareVolume*clampedAveragePrice");
        p.setProperty("fileSelection.multiDayCoverageEnabled", Boolean.toString(fileSelection.multiDayCoverageEnabled));
        p.setProperty("fileSelection.minDaysPerTicker", Integer.toString(fileSelection.minDaysPerTicker));
        p.setProperty("fileSelection.maxFilesPerTicker", Integer.toString(fileSelection.maxFilesPerTicker));
        p.setProperty("fileSelection.plannedReplayFiles", Integer.toString(fileSelection.plannedReplayFiles));
        p.setProperty("fileSelection.coverageReadyTickers", Integer.toString(fileSelection.coverageReadyTickers));
        p.setProperty("fileSelection.minEffectivePrice", fmt(FILE_RANK_MIN_EFFECTIVE_PRICE));
        p.setProperty("fileSelection.maxEffectivePrice", fmt(FILE_RANK_MAX_EFFECTIVE_PRICE));
        p.setProperty("fileSelection.qualityRejected", Integer.toString(fileSelection.qualityRejected));
        p.setProperty("fileSelection.usableCandidates", Integer.toString(fileSelection.files.size()));
        p.setProperty("dataQuality.enabled", Boolean.toString(dataQualityEnabled));
        p.setProperty("dataQuality.requireTimestamp", Boolean.toString(dataQualityRequireTimestamp));
        p.setProperty("dataQuality.requireOhlc", Boolean.toString(dataQualityRequireOhlc));
        p.setProperty("dataQuality.minPrice", fmt(minQualityPrice));
        p.setProperty("dataQuality.maxPrice", fmt(maxQualityPrice));
        p.setProperty("dataQuality.minAcceptedRows", Integer.toString(minQualityAcceptedRows));
        p.setProperty("dataQuality.minAcceptedRatio", fmt(minQualityAcceptedRatio));
        p.setProperty("dataQuality.maxIntrabarRange", fmt(maxQualityIntrabarRange));
        p.setProperty("dataQuality.maxRowVolume", fmt(maxQualityRowVolume));
        p.setProperty("dataQuality.maxRowDollars", fmt(maxQualityRowDollarVolume));
        p.setProperty("dataQuality.replayFiles", Integer.toString(replayQuality.files));
        p.setProperty("dataQuality.replayFilesRejected", Integer.toString(replayQuality.rejectedFiles));
        p.setProperty("dataQuality.replayRowsRaw", Integer.toString(replayQuality.rawRows));
        p.setProperty("dataQuality.replayRowsAccepted", Integer.toString(replayQuality.acceptedRows));
        p.setProperty("dataQuality.replayRowsRejected", Integer.toString(replayQuality.rejectedRows()));
        p.setProperty("dataQuality.reject.missing", Integer.toString(replayQuality.missingRejects));
        p.setProperty("dataQuality.reject.ticker", Integer.toString(replayQuality.tickerRejects));
        p.setProperty("dataQuality.reject.timestamp", Integer.toString(replayQuality.timestampRejects));
        p.setProperty("dataQuality.reject.price", Integer.toString(replayQuality.priceRejects));
        p.setProperty("dataQuality.reject.volume", Integer.toString(replayQuality.volumeRejects));
        p.setProperty("dataQuality.reject.ohlc", Integer.toString(replayQuality.ohlcRejects));
        p.setProperty("dataQuality.reject.range", Integer.toString(replayQuality.rangeRejects));
        p.setProperty("dataQuality.reject.notional", Integer.toString(replayQuality.notionalRejects));
        int preview = Math.min(fileRankPreviewCount, fileSelection.files.size());
        for (int i = 0; i < preview; i++) {
            p.setProperty("fileSelection.top." + (i + 1), fileSelection.files.get(i).toPolicyValue());
        }
        p.setProperty("promotion.minTrades", Integer.toString(minPromotionTrades));
        p.setProperty("promotion.minValidationTrades", Integer.toString(minValidationTrades));
        p.setProperty("promotion.minTotalProfitFactor", fmt(minTotalProfitFactor));
        p.setProperty("promotion.minTotalExpectancyDollars", fmt(minTotalExpectancy));
        p.setProperty("promotion.minValidationProfitFactor", fmt(minValidationProfitFactor));
        p.setProperty("promotion.minValidationWinRate", fmt(minValidationWinRate));
        p.setProperty("promotion.minValidationExpectancyDollars", fmt(minValidationExpectancy));
        p.setProperty("promotion.walkForward.enabled", Boolean.toString(walkForwardValidationEnabled));
        p.setProperty("promotion.walkForward.minWindows", Integer.toString(minWalkForwardWindows));
        p.setProperty("promotion.walkForward.minPassingWindows", Integer.toString(minWalkForwardPassingWindows));
        p.setProperty("promotion.walkForward.minTradesPerWindow", Integer.toString(minWalkForwardTradesPerWindow));
        p.setProperty("promotion.walkForward.minProfitFactor", fmt(minWalkForwardProfitFactor));
        p.setProperty("promotion.walkForward.minExpectancyDollars", fmt(minWalkForwardExpectancy));
        p.setProperty("promotion.walkForward.lossVeto.enabled", Boolean.toString(walkForwardLossVetoEnabled));
        p.setProperty("promotion.walkForward.lossVeto.maxWindowLossDollars", fmt(maxWalkForwardWindowLoss));
        p.setProperty("promotion.walkForward.lossVeto.maxWindowLossFraction", fmt(maxWalkForwardWindowLossFraction));
        p.setProperty("promotion.walkForward.lossVeto.maxWindowDrawdownDollars", fmt(maxWalkForwardWindowDrawdown));
        p.setProperty("promotion.walkForward.lossVeto.maxWindowDrawdownFraction", fmt(maxWalkForwardWindowDrawdownFraction));

        for (int i = 0; i < candidateWatchlist.size(); i++) {
            CandidateWatchlistEntry candidate = candidateWatchlist.get(i);
            String prefix = "candidateWatchlist." + (i + 1) + ".";
            p.setProperty(prefix + "strategy", candidate.strategy);
            p.setProperty(prefix + "status", candidate.status);
            p.setProperty(prefix + "gate", candidate.gate);
            p.setProperty(prefix + "retestAction", candidate.retestAction);
            p.setProperty(prefix + "priorityScore", fmt(candidate.priorityScore));
            p.setProperty(prefix + "summary", candidate.toPolicyValue());

            String strategyPrefix = "candidateWatchlist.strategy." + candidate.strategy + ".";
            p.setProperty(strategyPrefix + "status", candidate.status);
            p.setProperty(strategyPrefix + "gate", candidate.gate);
            p.setProperty(strategyPrefix + "retestAction", candidate.retestAction);
            p.setProperty(strategyPrefix + "priorityScore", fmt(candidate.priorityScore));
            p.setProperty("barSim.watchlistStatus." + candidate.strategy, candidate.status);
            p.setProperty("simulationStatus." + candidate.strategy, "WATCHLIST");
        }

        int promoted = 0;
        int riskReduced = 0;
        for (StrategyStats stats : sortedStats(statsByStrategy)) {
            StrategyDecision decision = decide(stats);
            String prefix = "strategy." + stats.strategy + ".";
            OutcomeSlice total = stats.totalSlice();
            OutcomeSlice validation = validationSlice(stats);
            WalkForwardSummary walkForward = walkForwardSummary(stats);
            p.setProperty(prefix + "trades", Integer.toString(total.trades));
            p.setProperty(prefix + "validationTrades", Integer.toString(validation.trades));
            p.setProperty(prefix + "walkForwardWindows", Integer.toString(walkForward.windows));
            p.setProperty(prefix + "walkForwardEligibleWindows", Integer.toString(walkForward.eligibleWindows));
            p.setProperty(prefix + "walkForwardPassingWindows", Integer.toString(walkForward.passingWindows));
            p.setProperty(prefix + "walkForwardWorstWindowPnlDollars", fmt(walkForward.worstWindowPnl));
            p.setProperty(prefix + "walkForwardWorstWindowDrawdownDollars", fmt(walkForward.worstWindowDrawdown));
            p.setProperty(prefix + "walkForwardStatus", walkForward.status());
            p.setProperty(prefix + "pnlDollars", fmt(total.pnl));
            p.setProperty(prefix + "validationPnlDollars", fmt(validation.pnl));
            p.setProperty(prefix + "winRate", fmt(total.winRate()));
            p.setProperty(prefix + "validationWinRate", fmt(validation.winRate()));
            p.setProperty(prefix + "profitFactor", fmt(total.profitFactor()));
            p.setProperty(prefix + "validationProfitFactor", fmt(validation.profitFactor()));
            p.setProperty(prefix + "expectancyDollars", fmt(total.expectancy()));
            p.setProperty(prefix + "validationExpectancyDollars", fmt(validation.expectancy()));
            p.setProperty(prefix + "maxDrawdownDollars", fmt(total.maxDrawdown));
            p.setProperty(prefix + "signals", Integer.toString(stats.actionableSignals));
            p.setProperty(prefix + "errors", Integer.toString(stats.evaluationErrors));
            p.setProperty(prefix + "decision", decision.decision);
            p.setProperty(prefix + "reason", decision.reason);
            p.setProperty("barSim.validationStatus." + stats.strategy, decision.validationStatus);

            if ("PROMOTE".equals(decision.decision)) {
                promoted++;
                p.setProperty("promotedStrategy." + stats.strategy, "true");
                p.setProperty("simulationStatus." + stats.strategy, "PASSED");
                p.setProperty("strategyMultiplier." + stats.strategy, fmt(decision.multiplier));
            } else if ("RISK_REDUCTION".equals(decision.decision)) {
                riskReduced++;
                p.setProperty("simulationStatus." + stats.strategy, "RISK_REDUCTION");
                p.setProperty("strategyMultiplier." + stats.strategy, fmt(decision.multiplier));
                if (disableFailedStrategies) {
                    p.setProperty("disabledStrategy." + stats.strategy, "true");
                }
            }
        }

        try {
            Path parent = policyPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(policyPath)) {
                p.store(out, "Bar-by-bar simulation policy. Positive increases require simulationStatus=PASSED.");
            }
        } catch (Exception e) {
            System.out.println("BAR-BY-BAR SIMULATION POLICY WRITE FAILED: " + e.getMessage());
        }
        return new PromotionSummary(promoted, riskReduced);
    }

    private void writeReport(Map<String, StrategyStats> statsByStrategy, PromotionSummary promotion, long startedMs,
                             long finishedMs, int files, int bars, int evaluations, int errors,
                             boolean evaluationLimitHit, int trades, int tradeRowsWritten, int strategies,
                             HistoricalNewsRepository.LoadedNews historicalNews, int newsContextBars,
                             FileSelectionPlan fileSelection, DataQualityAggregate replayQuality,
                             EvaluationBudget evaluationBudget,
                             List<CandidateWatchlistEntry> candidateWatchlist,
                             int candidateWatchlistRowsWritten) {
        StringBuilder b = new StringBuilder();
        b.append("POLYGON BAR-BY-BAR SIMULATION REPORT\n");
        b.append("mode=OFFLINE_NO_ORDERS\n");
        b.append("startedAt=").append(Instant.ofEpochMilli(startedMs)).append('\n');
        b.append("finishedAt=").append(Instant.ofEpochMilli(finishedMs)).append('\n');
        b.append("elapsedMs=").append(finishedMs - startedMs).append('\n');
        b.append("historicalDataRoot=").append(repository.root()).append('\n');
        b.append("historicalNewsRoot=").append(historicalNews.root).append('\n');
        b.append("historicalNewsFiles=").append(historicalNews.files).append('\n');
        b.append("historicalNewsRows=").append(historicalNews.rows).append('\n');
        b.append("newsContextBars=").append(newsContextBars).append('\n');
        b.append("newsMaxAgeMinutes=").append(maxNewsAgeMs / 60_000L).append('\n');
        b.append("executionCostPolicy=enabled=").append(executionCostPolicyEnabled)
                .append(" path=").append(System.getenv().getOrDefault("EXECUTION_COST_POLICY_PATH", "logs/execution_cost_policy.properties"))
                .append(" baseSlippageBps=").append(fmt(slippageBps))
                .append('\n');
        b.append("fileSelectionMode=").append(fileSelection.mode).append('\n');
        b.append("fileSelectionCandidates=").append(fileSelection.candidates).append('\n');
        b.append("fileSelectionLiquidityRanked=").append(fileSelection.liquidityRanked).append('\n');
        b.append("fileSelectionFallbackRanked=").append(fileSelection.fallbackRanked).append('\n');
        b.append("fileSelectionQualityRejected=").append(fileSelection.qualityRejected).append('\n');
        b.append("fileSelectionUsableCandidates=").append(fileSelection.files.size()).append('\n');
        b.append("fileSelectionSampleRows=").append(fileSelection.sampleRows).append('\n');
        b.append("fileSelectionMultiDayCoverage=enabled=").append(fileSelection.multiDayCoverageEnabled)
                .append(" minDaysPerTicker=").append(fileSelection.minDaysPerTicker)
                .append(" maxFilesPerTicker=").append(fileSelection.maxFilesPerTicker)
                .append(" plannedReplayFiles=").append(fileSelection.plannedReplayFiles)
                .append(" coverageReadyTickers=").append(fileSelection.coverageReadyTickers)
                .append('\n');
        b.append("fileSelectionScore=shareVolume*clampedAveragePrice minEffectivePrice=")
                .append(fmt(FILE_RANK_MIN_EFFECTIVE_PRICE))
                .append(" maxEffectivePrice=")
                .append(fmt(FILE_RANK_MAX_EFFECTIVE_PRICE))
                .append('\n');
        b.append("dataQuality=enabled=").append(dataQualityEnabled)
                .append(" requireTimestamp=").append(dataQualityRequireTimestamp)
                .append(" requireOhlc=").append(dataQualityRequireOhlc)
                .append(" minPrice=").append(fmt(minQualityPrice))
                .append(" maxPrice=").append(fmt(maxQualityPrice))
                .append(" minAcceptedRows=").append(minQualityAcceptedRows)
                .append(" minAcceptedRatio=").append(fmt(minQualityAcceptedRatio))
                .append(" maxIntrabarRange=").append(fmt(maxQualityIntrabarRange))
                .append(" maxRowVolume=").append(fmt(maxQualityRowVolume))
                .append(" maxRowDollars=").append(fmt(maxQualityRowDollarVolume))
                .append('\n');
        b.append("dataQualityReplay=files=").append(replayQuality.files)
                .append(" filesRejected=").append(replayQuality.rejectedFiles)
                .append(" rowsRaw=").append(replayQuality.rawRows)
                .append(" rowsAccepted=").append(replayQuality.acceptedRows)
                .append(" rowsRejected=").append(replayQuality.rejectedRows())
                .append(" missing=").append(replayQuality.missingRejects)
                .append(" ticker=").append(replayQuality.tickerRejects)
                .append(" timestamp=").append(replayQuality.timestampRejects)
                .append(" price=").append(replayQuality.priceRejects)
                .append(" volume=").append(replayQuality.volumeRejects)
                .append(" ohlc=").append(replayQuality.ohlcRejects)
                .append(" range=").append(replayQuality.rangeRejects)
                .append(" notional=").append(replayQuality.notionalRejects)
                .append('\n');
        b.append("filesProcessed=").append(files).append('\n');
        b.append("barsProcessed=").append(bars).append('\n');
        b.append("strategiesEvaluated=").append(strategies).append('\n');
        b.append("strategyEvaluations=").append(evaluations).append('\n');
        b.append("evaluationBudget=adaptive=").append(evaluationBudget.adaptiveEnabled)
                .append(" configuredMax=").append(evaluationBudget.configuredMaxEvaluations)
                .append(" effectiveMax=").append(evaluationBudget.effectiveMaxEvaluations)
                .append(" expanded=").append(evaluationBudget.expanded())
                .append(" targetCoverageTickers=").append(evaluationBudget.targetCoverageTickers)
                .append(" targetReplayFiles=").append(evaluationBudget.targetReplayFiles)
                .append(" coverageTargetReached=").append(files >= evaluationBudget.targetReplayFiles)
                .append(" estimatedBarsPerFile=").append(evaluationBudget.estimatedBarsPerFile)
                .append(" safetyMultiplier=").append(fmt(evaluationBudget.safetyMultiplier))
                .append(" requested=").append(evaluationBudget.requestedEvaluations)
                .append(" ceiling=").append(evaluationBudget.ceilingEvaluations)
                .append(" reason=").append(evaluationBudget.reason)
                .append('\n');
        b.append("evaluationErrors=").append(errors).append('\n');
        b.append("evaluationLimitHit=").append(evaluationLimitHit).append('\n');
        b.append("simulatedTrades=").append(trades).append('\n');
        b.append("tradeRowsWritten=").append(tradeRowsWritten).append('\n');
        b.append("promoted=").append(promotion.promoted).append('\n');
        b.append("riskReduced=").append(promotion.riskReduced).append('\n');
        b.append("candidateWatchlistEnabled=").append(candidateWatchlistEnabled).append('\n');
        b.append("candidateWatchlisted=").append(candidateWatchlist.size()).append('\n');
        b.append("candidateWatchlistRowsWritten=").append(candidateWatchlistRowsWritten).append('\n');
        b.append("policyPath=").append(policyPath).append('\n');
        b.append("tradesPath=").append(tradesPath).append('\n');
        b.append("candidateWatchlistPath=").append(candidateWatchlistPath).append('\n');
        b.append("assumptions=market entry at close with slippage, target/stop/trailing/max-hold exits, pessimistic same-bar target/stop=").append(pessimisticIntrabar).append('\n');
        b.append("thresholds=minTrades=").append(minPromotionTrades)
                .append(" minValidationTrades=").append(minValidationTrades)
                .append(" minTotalProfitFactor=").append(fmt(minTotalProfitFactor))
                .append(" minTotalExpectancyDollars=").append(fmt(minTotalExpectancy))
                .append(" minValidationProfitFactor=").append(fmt(minValidationProfitFactor))
                .append(" minValidationWinRate=").append(fmt(minValidationWinRate))
                .append(" minValidationExpectancyDollars=").append(fmt(minValidationExpectancy))
                .append(" walkForwardEnabled=").append(walkForwardValidationEnabled)
                .append(" minWalkForwardWindows=").append(minWalkForwardWindows)
                .append(" minWalkForwardPassingWindows=").append(minWalkForwardPassingWindows)
                .append(" minWalkForwardTradesPerWindow=").append(minWalkForwardTradesPerWindow)
                .append(" minWalkForwardProfitFactor=").append(fmt(minWalkForwardProfitFactor))
                .append(" minWalkForwardExpectancyDollars=").append(fmt(minWalkForwardExpectancy))
                .append(" walkForwardLossVetoEnabled=").append(walkForwardLossVetoEnabled)
                .append(" maxWalkForwardWindowLossDollars=").append(fmt(maxWalkForwardWindowLoss))
                .append(" maxWalkForwardWindowDrawdownDollars=").append(fmt(maxWalkForwardWindowDrawdown))
                .append('\n');
        b.append("topRankedFiles=").append(fileSelection.preview(fileRankPreviewCount)).append('\n');
        b.append('\n').append("CANDIDATE WATCHLIST\n");
        if (candidateWatchlist.isEmpty()) {
            b.append("- none\n");
        } else {
            for (CandidateWatchlistEntry candidate : candidateWatchlist) {
                b.append("- ").append(candidate.strategy)
                        .append(" status=").append(candidate.status)
                        .append(" gate=").append(candidate.gate)
                        .append(" retestAction=").append(candidate.retestAction)
                        .append(" priorityScore=").append(fmt(candidate.priorityScore))
                        .append(" trades=").append(candidate.trades)
                        .append(" pnl=").append(fmt(candidate.pnl))
                        .append(" pf=").append(fmt(candidate.profitFactor))
                        .append(" validationTrades=").append(candidate.validationTrades)
                        .append(" validationPnl=").append(fmt(candidate.validationPnl))
                        .append(" validationPf=").append(fmt(candidate.validationProfitFactor))
                        .append(" validationExpectancy=").append(fmt(candidate.validationExpectancy))
                        .append(" walkForwardPassing=").append(candidate.walkForwardPassingWindows)
                        .append("/")
                        .append(candidate.walkForwardEligibleWindows)
                        .append(" worstPnl=").append(fmt(candidate.walkForwardWorstPnl))
                        .append(" worstDrawdown=").append(fmt(candidate.walkForwardWorstDrawdown))
                        .append(" reason=").append(candidate.reason)
                        .append('\n');
            }
        }
        b.append('\n').append("STRATEGIES\n");
        for (StrategyStats stats : sortedStats(statsByStrategy)) {
            OutcomeSlice total = stats.totalSlice();
            OutcomeSlice validation = validationSlice(stats);
            WalkForwardSummary walkForward = walkForwardSummary(stats);
            StrategyDecision decision = decide(stats);
            b.append("- ").append(stats.strategy)
                    .append(" decision=").append(decision.decision)
                    .append(" multiplier=").append(fmt(decision.multiplier))
                    .append(" trades=").append(total.trades)
                    .append(" pnl=").append(fmt(total.pnl))
                    .append(" pf=").append(fmt(total.profitFactor()))
                    .append(" winRate=").append(fmt(total.winRate()))
                    .append(" expectancy=").append(fmt(total.expectancy()))
                    .append(" validationTrades=").append(validation.trades)
                    .append(" validationPnl=").append(fmt(validation.pnl))
                    .append(" validationPf=").append(fmt(validation.profitFactor()))
                    .append(" validationWinRate=").append(fmt(validation.winRate()))
                    .append(" validationExpectancy=").append(fmt(validation.expectancy()))
                    .append(" walkForwardWindows=").append(walkForward.windows)
                    .append(" walkForwardEligible=").append(walkForward.eligibleWindows)
                    .append(" walkForwardPassing=").append(walkForward.passingWindows)
                    .append(" walkForwardWorstPnl=").append(fmt(walkForward.worstWindowPnl))
                    .append(" walkForwardWorstDrawdown=").append(fmt(walkForward.worstWindowDrawdown))
                    .append(" walkForwardStatus=").append(walkForward.status())
                    .append(" signals=").append(stats.actionableSignals)
                    .append(" errors=").append(stats.evaluationErrors)
                    .append(" reason=").append(decision.reason)
                    .append('\n');
        }

        try {
            Path parent = reportPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(reportPath, b.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("BAR-BY-BAR SIMULATION REPORT WRITE FAILED: " + e.getMessage());
        }
    }

    private StrategyDecision decide(StrategyStats stats) {
        OutcomeSlice total = stats.totalSlice();
        OutcomeSlice validation = validationSlice(stats);
        WalkForwardSummary walkForward = walkForwardSummary(stats);
        if (!promotionSampleReady(total, validation)) {
            return StrategyDecision.hold(1.0, "INSUFFICIENT_DATA",
                    "Need more bar-replay samples before changing live sizing.");
        }
        boolean totalPass = totalPromotionPass(total);
        boolean validationPass = validationPromotionPass(validation);
        if (totalPass && validationPass) {
            if (!walkForward.passed()) {
                String reason = "WALK_FORWARD_LOSS_VETO".equals(walkForward.status())
                        ? "Full sample and chronological validation passed, but a walk-forward window exceeded the loss or drawdown veto."
                        : "Full sample and chronological validation passed, but separate walk-forward day windows did not prove repeatable profit.";
                return StrategyDecision.hold(1.0, walkForward.status(),
                        reason);
            }
            double boost = 0.02
                    + Math.max(0.0, validation.avgPnlPercent()) * 2.0
                    + Math.max(0.0, validation.profitFactor() - minValidationProfitFactor) * 0.025
                    + Math.max(0.0, validation.winRate() - minValidationWinRate) * 0.10;
            double multiplier = 1.0 + Math.min(maxPromotionBoost, boost);
            return StrategyDecision.promote(multiplier,
                    "Full sample, chronological validation, and walk-forward day windows passed.");
        }
        if (validationPass) {
            return StrategyDecision.hold(1.0, "TOTAL_SAMPLE_FAILED",
                    "Validation passed, but full-sample expectancy/profit factor did not clear promotion thresholds.");
        }

        boolean weak = validation.pnl < 0.0
                || validation.expectancy() < 0.0
                || validation.profitFactor() < 0.90;
        if (weak) {
            double multiplier = validation.profitFactor() < 0.70 || validation.expectancy() < -1.0 ? 0.70 : 0.85;
            return StrategyDecision.riskReduction(multiplier,
                    "Chronological validation failed with negative or weak expectancy.");
        }
        return StrategyDecision.hold(1.0, "FAILED",
                "Validation did not clear promotion thresholds, but risk reduction was not severe enough.");
    }

    private List<CandidateWatchlistEntry> candidateWatchlist(Map<String, StrategyStats> statsByStrategy) {
        if (!candidateWatchlistEnabled) {
            return List.of();
        }
        List<CandidateWatchlistEntry> candidates = new ArrayList<>();
        for (StrategyStats stats : statsByStrategy.values()) {
            CandidateWatchlistEntry candidate = candidateWatchlistEntry(stats);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.comparingDouble((CandidateWatchlistEntry c) -> c.priorityScore).reversed()
                .thenComparing(c -> c.strategy));
        if (candidates.size() <= candidateWatchlistMaxEntries) {
            return candidates;
        }
        return new ArrayList<>(candidates.subList(0, candidateWatchlistMaxEntries));
    }

    private CandidateWatchlistEntry candidateWatchlistEntry(StrategyStats stats) {
        OutcomeSlice total = stats.totalSlice();
        OutcomeSlice validation = validationSlice(stats);
        WalkForwardSummary walkForward = walkForwardSummary(stats);
        if (!promotionSampleReady(total, validation)
                || !totalPromotionPass(total)
                || !validationPromotionPass(validation)
                || walkForward.passed()) {
            return null;
        }
        StrategyDecision decision = decide(stats);
        double priorityScore = candidatePriorityScore(total, validation, walkForward);
        return new CandidateWatchlistEntry(
                Instant.now().toString(),
                stats.strategy,
                "WATCHLIST",
                walkForward.status(),
                "RETEST_AFTER_MORE_HISTORICAL_AND_PAPER_SAMPLES",
                priorityScore,
                total.trades,
                total.pnl,
                total.profitFactor(),
                total.winRate(),
                total.expectancy(),
                validation.trades,
                validation.pnl,
                validation.profitFactor(),
                validation.winRate(),
                validation.expectancy(),
                walkForward.windows,
                walkForward.eligibleWindows,
                walkForward.passingWindows,
                walkForward.worstWindowPnl,
                walkForward.worstWindowDrawdown,
                decision.reason);
    }

    private double candidatePriorityScore(OutcomeSlice total, OutcomeSlice validation, WalkForwardSummary walkForward) {
        double edgeScore = Math.max(0.0, validation.expectancy()) * 10.0
                + Math.max(0.0, validation.profitFactor() - 1.0) * 20.0
                + Math.max(0.0, validation.winRate() - minValidationWinRate) * 25.0
                + Math.max(0.0, total.expectancy()) * 2.0;
        double repeatabilityScore = walkForward.eligibleWindows == 0
                ? 0.0
                : (walkForward.passingWindows * 1.0 / walkForward.eligibleWindows) * 10.0;
        double riskPenalty = (Math.max(0.0, -walkForward.worstWindowPnl)
                + Math.max(0.0, walkForward.worstWindowDrawdown))
                / Math.max(1.0, accountEquity) * 100.0;
        return edgeScore + repeatabilityScore - riskPenalty;
    }

    private boolean promotionSampleReady(OutcomeSlice total, OutcomeSlice validation) {
        return total.trades >= minPromotionTrades && validation.trades >= minValidationTrades;
    }

    private boolean totalPromotionPass(OutcomeSlice total) {
        return total.pnl > 0.0
                && total.expectancy() >= minTotalExpectancy
                && total.profitFactor() >= minTotalProfitFactor;
    }

    private boolean validationPromotionPass(OutcomeSlice validation) {
        return validation.pnl > 0.0
                && validation.expectancy() >= minValidationExpectancy
                && validation.profitFactor() >= minValidationProfitFactor
                && validation.winRate() >= minValidationWinRate;
    }

    private OutcomeSlice validationSlice(StrategyStats stats) {
        return OutcomeSlice.from(validationTrades(stats));
    }

    private List<TradeRecord> validationTrades(StrategyStats stats) {
        List<TradeRecord> trades = new ArrayList<>(stats.trades);
        trades.sort(Comparator.comparingLong(t -> t.exitTimestamp));
        if (trades.isEmpty()) {
            return List.of();
        }
        int split = Math.max(0, Math.min(trades.size() - 1, (int) Math.floor(trades.size() * trainFraction)));
        return new ArrayList<>(trades.subList(split, trades.size()));
    }

    private WalkForwardSummary walkForwardSummary(StrategyStats stats) {
        if (!walkForwardValidationEnabled) {
            return WalkForwardSummary.disabled();
        }
        List<TradeRecord> trades = validationTrades(stats);
        if (trades.isEmpty()) {
            return new WalkForwardSummary(0, 0, 0, 0.0, 0.0, "WALK_FORWARD_NO_TRADES");
        }

        Map<LocalDate, List<TradeRecord>> byDay = new LinkedHashMap<>();
        for (TradeRecord trade : trades) {
            if (trade == null) {
                continue;
            }
            LocalDate day = Instant.ofEpochMilli(trade.exitTimestamp).atZone(MARKET_ZONE).toLocalDate();
            byDay.computeIfAbsent(day, ignored -> new ArrayList<>()).add(trade);
        }

        int eligible = 0;
        int passing = 0;
        double worstPnl = 0.0;
        double worstDrawdown = 0.0;
        boolean hasEligible = false;
        for (List<TradeRecord> dayTrades : byDay.values()) {
            OutcomeSlice day = OutcomeSlice.from(dayTrades);
            if (day.trades < minWalkForwardTradesPerWindow) {
                continue;
            }
            eligible++;
            if (!hasEligible) {
                worstPnl = day.pnl;
                worstDrawdown = day.maxDrawdown;
                hasEligible = true;
            } else {
                worstPnl = Math.min(worstPnl, day.pnl);
                worstDrawdown = Math.max(worstDrawdown, day.maxDrawdown);
            }
            if (day.pnl > 0.0
                    && day.expectancy() >= minWalkForwardExpectancy
                    && day.profitFactor() >= minWalkForwardProfitFactor) {
                passing++;
            }
        }

        String status;
        if (byDay.size() < minWalkForwardWindows) {
            status = "WALK_FORWARD_INSUFFICIENT_WINDOWS";
        } else if (eligible < minWalkForwardWindows) {
            status = "WALK_FORWARD_INSUFFICIENT_ELIGIBLE_WINDOWS";
        } else if (passing < minWalkForwardPassingWindows) {
            status = "WALK_FORWARD_FAILED";
        } else if (walkForwardLossVetoEnabled
                && (worstPnl < -maxWalkForwardWindowLoss
                || worstDrawdown > maxWalkForwardWindowDrawdown)) {
            status = "WALK_FORWARD_LOSS_VETO";
        } else {
            status = "PASSED";
        }
        return new WalkForwardSummary(byDay.size(), eligible, passing, hasEligible ? worstPnl : 0.0,
                hasEligible ? worstDrawdown : 0.0, status);
    }

    private List<TradeRecord> allTrades(Map<String, StrategyStats> statsByStrategy) {
        List<TradeRecord> out = new ArrayList<>();
        for (StrategyStats stats : statsByStrategy.values()) {
            out.addAll(stats.trades);
        }
        return out;
    }

    private List<StrategyStats> sortedStats(Map<String, StrategyStats> statsByStrategy) {
        List<StrategyStats> out = new ArrayList<>(statsByStrategy.values());
        out.sort(Comparator
                .comparing((StrategyStats s) -> decide(s).rank).reversed()
                .thenComparing((StrategyStats s) -> s.totalSlice().pnl, Comparator.reverseOrder())
                .thenComparing(s -> s.strategy));
        return out;
    }

    private List<TradingStrategy> selectedStrategies(List<TradingStrategy> all) {
        Set<String> allow = strategySet(env("BAR_SIM_STRATEGIES", ""));
        Set<String> exclude = strategySet(env("BAR_SIM_EXCLUDED_STRATEGIES", ""));
        int maxStrategies = Math.max(1, envInt("BAR_SIM_MAX_STRATEGIES", 500));
        List<TradingStrategy> selected = new ArrayList<>();
        for (TradingStrategy strategy : all) {
            if (strategy == null || strategy.name() == null || strategy.name().isBlank()) {
                continue;
            }
            String name = normalizeStrategy(strategy.name());
            if (!allow.isEmpty() && !allow.contains(name)) {
                continue;
            }
            if (exclude.contains(name)) {
                continue;
            }
            selected.add(strategy);
            if (selected.size() >= maxStrategies) {
                break;
            }
        }
        return selected;
    }

    private EvaluationBudget evaluationBudget(FileSelectionPlan fileSelection, int strategyCount) {
        int plannedFiles = fileSelection == null ? 0 : Math.max(0, fileSelection.plannedReplayFiles);
        int normalizedStrategyCount = Math.max(1, strategyCount);
        if (plannedFiles <= 0) {
            return new EvaluationBudget(adaptiveEvaluationBudgetEnabled, maxEvaluations, maxEvaluations,
                    adaptiveEvaluationTargetCoverageTickers, 0, adaptiveEvaluationEstimatedBarsPerFile,
                    adaptiveEvaluationSafetyMultiplier, maxEvaluations, maxEvaluations, "NO_REPLAY_FILES");
        }

        int targetCoverageTickers = fileSelection.coverageReadyTickers <= 0
                ? adaptiveEvaluationTargetCoverageTickers
                : Math.min(adaptiveEvaluationTargetCoverageTickers, fileSelection.coverageReadyTickers);
        int coverageTargetFiles = filesNeededForCoverageReadyTickers(fileSelection.files, plannedFiles,
                targetCoverageTickers);
        int targetReplayFiles = Math.max(adaptiveEvaluationMinTargetFiles, coverageTargetFiles);
        targetReplayFiles = Math.max(1, Math.min(plannedFiles, targetReplayFiles));

        long requested = (long) Math.ceil(targetReplayFiles
                * (double) adaptiveEvaluationEstimatedBarsPerFile
                * normalizedStrategyCount
                * adaptiveEvaluationSafetyMultiplier);
        long multiplierCeiling = (long) Math.ceil(maxEvaluations * adaptiveEvaluationMaxMultiplier);
        long ceiling = Math.max(maxEvaluations, Math.min((long) adaptiveEvaluationAbsoluteMax, multiplierCeiling));
        long effective = maxEvaluations;
        String reason = "FIXED_CONFIGURED_LIMIT";
        if (adaptiveEvaluationBudgetEnabled) {
            effective = Math.max((long) maxEvaluations, Math.min(requested, ceiling));
            if (effective > maxEvaluations && requested > ceiling) {
                reason = "EXPANDED_TO_CEILING_FOR_MULTI_DAY_COVERAGE";
            } else if (effective > maxEvaluations) {
                reason = "EXPANDED_FOR_MULTI_DAY_COVERAGE";
            } else {
                reason = "CONFIGURED_LIMIT_ALREADY_SUFFICIENT";
            }
        }

        return new EvaluationBudget(adaptiveEvaluationBudgetEnabled, maxEvaluations, boundedInt(effective),
                targetCoverageTickers, targetReplayFiles, adaptiveEvaluationEstimatedBarsPerFile,
                adaptiveEvaluationSafetyMultiplier, requested, boundedInt(ceiling), reason);
    }

    private int filesNeededForCoverageReadyTickers(List<FileRank> ordered, int plannedReplayFiles,
                                                   int targetCoverageTickers) {
        if (ordered == null || ordered.isEmpty() || plannedReplayFiles <= 0 || targetCoverageTickers <= 0) {
            return 0;
        }
        Map<String, Set<LocalDate>> datesByTicker = new LinkedHashMap<>();
        Set<String> readyTickers = new HashSet<>();
        int limit = Math.min(plannedReplayFiles, ordered.size());
        for (int i = 0; i < limit; i++) {
            FileRank rank = ordered.get(i);
            if (rank == null) {
                continue;
            }
            String ticker = rank.ticker.isBlank() ? inferTickerFromFile(rank.path) : rank.ticker;
            if (ticker.isBlank()) {
                continue;
            }
            Set<LocalDate> dates = datesByTicker.computeIfAbsent(ticker, ignored -> new LinkedHashSet<>());
            dates.addAll(rank.coverageDates);
            if (dates.size() >= minSelectionDaysPerTicker) {
                readyTickers.add(ticker);
                if (readyTickers.size() >= targetCoverageTickers) {
                    return i + 1;
                }
            }
        }
        return limit;
    }

    private Set<String> strategySet(String raw) {
        Set<String> out = new HashSet<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String normalized = normalizeStrategy(part);
            if (!normalized.isBlank()) {
                out.add(normalized);
            }
        }
        return out;
    }

    private FileSelectionPlan selectFiles(List<Path> discoveredFiles) {
        List<Path> files = discoveredFiles == null ? List.of() : discoveredFiles;
        if (files.isEmpty()) {
            return new FileSelectionPlan("EMPTY", 0, 0, 0, 0, Math.min(fileRankSampleRows, maxRowsPerFile),
                    List.of(), false, minSelectionDaysPerTicker, maxSelectionFilesPerTicker, 0, 0);
        }
        int sampleRows = Math.max(10, Math.min(fileRankSampleRows, maxRowsPerFile));
        if (!prioritizeHighVolumeFiles) {
            List<FileRank> unchanged = new ArrayList<>();
            int liquidityRanked = 0;
            int fallbackRanked = 0;
            int qualityRejected = 0;
            for (int i = 0; i < files.size(); i++) {
                FileRank rank = rankFile(files.get(i), i, sampleRows);
                if (!rank.qualityPass) {
                    qualityRejected++;
                    continue;
                }
                if (rank.hasLiquidity) {
                    liquidityRanked++;
                } else {
                    fallbackRanked++;
                }
                unchanged.add(rank);
            }
            int plannedReplayFiles = Math.min(maxFiles, unchanged.size());
            return new FileSelectionPlan("REPOSITORY_ORDER", files.size(), liquidityRanked, fallbackRanked,
                    qualityRejected, sampleRows, unchanged, false, minSelectionDaysPerTicker,
                    maxSelectionFilesPerTicker, plannedReplayFiles,
                    coverageReadyTickerCount(unchanged, plannedReplayFiles));
        }

        List<FileRank> ranked = new ArrayList<>();
        int liquidityRanked = 0;
        int fallbackRanked = 0;
        int qualityRejected = 0;
        for (int i = 0; i < files.size(); i++) {
            FileRank rank = rankFile(files.get(i), i, sampleRows);
            if (!rank.qualityPass) {
                qualityRejected++;
                continue;
            }
            if (rank.hasLiquidity) {
                liquidityRanked++;
            } else {
                fallbackRanked++;
            }
            ranked.add(rank);
        }
        ranked.sort(Comparator
                .comparing((FileRank r) -> r.hasLiquidity).reversed()
                .thenComparing((FileRank r) -> r.liquidityScore, Comparator.reverseOrder())
                .thenComparing((FileRank r) -> r.shareVolume, Comparator.reverseOrder())
                .thenComparing((FileRank r) -> r.dollarVolume, Comparator.reverseOrder())
                .thenComparing((FileRank r) -> r.latestDateEpochDay, Comparator.reverseOrder())
                .thenComparing(r -> r.path.toString()));
        boolean multiDayCoverage = multiDaySelectionEnabled && ranked.size() > 1;
        List<FileRank> ordered = multiDayCoverage ? multiDayCoverageOrder(ranked) : ranked;
        int plannedReplayFiles = Math.min(maxFiles, ordered.size());
        return new FileSelectionPlan(multiDayCoverage ? "HIGH_VOLUME_MULTI_DAY" : "HIGH_VOLUME_FIRST",
                files.size(), liquidityRanked, fallbackRanked, qualityRejected, sampleRows, ordered,
                multiDayCoverage, minSelectionDaysPerTicker, maxSelectionFilesPerTicker, plannedReplayFiles,
                coverageReadyTickerCount(ordered, plannedReplayFiles));
    }

    private int defaultMinSelectionDaysPerTicker() {
        int requiredValidationDays = Math.max(minWalkForwardWindows, minWalkForwardPassingWindows);
        double validationFraction = Math.max(0.05, 1.0 - trainFraction);
        return Math.max(requiredValidationDays + 1, (int) Math.ceil(requiredValidationDays / validationFraction));
    }

    private List<FileRank> multiDayCoverageOrder(List<FileRank> ranked) {
        Map<String, List<FileRank>> byTicker = new LinkedHashMap<>();
        for (FileRank rank : ranked) {
            String ticker = rank.ticker.isBlank() ? inferTickerFromFile(rank.path) : rank.ticker;
            String key = ticker.isBlank() ? "UNKNOWN|" + rank.sequence : ticker;
            byTicker.computeIfAbsent(key, ignored -> new ArrayList<>()).add(rank);
        }

        List<FileRank> ordered = new ArrayList<>();
        Set<Path> used = new HashSet<>();
        for (List<FileRank> tickerFiles : byTicker.values()) {
            for (FileRank rank : coverageBundle(tickerFiles)) {
                if (rank.path != null && used.add(rank.path)) {
                    ordered.add(rank);
                }
            }
        }
        for (FileRank rank : ranked) {
            if (rank.path != null && used.add(rank.path)) {
                ordered.add(rank);
            }
        }
        return ordered;
    }

    private List<FileRank> coverageBundle(List<FileRank> tickerFiles) {
        if (tickerFiles == null || tickerFiles.isEmpty()) {
            return List.of();
        }
        List<FileRank> remaining = new ArrayList<>(tickerFiles);
        List<FileRank> selected = new ArrayList<>();
        Set<LocalDate> covered = new LinkedHashSet<>();
        while (!remaining.isEmpty()
                && selected.size() < maxSelectionFilesPerTicker
                && (selected.isEmpty() || covered.size() < minSelectionDaysPerTicker)) {
            FileRank best = null;
            int bestNewDays = -1;
            for (FileRank rank : remaining) {
                int newDays = countNewCoverageDays(rank.coverageDates, covered);
                if (best == null || newDays > bestNewDays) {
                    best = rank;
                    bestNewDays = newDays;
                }
            }
            if (best == null) {
                break;
            }
            if (!selected.isEmpty() && bestNewDays <= 0) {
                break;
            }
            selected.add(best);
            covered.addAll(best.coverageDates);
            remaining.remove(best);
        }
        return selected;
    }

    private int countNewCoverageDays(List<LocalDate> dates, Set<LocalDate> covered) {
        if (dates == null || dates.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (LocalDate date : dates) {
            if (date != null && (covered == null || !covered.contains(date))) {
                count++;
            }
        }
        return count;
    }

    private int coverageReadyTickerCount(List<FileRank> ordered, int plannedReplayFiles) {
        if (ordered == null || ordered.isEmpty() || plannedReplayFiles <= 0) {
            return 0;
        }
        Map<String, Set<LocalDate>> datesByTicker = new LinkedHashMap<>();
        int limit = Math.min(plannedReplayFiles, ordered.size());
        for (int i = 0; i < limit; i++) {
            FileRank rank = ordered.get(i);
            if (rank == null) {
                continue;
            }
            String ticker = rank.ticker.isBlank() ? inferTickerFromFile(rank.path) : rank.ticker;
            if (ticker.isBlank()) {
                continue;
            }
            datesByTicker.computeIfAbsent(ticker, ignored -> new LinkedHashSet<>()).addAll(rank.coverageDates);
        }
        int ready = 0;
        for (Set<LocalDate> dates : datesByTicker.values()) {
            if (dates.size() >= minSelectionDaysPerTicker) {
                ready++;
            }
        }
        return ready;
    }

    private FileRank rankFile(Path file, int sequence, int sampleRows) {
        QualityBars quality = qualifyBars(repository.loadBars(file, sampleRows));
        List<HistoricalMarketDataRepository.HistoricalBar> bars = quality.accepted;
        if (bars.isEmpty()) {
            return FileRank.rejected(file, sequence, latestDateScore(file), quality);
        }
        double shareVolume = 0.0;
        double dollarVolume = 0.0;
        double priceSum = 0.0;
        int priceRows = 0;
        String ticker = "";
        for (HistoricalMarketDataRepository.HistoricalBar bar : bars) {
            if (bar == null) {
                continue;
            }
            if (ticker.isBlank()) {
                ticker = normalizeTicker(bar.ticker);
            }
            double close = positive(bar.close, 0.0);
            double volume = Math.max(0.0, bar.volume);
            shareVolume += volume;
            dollarVolume += volume * close;
            if (close > 0.0) {
                priceSum += close;
                priceRows++;
            }
        }
        if (ticker.isBlank()) {
            ticker = inferTickerFromFile(file);
        }
        double avgPrice = priceRows == 0 ? 0.0 : priceSum / priceRows;
        boolean hasLiquidity = shareVolume > 0.0 || dollarVolume > 0.0;
        double liquidityScore = hasLiquidity
                ? shareVolume * clamp(avgPrice, FILE_RANK_MIN_EFFECTIVE_PRICE, FILE_RANK_MAX_EFFECTIVE_PRICE)
                : 0.0;
        return new FileRank(file, ticker, quality.rawRows, bars.size(), quality.rejectedRows(), shareVolume,
                dollarVolume, avgPrice, liquidityScore, latestDateScore(file), coverageDates(file, bars), hasLiquidity,
                quality.usableForRanking(minQualityAcceptedRows), sequence);
    }

    private List<LocalDate> coverageDates(Path file, List<HistoricalMarketDataRepository.HistoricalBar> bars) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        if (bars != null) {
            for (HistoricalMarketDataRepository.HistoricalBar bar : bars) {
                if (bar == null) {
                    continue;
                }
                LocalDate date = parseBarDate(bar.timestamp);
                if (isLikelyMarketDay(date)) {
                    dates.add(date);
                }
            }
        }
        dates.addAll(fileNameCoverageDates(file));
        List<LocalDate> out = new ArrayList<>(dates);
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private LocalDate parseBarDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            if (value.matches("^-?\\d+$")) {
                long n = Long.parseLong(value);
                long millis;
                if (n > 100_000_000_000_000_000L) {
                    millis = n / 1_000_000L;
                } else if (n > 100_000_000_000_000L) {
                    millis = n / 1_000L;
                } else if (n > 100_000_000_000L) {
                    millis = n;
                } else {
                    millis = n * 1_000L;
                }
                return Instant.ofEpochMilli(millis).atZone(MARKET_ZONE).toLocalDate();
            }
        } catch (Exception ignored) {
        }
        try {
            return Instant.parse(value).atZone(MARKET_ZONE).toLocalDate();
        } catch (Exception ignored) {
        }
        for (DateTimeFormatter formatter : dateTimeFormatters()) {
            try {
                return LocalDateTime.parse(value.replace(' ', 'T'), formatter).toLocalDate();
            } catch (Exception ignored) {
            }
        }
        for (DateTimeFormatter formatter : dateFormatters()) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static List<LocalDate> fileNameCoverageDates(Path file) {
        if (file == null || file.getFileName() == null) {
            return List.of();
        }
        Set<LocalDate> parsed = new LinkedHashSet<>();
        Matcher matcher = DATE_IN_FILE_NAME.matcher(file.getFileName().toString());
        while (matcher.find()) {
            try {
                LocalDate date = LocalDate.parse(matcher.group(1));
                parsed.add(date);
            } catch (Exception ignored) {
            }
        }
        if (parsed.isEmpty()) {
            return List.of();
        }
        List<LocalDate> dates = new ArrayList<>(parsed);
        dates.sort(Comparator.naturalOrder());
        if (dates.size() < 2) {
            return isLikelyMarketDay(dates.get(0)) ? dates : List.of();
        }
        LocalDate start = dates.get(0);
        LocalDate end = dates.get(dates.size() - 1);
        if (end.isBefore(start) || start.plusDays(31).isBefore(end)) {
            List<LocalDate> marketDates = new ArrayList<>();
            for (LocalDate date : dates) {
                if (isLikelyMarketDay(date)) {
                    marketDates.add(date);
                }
            }
            return marketDates;
        }
        List<LocalDate> expanded = new ArrayList<>();
        LocalDate cursor = start;
        while (!cursor.isAfter(end)) {
            if (isLikelyMarketDay(cursor)) {
                expanded.add(cursor);
            }
            cursor = cursor.plusDays(1);
        }
        return expanded;
    }

    private static boolean isLikelyMarketDay(LocalDate date) {
        if (date == null) {
            return false;
        }
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    private long latestDateScore(Path file) {
        if (file == null || file.getFileName() == null) {
            return 0L;
        }
        Matcher matcher = DATE_IN_FILE_NAME.matcher(file.getFileName().toString());
        long latest = 0L;
        while (matcher.find()) {
            try {
                latest = Math.max(latest, LocalDate.parse(matcher.group(1)).toEpochDay());
            } catch (Exception ignored) {
            }
        }
        return latest;
    }

    private static String inferTickerFromFile(Path file) {
        if (file == null || file.getFileName() == null) {
            return "";
        }
        String name = file.getFileName().toString();
        int dot = name.indexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        int dateStart = name.indexOf("_20");
        if (dateStart > 0) {
            name = name.substring(0, dateStart);
        }
        return normalizeTicker(name.replaceAll("[^A-Za-z0-9.-]", ""));
    }

    private QualityBars qualifyBars(List<HistoricalMarketDataRepository.HistoricalBar> bars) {
        QualityBars quality = new QualityBars();
        if (bars == null || bars.isEmpty()) {
            return quality;
        }
        for (HistoricalMarketDataRepository.HistoricalBar bar : bars) {
            quality.rawRows++;
            String rejection = dataQualityEnabled ? qualityRejectionReason(bar) : null;
            if (rejection == null) {
                quality.accept(bar);
            } else {
                quality.reject(rejection);
            }
        }
        return quality;
    }

    private String qualityRejectionReason(HistoricalMarketDataRepository.HistoricalBar bar) {
        if (bar == null) {
            return "missing";
        }
        if (normalizeTicker(bar.ticker).isBlank()) {
            return "ticker";
        }
        if (dataQualityRequireTimestamp && (bar.timestamp == null || bar.timestamp.isBlank())) {
            return "timestamp";
        }

        double close = bar.close;
        if (!Double.isFinite(close) || close < minQualityPrice || close > maxQualityPrice) {
            return "price";
        }

        double open = bar.open;
        double high = bar.high;
        double low = bar.low;
        if (dataQualityRequireOhlc && (open <= 0.0 || high <= 0.0 || low <= 0.0)) {
            return "ohlc";
        }
        if (componentPriceBad(open) || componentPriceBad(high) || componentPriceBad(low)) {
            return "price";
        }
        if (high > 0.0 && low > 0.0) {
            if (high < low) {
                return "ohlc";
            }
            if (low > 0.0 && high / low > maxQualityIntrabarRange) {
                return "range";
            }
        }

        double referenceOpen = positive(open, close);
        if (high > 0.0 && high < Math.max(referenceOpen, close) * 0.995) {
            return "ohlc";
        }
        if (low > 0.0 && low > Math.min(referenceOpen, close) * 1.005) {
            return "ohlc";
        }

        double volume = bar.volume;
        if (!Double.isFinite(volume) || volume < 0.0 || volume > maxQualityRowVolume) {
            return "volume";
        }
        double notional = close * volume;
        if (!Double.isFinite(notional) || notional > maxQualityRowDollarVolume) {
            return "notional";
        }
        return null;
    }

    private boolean componentPriceBad(double price) {
        if (price <= 0.0) {
            return false;
        }
        return !Double.isFinite(price)
                || price < minQualityPrice * 0.25
                || price > maxQualityPrice * 1.25;
    }

    private boolean isTradable(StrategySignal signal) {
        if (signal == null || !signal.isActionableBuy()) {
            return false;
        }
        return signal.getDirection() == TradeDirection.LONG_STOCK
                || signal.getDirection() == TradeDirection.SHORT_STOCK;
    }

    private int quantityFor(StrategySignal signal, double price) {
        if (signal == null || price <= 0.0) {
            return 0;
        }
        int quantity = fixedQuantity > 0 ? fixedQuantity : signal.getSuggestedQuantity();
        quantity = Math.max(1, Math.min(maxQuantity, quantity));
        double maxNotional = accountEquity * maxNotionalFraction;
        if (price * quantity > maxNotional) {
            quantity = (int) Math.floor(maxNotional / price);
        }
        return Math.max(0, quantity);
    }

    private double targetMove(StrategySignal signal) {
        double expected = signal == null ? 0.0 : Math.abs(signal.getExpectedMovePercent());
        if (expected > 1.0) {
            expected = expected / 100.0;
        }
        return clamp(expected, minTargetMove, maxTargetMove);
    }

    private double executionSlippageBps(String ticker, String strategy) {
        if (!executionCostPolicyEnabled) {
            return slippageBps;
        }
        return executionCostModel.oneWaySlippageBps(ticker, strategy, slippageBps);
    }

    private Bar toBar(HistoricalMarketDataRepository.HistoricalBar historical, long previousTimestamp) {
        Bar bar = new Bar();
        bar.ticker = normalizeTicker(historical.ticker);
        bar.timestamp = parseTimestamp(historical.timestamp, previousTimestamp);
        bar.close = positive(historical.close, 0.0);
        bar.open = positive(historical.open, bar.close);
        bar.high = positive(historical.high, Math.max(bar.open, bar.close));
        bar.low = positive(historical.low, Math.min(bar.open, bar.close));
        if (bar.high < Math.max(bar.open, bar.close)) {
            bar.high = Math.max(bar.open, bar.close);
        }
        if (bar.low <= 0.0 || bar.low > Math.min(bar.open, bar.close)) {
            bar.low = Math.min(bar.open, bar.close);
        }
        bar.volume = Math.max(0L, Math.round(historical.volume));
        return bar;
    }

    private long parseTimestamp(String raw, long previousTimestamp) {
        long fallback = previousTimestamp > 0L ? previousTimestamp + 60_000L : System.currentTimeMillis();
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim();
        try {
            if (value.matches("^-?\\d+$")) {
                long n = Long.parseLong(value);
                if (n > 100_000_000_000_000_000L) {
                    return monotonic(n / 1_000_000L, previousTimestamp);
                }
                if (n > 100_000_000_000_000L) {
                    return monotonic(n / 1_000L, previousTimestamp);
                }
                if (n > 100_000_000_000L) {
                    return monotonic(n, previousTimestamp);
                }
                return monotonic(n * 1_000L, previousTimestamp);
            }
        } catch (Exception ignored) {
        }
        try {
            return monotonic(Instant.parse(value).toEpochMilli(), previousTimestamp);
        } catch (Exception ignored) {
        }
        for (DateTimeFormatter formatter : dateTimeFormatters()) {
            try {
                return monotonic(LocalDateTime.parse(value.replace(' ', 'T'), formatter)
                        .atZone(MARKET_ZONE).toInstant().toEpochMilli(), previousTimestamp);
            } catch (Exception ignored) {
            }
        }
        for (DateTimeFormatter formatter : dateFormatters()) {
            try {
                return monotonic(LocalDate.parse(value, formatter).atStartOfDay(MARKET_ZONE).toInstant().toEpochMilli(), previousTimestamp);
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }

    private List<DateTimeFormatter> dateTimeFormatters() {
        return List.of(
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
                DateTimeFormatter.ofPattern("MM/dd/yyyy'T'HH:mm:ss"),
                DateTimeFormatter.ofPattern("MM/dd/yyyy'T'HH:mm")
        );
    }

    private List<DateTimeFormatter> dateFormatters() {
        return List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("MM/dd/yyyy")
        );
    }

    private long monotonic(long timestamp, long previousTimestamp) {
        if (previousTimestamp > 0L && timestamp <= previousTimestamp) {
            return previousTimestamp + 1L;
        }
        return timestamp;
    }

    private static double adjustedEntryPrice(double close, TradeDirection direction, double slippageBps) {
        double slip = Math.max(0.0, slippageBps) / 10_000.0;
        if (direction == TradeDirection.SHORT_STOCK) {
            return close * (1.0 - slip);
        }
        return close * (1.0 + slip);
    }

    private static double adjustExitPrice(double price, TradeDirection direction, double slippageBps) {
        double slip = Math.max(0.0, slippageBps) / 10_000.0;
        if (direction == TradeDirection.SHORT_STOCK) {
            return price * (1.0 + slip);
        }
        return price * (1.0 - slip);
    }

    private static String positionKey(String strategy, String ticker) {
        return normalizeStrategy(strategy) + "|" + normalizeTicker(ticker);
    }

    private static String normalizeStrategy(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String normalizeTicker(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static int boundedInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.6f", Double.isFinite(value) ? value : 0.0);
    }

    private static String formatCoverageDates(List<LocalDate> dates) {
        if (dates == null || dates.isEmpty()) {
            return "";
        }
        if (dates.size() > 6) {
            return dates.get(0) + ".." + dates.get(dates.size() - 1) + "(" + dates.size() + "d)";
        }
        StringBuilder b = new StringBuilder();
        for (LocalDate date : dates) {
            if (date == null) {
                continue;
            }
            if (!b.isEmpty()) {
                b.append('|');
            }
            b.append(date);
        }
        return b.toString();
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static String env(String key, String fallback) {
        String value = configuredValue(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = configuredValue(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = configuredValue(key);
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = configuredValue(key);
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean envBool(String key, boolean fallback) {
        String value = configuredValue(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("on");
    }

    private static String configuredValue(String key) {
        String property = System.getProperty(key);
        if (property != null && !property.isBlank()) {
            return property.trim();
        }
        String value = System.getenv(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static final class Result {
        public final int files;
        public final int bars;
        public final int evaluations;
        public final int trades;
        public final int promoted;
        public final int riskReduced;
        public final int watchlisted;
        public final Path policyPath;
        public final Path reportPath;
        public final Path tradesPath;
        public final Path candidateWatchlistPath;
        public final long elapsedMs;

        Result(int files, int bars, int evaluations, int trades, int promoted, int riskReduced, int watchlisted,
               Path policyPath, Path reportPath, Path tradesPath, Path candidateWatchlistPath, long elapsedMs) {
            this.files = files;
            this.bars = bars;
            this.evaluations = evaluations;
            this.trades = trades;
            this.promoted = promoted;
            this.riskReduced = riskReduced;
            this.watchlisted = watchlisted;
            this.policyPath = policyPath;
            this.reportPath = reportPath;
            this.tradesPath = tradesPath;
            this.candidateWatchlistPath = candidateWatchlistPath;
            this.elapsedMs = elapsedMs;
        }

        public String summary() {
            return "files=" + files +
                    " bars=" + bars +
                    " evaluations=" + evaluations +
                    " trades=" + trades +
                    " promoted=" + promoted +
                    " riskReduced=" + riskReduced +
                    " watchlisted=" + watchlisted +
                    " policy=" + policyPath +
                    " report=" + reportPath +
                    " watchlist=" + candidateWatchlistPath +
                    " elapsedMs=" + elapsedMs;
        }
    }

    private static final class EvaluationBudget {
        final boolean adaptiveEnabled;
        final int configuredMaxEvaluations;
        final int effectiveMaxEvaluations;
        final int targetCoverageTickers;
        final int targetReplayFiles;
        final int estimatedBarsPerFile;
        final double safetyMultiplier;
        final long requestedEvaluations;
        final int ceilingEvaluations;
        final String reason;

        EvaluationBudget(boolean adaptiveEnabled, int configuredMaxEvaluations, int effectiveMaxEvaluations,
                         int targetCoverageTickers, int targetReplayFiles, int estimatedBarsPerFile,
                         double safetyMultiplier, long requestedEvaluations, int ceilingEvaluations,
                         String reason) {
            this.adaptiveEnabled = adaptiveEnabled;
            this.configuredMaxEvaluations = configuredMaxEvaluations;
            this.effectiveMaxEvaluations = effectiveMaxEvaluations;
            this.targetCoverageTickers = targetCoverageTickers;
            this.targetReplayFiles = targetReplayFiles;
            this.estimatedBarsPerFile = estimatedBarsPerFile;
            this.safetyMultiplier = safetyMultiplier;
            this.requestedEvaluations = requestedEvaluations;
            this.ceilingEvaluations = ceilingEvaluations;
            this.reason = reason == null ? "" : reason;
        }

        boolean expanded() {
            return effectiveMaxEvaluations > configuredMaxEvaluations;
        }
    }

    private static final class PromotionSummary {
        final int promoted;
        final int riskReduced;

        PromotionSummary(int promoted, int riskReduced) {
            this.promoted = promoted;
            this.riskReduced = riskReduced;
        }
    }

    private static final class WalkForwardSummary {
        final int windows;
        final int eligibleWindows;
        final int passingWindows;
        final double worstWindowPnl;
        final double worstWindowDrawdown;
        final String status;

        WalkForwardSummary(int windows, int eligibleWindows, int passingWindows, double worstWindowPnl,
                           double worstWindowDrawdown, String status) {
            this.windows = windows;
            this.eligibleWindows = eligibleWindows;
            this.passingWindows = passingWindows;
            this.worstWindowPnl = worstWindowPnl;
            this.worstWindowDrawdown = worstWindowDrawdown;
            this.status = status == null ? "FAILED" : status;
        }

        static WalkForwardSummary disabled() {
            return new WalkForwardSummary(0, 0, 0, 0.0, 0.0, "DISABLED");
        }

        boolean passed() {
            return "PASSED".equals(status) || "DISABLED".equals(status);
        }

        String status() {
            return status;
        }
    }

    private static final class CandidateWatchlistEntry {
        final String updatedAt;
        final String strategy;
        final String status;
        final String gate;
        final String retestAction;
        final double priorityScore;
        final int trades;
        final double pnl;
        final double profitFactor;
        final double winRate;
        final double expectancy;
        final int validationTrades;
        final double validationPnl;
        final double validationProfitFactor;
        final double validationWinRate;
        final double validationExpectancy;
        final int walkForwardWindows;
        final int walkForwardEligibleWindows;
        final int walkForwardPassingWindows;
        final double walkForwardWorstPnl;
        final double walkForwardWorstDrawdown;
        final String reason;

        CandidateWatchlistEntry(String updatedAt,
                                String strategy,
                                String status,
                                String gate,
                                String retestAction,
                                double priorityScore,
                                int trades,
                                double pnl,
                                double profitFactor,
                                double winRate,
                                double expectancy,
                                int validationTrades,
                                double validationPnl,
                                double validationProfitFactor,
                                double validationWinRate,
                                double validationExpectancy,
                                int walkForwardWindows,
                                int walkForwardEligibleWindows,
                                int walkForwardPassingWindows,
                                double walkForwardWorstPnl,
                                double walkForwardWorstDrawdown,
                                String reason) {
            this.updatedAt = updatedAt == null ? "" : updatedAt;
            this.strategy = strategy == null ? "" : strategy;
            this.status = status == null ? "" : status;
            this.gate = gate == null ? "" : gate;
            this.retestAction = retestAction == null ? "" : retestAction;
            this.priorityScore = priorityScore;
            this.trades = trades;
            this.pnl = pnl;
            this.profitFactor = profitFactor;
            this.winRate = winRate;
            this.expectancy = expectancy;
            this.validationTrades = validationTrades;
            this.validationPnl = validationPnl;
            this.validationProfitFactor = validationProfitFactor;
            this.validationWinRate = validationWinRate;
            this.validationExpectancy = validationExpectancy;
            this.walkForwardWindows = walkForwardWindows;
            this.walkForwardEligibleWindows = walkForwardEligibleWindows;
            this.walkForwardPassingWindows = walkForwardPassingWindows;
            this.walkForwardWorstPnl = walkForwardWorstPnl;
            this.walkForwardWorstDrawdown = walkForwardWorstDrawdown;
            this.reason = reason == null ? "" : reason;
        }

        String toPolicyValue() {
            return "strategy=" + strategy +
                    ",status=" + status +
                    ",gate=" + gate +
                    ",retestAction=" + retestAction +
                    ",priorityScore=" + fmt(priorityScore) +
                    ",trades=" + trades +
                    ",pnlDollars=" + fmt(pnl) +
                    ",validationTrades=" + validationTrades +
                    ",validationPnlDollars=" + fmt(validationPnl) +
                    ",validationProfitFactor=" + fmt(validationProfitFactor) +
                    ",walkForwardPassing=" + walkForwardPassingWindows +
                    "/" + walkForwardEligibleWindows +
                    ",walkForwardWorstPnlDollars=" + fmt(walkForwardWorstPnl) +
                    ",walkForwardWorstDrawdownDollars=" + fmt(walkForwardWorstDrawdown);
        }

        String toCsvLine() {
            return csv(updatedAt) +
                    "," + csv(strategy) +
                    "," + csv(status) +
                    "," + csv(gate) +
                    "," + csv(retestAction) +
                    "," + fmt(priorityScore) +
                    "," + trades +
                    "," + fmt(pnl) +
                    "," + fmt(profitFactor) +
                    "," + fmt(winRate) +
                    "," + fmt(expectancy) +
                    "," + validationTrades +
                    "," + fmt(validationPnl) +
                    "," + fmt(validationProfitFactor) +
                    "," + fmt(validationWinRate) +
                    "," + fmt(validationExpectancy) +
                    "," + walkForwardWindows +
                    "," + walkForwardEligibleWindows +
                    "," + walkForwardPassingWindows +
                    "," + fmt(walkForwardWorstPnl) +
                    "," + fmt(walkForwardWorstDrawdown) +
                    "," + csv(reason);
        }
    }

    private final class QualityBars {
        final List<HistoricalMarketDataRepository.HistoricalBar> accepted = new ArrayList<>();
        int rawRows;
        int missingRejects;
        int tickerRejects;
        int timestampRejects;
        int priceRejects;
        int volumeRejects;
        int ohlcRejects;
        int rangeRejects;
        int notionalRejects;

        void accept(HistoricalMarketDataRepository.HistoricalBar bar) {
            if (bar != null) {
                accepted.add(bar);
            }
        }

        void reject(String reason) {
            String normalized = reason == null ? "" : reason;
            switch (normalized) {
                case "ticker" -> tickerRejects++;
                case "timestamp" -> timestampRejects++;
                case "price" -> priceRejects++;
                case "volume" -> volumeRejects++;
                case "ohlc" -> ohlcRejects++;
                case "range" -> rangeRejects++;
                case "notional" -> notionalRejects++;
                default -> missingRejects++;
            }
        }

        int acceptedRows() {
            return accepted.size();
        }

        int rejectedRows() {
            return Math.max(0, rawRows - acceptedRows());
        }

        boolean usableForRanking(int requestedMinRows) {
            return usable(requestedMinRows);
        }

        boolean usableForReplay(int requestedMinRows) {
            return usable(Math.max(requestedMinRows, minQualityAcceptedRows));
        }

        private boolean usable(int requestedMinRows) {
            if (!dataQualityEnabled) {
                return acceptedRows() > 0;
            }
            if (rawRows <= 0) {
                return false;
            }
            int minRows = Math.max(1, requestedMinRows);
            double ratio = acceptedRows() * 1.0 / rawRows;
            return acceptedRows() >= minRows && ratio >= minQualityAcceptedRatio;
        }
    }

    private final class DataQualityAggregate {
        int files;
        int rejectedFiles;
        int rawRows;
        int acceptedRows;
        int missingRejects;
        int tickerRejects;
        int timestampRejects;
        int priceRejects;
        int volumeRejects;
        int ohlcRejects;
        int rangeRejects;
        int notionalRejects;

        void record(QualityBars quality) {
            if (quality == null) {
                return;
            }
            files++;
            rawRows += quality.rawRows;
            acceptedRows += quality.acceptedRows();
            missingRejects += quality.missingRejects;
            tickerRejects += quality.tickerRejects;
            timestampRejects += quality.timestampRejects;
            priceRejects += quality.priceRejects;
            volumeRejects += quality.volumeRejects;
            ohlcRejects += quality.ohlcRejects;
            rangeRejects += quality.rangeRejects;
            notionalRejects += quality.notionalRejects;
            if (!quality.usableForReplay(Math.max(2, warmupBars))) {
                rejectedFiles++;
            }
        }

        int rejectedRows() {
            return Math.max(0, rawRows - acceptedRows);
        }
    }

    private static final class FileSelectionPlan {
        final String mode;
        final int candidates;
        final int liquidityRanked;
        final int fallbackRanked;
        final int qualityRejected;
        final int sampleRows;
        final List<FileRank> files;
        final boolean multiDayCoverageEnabled;
        final int minDaysPerTicker;
        final int maxFilesPerTicker;
        final int plannedReplayFiles;
        final int coverageReadyTickers;

        FileSelectionPlan(String mode, int candidates, int liquidityRanked, int fallbackRanked, int qualityRejected,
                          int sampleRows, List<FileRank> files, boolean multiDayCoverageEnabled,
                          int minDaysPerTicker, int maxFilesPerTicker, int plannedReplayFiles,
                          int coverageReadyTickers) {
            this.mode = mode;
            this.candidates = candidates;
            this.liquidityRanked = liquidityRanked;
            this.fallbackRanked = fallbackRanked;
            this.qualityRejected = qualityRejected;
            this.sampleRows = sampleRows;
            this.files = files == null ? List.of() : files;
            this.multiDayCoverageEnabled = multiDayCoverageEnabled;
            this.minDaysPerTicker = minDaysPerTicker;
            this.maxFilesPerTicker = maxFilesPerTicker;
            this.plannedReplayFiles = plannedReplayFiles;
            this.coverageReadyTickers = coverageReadyTickers;
        }

        String preview(int limit) {
            if (files.isEmpty()) {
                return "none";
            }
            StringBuilder b = new StringBuilder();
            int max = Math.max(1, Math.min(limit, files.size()));
            for (int i = 0; i < max; i++) {
                if (i > 0) {
                    b.append(" | ");
                }
                b.append(i + 1).append(':').append(files.get(i).toShortText());
            }
            return b.toString();
        }
    }

    private static final class FileRank {
        final Path path;
        final String ticker;
        final int rawRows;
        final int sampledRows;
        final int rejectedRows;
        final double shareVolume;
        final double dollarVolume;
        final double avgPrice;
        final double liquidityScore;
        final long latestDateEpochDay;
        final List<LocalDate> coverageDates;
        final boolean hasLiquidity;
        final boolean qualityPass;
        final int sequence;

        FileRank(Path path, String ticker, int rawRows, int sampledRows, int rejectedRows, double shareVolume,
                 double dollarVolume, double avgPrice, double liquidityScore, long latestDateEpochDay,
                 List<LocalDate> coverageDates, boolean hasLiquidity, boolean qualityPass, int sequence) {
            this.path = path;
            this.ticker = ticker == null ? "" : ticker;
            this.rawRows = rawRows;
            this.sampledRows = sampledRows;
            this.rejectedRows = rejectedRows;
            this.shareVolume = shareVolume;
            this.dollarVolume = dollarVolume;
            this.avgPrice = avgPrice;
            this.liquidityScore = liquidityScore;
            this.latestDateEpochDay = latestDateEpochDay;
            this.coverageDates = coverageDates == null ? List.of() : List.copyOf(coverageDates);
            this.hasLiquidity = hasLiquidity;
            this.qualityPass = qualityPass;
            this.sequence = sequence;
        }

        static FileRank fallback(Path path, int sequence, long latestDateEpochDay) {
            return new FileRank(path, inferTickerFromFile(path), 0, 0, 0, 0.0, 0.0, 0.0, 0.0,
                    latestDateEpochDay, fileNameCoverageDates(path), false, true, sequence);
        }

        static FileRank rejected(Path path, int sequence, long latestDateEpochDay, QualityBars quality) {
            int raw = quality == null ? 0 : quality.rawRows;
            int rejected = quality == null ? 0 : quality.rejectedRows();
            return new FileRank(path, inferTickerFromFile(path), raw, 0, rejected, 0.0, 0.0, 0.0, 0.0,
                    latestDateEpochDay, fileNameCoverageDates(path), false, false, sequence);
        }

        String toPolicyValue() {
            return "ticker=" + ticker +
                    ",rawRows=" + rawRows +
                    ",acceptedRows=" + sampledRows +
                    ",rejectedRows=" + rejectedRows +
                    ",liquidityScore=" + fmt(liquidityScore) +
                    ",shareVolume=" + fmt(shareVolume) +
                    ",dollarVolume=" + fmt(dollarVolume) +
                    ",avgPrice=" + fmt(avgPrice) +
                    ",coverageDays=" + coverageDates.size() +
                    ",coverageDates=" + formatCoverageDates(coverageDates) +
                    ",file=" + (path == null ? "" : path);
        }

        String toShortText() {
            String label = ticker.isBlank() ? "UNKNOWN" : ticker;
            return label +
                    " rows=" + sampledRows +
                    " rejected=" + rejectedRows +
                    " score=" + fmt(liquidityScore) +
                    " shares=" + fmt(shareVolume) +
                    " dollars=" + fmt(dollarVolume) +
                    " days=" + coverageDates.size() +
                    " dates=" + formatCoverageDates(coverageDates) +
                    " file=" + (path == null || path.getFileName() == null ? "" : path.getFileName());
        }
    }

    private static final class StrategyDecision {
        final String decision;
        final String validationStatus;
        final double multiplier;
        final String reason;
        final int rank;

        private StrategyDecision(String decision, String validationStatus, double multiplier, String reason, int rank) {
            this.decision = decision;
            this.validationStatus = validationStatus;
            this.multiplier = multiplier;
            this.reason = reason;
            this.rank = rank;
        }

        static StrategyDecision promote(double multiplier, String reason) {
            return new StrategyDecision("PROMOTE", "PASSED", clamp(multiplier, 1.0, 1.50), reason, 3);
        }

        static StrategyDecision riskReduction(double multiplier, String reason) {
            return new StrategyDecision("RISK_REDUCTION", "FAILED", clamp(multiplier, 0.50, 1.0), reason, 1);
        }

        static StrategyDecision hold(double multiplier, String validationStatus, String reason) {
            return new StrategyDecision("HOLD", validationStatus, multiplier, reason, 2);
        }
    }

    private static final class StrategyStats {
        final String strategy;
        final List<TradeRecord> trades = new ArrayList<>();
        int actionableSignals = 0;
        int evaluationErrors = 0;

        StrategyStats(String strategy) {
            this.strategy = strategy;
        }

        void record(TradeRecord trade) {
            if (trade != null) {
                trades.add(trade);
            }
        }

        OutcomeSlice totalSlice() {
            return OutcomeSlice.from(trades);
        }
    }

    private static final class OutcomeSlice {
        final int trades;
        final int wins;
        final double pnl;
        final double grossProfit;
        final double grossLoss;
        final double maxDrawdown;
        final double avgPnlPercent;

        private OutcomeSlice(int trades, int wins, double pnl, double grossProfit, double grossLoss,
                             double maxDrawdown, double avgPnlPercent) {
            this.trades = trades;
            this.wins = wins;
            this.pnl = pnl;
            this.grossProfit = grossProfit;
            this.grossLoss = grossLoss;
            this.maxDrawdown = maxDrawdown;
            this.avgPnlPercent = avgPnlPercent;
        }

        static OutcomeSlice empty() {
            return new OutcomeSlice(0, 0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        static OutcomeSlice from(List<TradeRecord> trades) {
            if (trades == null || trades.isEmpty()) {
                return empty();
            }
            int count = 0;
            int wins = 0;
            double pnl = 0.0;
            double grossProfit = 0.0;
            double grossLoss = 0.0;
            double equity = 0.0;
            double peak = 0.0;
            double maxDrawdown = 0.0;
            double pnlPct = 0.0;
            for (TradeRecord trade : trades) {
                if (trade == null) {
                    continue;
                }
                count++;
                pnl += trade.pnlDollars;
                pnlPct += trade.pnlPercent;
                if (trade.pnlDollars > 0.0) {
                    wins++;
                    grossProfit += trade.pnlDollars;
                } else {
                    grossLoss += trade.pnlDollars;
                }
                equity += trade.pnlDollars;
                peak = Math.max(peak, equity);
                maxDrawdown = Math.max(maxDrawdown, peak - equity);
            }
            return new OutcomeSlice(count, wins, pnl, grossProfit, grossLoss, maxDrawdown,
                    count == 0 ? 0.0 : pnlPct / count);
        }

        double winRate() {
            return trades == 0 ? 0.0 : wins * 1.0 / trades;
        }

        double expectancy() {
            return trades == 0 ? 0.0 : pnl / trades;
        }

        double profitFactor() {
            if (grossLoss < 0.0) {
                return grossProfit / Math.abs(grossLoss);
            }
            return grossProfit > 0.0 ? 999.0 : 0.0;
        }

        double avgPnlPercent() {
            return avgPnlPercent;
        }
    }

    private static final class OpenPosition {
        final String strategy;
        final String ticker;
        final TradeDirection direction;
        final int quantity;
        final double entryPrice;
        final long entryTimestamp;
        final int entryBarIndex;
        final double targetMove;
        final double stopMove;
        final double confidence;
        final double expectedMove;
        final String newsSource;
        final String newsHeadline;
        final double newsAgeMinutes;
        final double catalystScore;
        final Path sourceFile;
        double highest;
        double lowest;

        private OpenPosition(String strategy, String ticker, TradeDirection direction, int quantity,
                             double entryPrice, long entryTimestamp, int entryBarIndex, double targetMove,
                             double stopMove, double confidence, double expectedMove, String newsSource,
                             String newsHeadline, double newsAgeMinutes, double catalystScore, Path sourceFile) {
            this.strategy = strategy;
            this.ticker = ticker;
            this.direction = direction;
            this.quantity = quantity;
            this.entryPrice = entryPrice;
            this.entryTimestamp = entryTimestamp;
            this.entryBarIndex = entryBarIndex;
            this.targetMove = targetMove;
            this.stopMove = stopMove;
            this.confidence = confidence;
            this.expectedMove = expectedMove;
            this.newsSource = newsSource == null ? "" : newsSource;
            this.newsHeadline = newsHeadline == null ? "" : newsHeadline;
            this.newsAgeMinutes = newsAgeMinutes;
            this.catalystScore = catalystScore;
            this.sourceFile = sourceFile;
            this.highest = entryPrice;
            this.lowest = entryPrice;
        }

        static OpenPosition open(StrategySignal signal, String strategyName, Bar bar, int barIndex, int quantity,
                                 double targetMove, double stopMove, double slippageBps, NewsEvent news) {
            double entry = adjustedEntryPrice(bar.close, signal.getDirection(), slippageBps);
            double ageMinutes = news == null || news.getTimestamp() <= 0L
                    ? 0.0
                    : Math.max(0.0, (bar.timestamp - news.getTimestamp()) / 60_000.0);
            return new OpenPosition(strategyName, normalizeTicker(signal.getTicker()), signal.getDirection(), quantity,
                    entry, bar.timestamp, barIndex, targetMove, stopMove, signal.getConfidence(),
                    signal.getExpectedMovePercent(),
                    news == null ? "" : news.getSource(),
                    news == null ? "" : news.getHeadline(),
                    ageMinutes,
                    news == null ? 0.0 : news.getCatalystScore(),
                    null);
        }

        ExitDecision exitDecision(Bar bar, int barIndex, boolean pessimisticIntrabar, int maxHoldBars,
                                  double trailingStartMove, double trailingGivebackMove, double slippageBps) {
            if (barIndex <= entryBarIndex) {
                return null;
            }
            highest = Math.max(highest, positive(bar.high, bar.close));
            lowest = Math.min(lowest, positive(bar.low, bar.close));
            int barsHeld = Math.max(0, barIndex - entryBarIndex);

            if (direction == TradeDirection.SHORT_STOCK) {
                double target = entryPrice * (1.0 - targetMove);
                double stop = entryPrice * (1.0 + stopMove);
                boolean hitTarget = positive(bar.low, bar.close) <= target;
                boolean hitStop = positive(bar.high, bar.close) >= stop;
                if (hitTarget && hitStop) {
                    return ExitDecision.of(adjustExitPrice(pessimisticIntrabar ? stop : target, direction, slippageBps),
                            pessimisticIntrabar ? "STOP_AND_TARGET_SAME_BAR" : "TARGET_AND_STOP_SAME_BAR");
                }
                if (hitTarget) {
                    return ExitDecision.of(adjustExitPrice(target, direction, slippageBps), "TARGET");
                }
                if (hitStop) {
                    return ExitDecision.of(adjustExitPrice(stop, direction, slippageBps), "STOP");
                }
                if (lowest <= entryPrice * (1.0 - trailingStartMove)) {
                    double trailingStop = lowest * (1.0 + trailingGivebackMove);
                    if (positive(bar.high, bar.close) >= trailingStop) {
                        return ExitDecision.of(adjustExitPrice(trailingStop, direction, slippageBps), "TRAILING_STOP");
                    }
                }
            } else {
                double target = entryPrice * (1.0 + targetMove);
                double stop = entryPrice * (1.0 - stopMove);
                boolean hitTarget = positive(bar.high, bar.close) >= target;
                boolean hitStop = positive(bar.low, bar.close) <= stop;
                if (hitTarget && hitStop) {
                    return ExitDecision.of(adjustExitPrice(pessimisticIntrabar ? stop : target, direction, slippageBps),
                            pessimisticIntrabar ? "STOP_AND_TARGET_SAME_BAR" : "TARGET_AND_STOP_SAME_BAR");
                }
                if (hitTarget) {
                    return ExitDecision.of(adjustExitPrice(target, direction, slippageBps), "TARGET");
                }
                if (hitStop) {
                    return ExitDecision.of(adjustExitPrice(stop, direction, slippageBps), "STOP");
                }
                if (highest >= entryPrice * (1.0 + trailingStartMove)) {
                    double trailingStop = highest * (1.0 - trailingGivebackMove);
                    if (positive(bar.low, bar.close) <= trailingStop) {
                        return ExitDecision.of(adjustExitPrice(trailingStop, direction, slippageBps), "TRAILING_STOP");
                    }
                }
            }

            if (barsHeld >= maxHoldBars) {
                return ExitDecision.of(adjustExitPrice(bar.close, direction, slippageBps), "MAX_HOLD");
            }
            return null;
        }

        TradeRecord close(ExitDecision exit, Bar bar, int barIndex, Path file, double feePerShare) {
            int barsHeld = Math.max(0, barIndex - entryBarIndex);
            double gross = direction == TradeDirection.SHORT_STOCK
                    ? (entryPrice - exit.exitPrice) * quantity
                    : (exit.exitPrice - entryPrice) * quantity;
            double fees = Math.max(0.0, feePerShare) * quantity * 2.0;
            double pnl = gross - fees;
            double denominator = entryPrice * Math.max(1, quantity);
            double pnlPercent = denominator <= 0.0 ? 0.0 : pnl / denominator;
            return new TradeRecord(entryTimestamp, bar.timestamp, ticker, strategy, direction, quantity,
                    entryPrice, exit.exitPrice, pnl, pnlPercent, barsHeld, exit.reason,
                    confidence, expectedMove, newsSource, newsHeadline, newsAgeMinutes, catalystScore,
                    file == null ? sourceFile : file);
        }
    }

    private static final class ExitDecision {
        final double exitPrice;
        final String reason;

        private ExitDecision(double exitPrice, String reason) {
            this.exitPrice = exitPrice;
            this.reason = reason;
        }

        static ExitDecision of(double exitPrice, String reason) {
            return new ExitDecision(exitPrice, reason);
        }
    }

    private static final class TradeRecord {
        final long entryTimestamp;
        final long exitTimestamp;
        final String ticker;
        final String strategy;
        final TradeDirection direction;
        final int quantity;
        final double entryPrice;
        final double exitPrice;
        final double pnlDollars;
        final double pnlPercent;
        final int barsHeld;
        final String exitReason;
        final double confidence;
        final double expectedMove;
        final String newsSource;
        final String newsHeadline;
        final double newsAgeMinutes;
        final double catalystScore;
        final Path file;

        private TradeRecord(long entryTimestamp, long exitTimestamp, String ticker, String strategy,
                            TradeDirection direction, int quantity, double entryPrice, double exitPrice,
                            double pnlDollars, double pnlPercent, int barsHeld, String exitReason,
                            double confidence, double expectedMove, String newsSource, String newsHeadline,
                            double newsAgeMinutes, double catalystScore, Path file) {
            this.entryTimestamp = entryTimestamp;
            this.exitTimestamp = exitTimestamp;
            this.ticker = ticker;
            this.strategy = strategy;
            this.direction = direction;
            this.quantity = quantity;
            this.entryPrice = entryPrice;
            this.exitPrice = exitPrice;
            this.pnlDollars = pnlDollars;
            this.pnlPercent = pnlPercent;
            this.barsHeld = barsHeld;
            this.exitReason = exitReason;
            this.confidence = confidence;
            this.expectedMove = expectedMove;
            this.newsSource = newsSource == null ? "" : newsSource;
            this.newsHeadline = newsHeadline == null ? "" : newsHeadline;
            this.newsAgeMinutes = newsAgeMinutes;
            this.catalystScore = catalystScore;
            this.file = file;
        }

        String toCsvLine() {
            return csv(Instant.ofEpochMilli(entryTimestamp).toString()) +
                    "," + csv(Instant.ofEpochMilli(exitTimestamp).toString()) +
                    "," + csv(ticker) +
                    "," + csv(strategy) +
                    "," + csv(direction == null ? "" : direction.name()) +
                    "," + quantity +
                    "," + fmt(entryPrice) +
                    "," + fmt(exitPrice) +
                    "," + fmt(pnlDollars) +
                    "," + fmt(pnlPercent) +
                    "," + barsHeld +
                    "," + csv(exitReason) +
                    "," + fmt(confidence) +
                    "," + fmt(expectedMove) +
                    "," + csv(newsSource) +
                    "," + fmt(newsAgeMinutes) +
                    "," + fmt(catalystScore) +
                    "," + csv(newsHeadline) +
                    "," + csv(file == null ? "" : file.toString());
        }
    }
}
