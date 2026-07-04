package com.bot.agents;

import com.bot.master.StrategyContext;
import com.bot.master.StrategySignal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

public class MultiAgentDecisionJournal {
    private final Path path;

    public MultiAgentDecisionJournal() {
        this(Path.of(System.getenv().getOrDefault("MULTI_AGENT_DECISION_JOURNAL_PATH", "logs/multi_agent_decisions.csv")));
    }

    public MultiAgentDecisionJournal(Path path) {
        this.path = path;
        ensureHeader();
    }

    public synchronized void record(StrategyContext context, StrategySignal candidate, MultiAgentTradeDecision decision) {
        if (!"true".equalsIgnoreCase(System.getenv().getOrDefault("MULTI_AGENT_DECISION_JOURNAL_ENABLED", "true"))) {
            return;
        }
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            String ticker = context == null ? "" : context.getTicker();
            String strategy = candidate == null ? "" : candidate.getStrategyName();
            String action = decision != null && decision.isApproved() ? "APPROVED" : "VETOED";
            String opinions = compactOpinions(decision == null ? null : decision.getOpinions());
            String line = csv(Instant.now().toString()) + "," +
                    csv(ticker) + "," +
                    csv(strategy) + "," +
                    csv(action) + "," +
                    (candidate == null ? 0.0 : candidate.getConfidence()) + "," +
                    (candidate == null ? 0 : candidate.getSuggestedQuantity()) + "," +
                    (decision == null ? 0 : decision.getApprovedQuantity()) + "," +
                    csv(decision == null ? "" : decision.getReason()) + "," +
                    csv(opinions) + "\n";
            Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.out.println("MULTI AGENT JOURNAL WARNING: " + e.getMessage());
        }
    }

    private void ensureHeader() {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            if (!Files.exists(path) || Files.size(path) == 0) {
                Files.writeString(path,
                        "timestamp,ticker,strategy,committeeAction,candidateConfidence,candidateQty,approvedQty,reason,agentOpinions\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }

    private static String compactOpinions(List<AgentOpinion> opinions) {
        if (opinions == null || opinions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < opinions.size(); i++) {
            AgentOpinion opinion = opinions.get(i);
            if (opinion == null) continue;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(opinion.getAgentName()).append(":").append(opinion.getVote()).append(":")
                    .append(String.format("%.2f", opinion.getConfidence())).append(":")
                    .append(opinion.getReason());
        }
        return sb.toString();
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }
}
