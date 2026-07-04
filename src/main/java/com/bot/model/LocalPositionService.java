package com.bot.model;

import com.bot.risk.PositionService;

import java.util.Collection;
import java.util.Map;

public class LocalPositionService implements PositionService {

    private final Map<String, Position> positions;

    public LocalPositionService(Map<String, Position> positions) {
        this.positions = positions;
    }

    @Override
    public boolean hasPosition(String ticker) {
        return positions.containsKey(ticker);
    }

    @Override
    public int openCount() {
        return positions.size();
    }

    @Override
    public Position getPosition(String ticker) {
        return positions.get(ticker);
    }

    @Override
    public Collection<Position> allPositions() {
        return positions.values();
    }
}
