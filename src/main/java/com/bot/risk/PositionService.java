package com.bot.risk;

import com.bot.model.Position;

import java.util.Collection;

public interface PositionService {

    boolean hasPosition(String ticker);

    int openCount();

    Position getPosition(String ticker);

    Collection<Position> allPositions();
}