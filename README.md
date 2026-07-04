# Project Atlas

**AI-powered quantitative trading research platform focused on real-time news, sentiment, momentum, liquidity, and adaptive strategy improvement.**

Project Atlas is an experimental trading intelligence system built to identify high-momentum opportunities, score real-time catalysts, manage risk, and improve strategy behavior through post-market performance analysis.

> **Status:** Active development  
> **Focus:** AI-driven market analysis, news momentum, risk management, and autonomous strategy refinement

---

## What Project Atlas Does

Project Atlas is designed around one central idea:

**Find liquid, fast-moving market opportunities, enter with conviction, manage risk aggressively, and learn from every trading day.**

The platform combines:

- Real-time news scanning
- AI sentiment and catalyst analysis
- Momentum and liquidity scoring
- Volume-first trade prioritization
- Long and short opportunity detection
- Risk controls and exit logic
- Trade journaling and performance review
- Strategy refinement based on historical outcomes

---

## Core Features

### Real-Time Market Intelligence

Project Atlas monitors market-moving news and attempts to determine whether a catalyst is relevant, timely, and strong enough to justify action.

Key areas:

- Breaking news detection
- Ticker relevance filtering
- Catalyst classification
- Sentiment scoring
- Stale news rejection
- Market confirmation

### AI-Driven Opportunity Scoring

The system ranks opportunities using multiple signals, with priority given to:

1. Volume
2. Liquidity
3. Violent price movement
4. Catalyst strength
5. Real-time confirmation
6. Risk/reward quality

Secondary indicators can support a trade, but the platform is designed to prioritize momentum and liquidity above everything else.

### Risk Management

Project Atlas is built with defensive risk controls, including:

- Position sizing logic
- Stop-loss and trailing-exit behavior
- Maximum hold-time concepts
- Short trade exit logic
- Trade journaling
- Post-trade analysis

### Continuous Improvement

A major goal of the platform is nightly self-improvement:

- Review the day’s trades
- Identify strategy failures
- Improve scoring logic
- Refine entries and exits
- Strengthen future trade selection

---

## Technology Stack

- **Java**
- **Maven**
- **Alpaca API**
- **ONNX Runtime**
- **FinBERT / sentiment analysis**
- **REST APIs**
- **WebSocket market data**
- **JSON processing**
- **AI-assisted strategy evaluation**

---

## Architecture Overview

Project Atlas is organized around several major components:

```text
Market Data + News Feeds
        ↓
News / Catalyst Scanner
        ↓
Ticker Relevance + Sentiment Analysis
        ↓
Momentum + Liquidity Scoring
        ↓
Risk Engine + Position Sizing
        ↓
Order Execution / Dry Run
        ↓
Trade Journal + Performance Database
        ↓
Nightly Strategy Review
```

---

## Development Goals

Current development priorities include:

- Improve real-time catalyst classification
- Strengthen volume-first opportunity scoring
- Improve short-side exit behavior
- Tighten stale-news rejection
- Improve trade attribution by strategy
- Build better logs and dashboards
- Expand performance analytics
- Continue refining autonomous post-market review

---

## Disclaimer

Project Atlas is an experimental software engineering and research project.

It is **not financial advice**, not a trading recommendation system, and not intended to guarantee trading performance. Any trading activity involves substantial risk.

---

## About the Developer

Built by **Michael Bennett**, a full-stack software developer and founder of **Axios LLC**, focused on software engineering, AI systems, automation, DevOps, and data-driven applications.

- GitHub: [MichaelBennett87](https://github.com/MichaelBennett87)
- Portfolio: [https://michaelbennett87.github.io](https://michaelbennett87.github.io)
