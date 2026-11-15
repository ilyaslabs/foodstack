package io.github.ilyaslabs.foodstack.ledger.impl;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
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

    @Flow(value = "ledger-api-store-balance", label = "Branch balance for other modules", description = "")
    @EVL.Endpoint(method = "API", path = "LedgerApi.storeBalance", caller = "Payment, store")
    @Override
    public StoreBalance storeBalance(String storeId) {
        ModuleAccess.requireServiceOr(LedgerReports.LEDGER_READ);
        return this.reports.storeBalance(storeId);
    }
}
