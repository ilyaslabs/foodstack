package io.github.ilyaslabs.foodstack.ledger.impl;

import io.github.ilyaslabs.foodstack.ledger.api.LedgerApi;
import io.github.ilyaslabs.foodstack.ledger.api.StoreBalance;
import io.github.ilyaslabs.foodstack.ledger.core.application.LedgerReports;
import io.github.ilyaslabs.foodstack.platform.security.ModuleAccess;

import org.springframework.stereotype.Component;

/** {@link LedgerApi} for other modules: only the platform, or finance, may read a branch's balance. */
@Component
class LedgerApiImpl implements LedgerApi {

    private final LedgerReports reports;

    LedgerApiImpl(LedgerReports reports) {
        this.reports = reports;
    }

    @Override
    public StoreBalance storeBalance(String storeId) {
        ModuleAccess.requireServiceOr(LedgerReports.LEDGER_READ);
        return this.reports.storeBalance(storeId);
    }
}
