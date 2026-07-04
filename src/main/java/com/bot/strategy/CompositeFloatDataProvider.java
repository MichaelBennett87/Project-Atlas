package com.bot.strategy;

import java.util.List;

public class CompositeFloatDataProvider implements FloatDataProvider {

    private final List<FloatDataProvider> providers;

    public CompositeFloatDataProvider() {
        this.providers =
                List.of(
                        new FmpFloatDataProvider(),
                        new ManualFloatDataProvider()
                );
    }

    public CompositeFloatDataProvider(List<FloatDataProvider> providers) {
        this.providers = providers;
    }

    @Override
    public Long getSharesFloat(String ticker) {
        for (FloatDataProvider provider : providers) {
            Long sharesFloat =
                    provider.getSharesFloat(ticker);

            if (sharesFloat != null && sharesFloat > 0) {
                return sharesFloat;
            }
        }

        return null;
    }
}