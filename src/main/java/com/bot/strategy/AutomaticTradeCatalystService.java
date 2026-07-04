package com.bot.strategy;

import com.bot.model.CatalystResult;
import com.bot.model.CatalystType;

public class AutomaticTradeCatalystService {

    public boolean isAutomaticLongCandidate(
            CatalystResult catalyst
    ) {
        if (catalyst == null) {
            return false;
        }

        CatalystType type =
                catalyst.type;

        return type == CatalystType.FDA_APPROVAL ||
                type == CatalystType.FDA_CLEARANCE ||
                type == CatalystType.CLINICAL_TRIAL_SUCCESS ||
                type == CatalystType.CLINICAL_TRIAL_INITIATION ||
                type == CatalystType.DRUG_DATA_POSITIVE ||
                type == CatalystType.MAJOR_CONTRACT ||
                type == CatalystType.MAJOR_ORDER ||
                type == CatalystType.CONTRACT_RENEWAL ||
                type == CatalystType.PRODUCT_SALE ||
                type == CatalystType.SALES_AGREEMENT ||
                type == CatalystType.MATERIAL_SUPPLY_AGREEMENT ||
                type == CatalystType.BUYOUT_OFFER ||
                type == CatalystType.NASDAQ_COMPLIANCE ||
                type == CatalystType.NASDAQ_COMPLIANCE_EXTENSION ||
                type == CatalystType.NYSE_COMPLIANCE ||
                type == CatalystType.EXCHANGE_COMPLIANCE ||
                type == CatalystType.INDEX_ADDITION ||
                type == CatalystType.IPO_DEBUT ||
                type == CatalystType.GUIDANCE_RAISE ||
                type == CatalystType.EARNINGS_BEAT;
    }

    public boolean isAutomaticShortCandidate(
            CatalystResult catalyst
    ) {
        return false;
    }
}
