package com.bot.strategy;

import com.bot.model.SectorMomentumProfile;

public interface SectorMomentumProvider {

    SectorMomentumProfile profile(String ticker);
}