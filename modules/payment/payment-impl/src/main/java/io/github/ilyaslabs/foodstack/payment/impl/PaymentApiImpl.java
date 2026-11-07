package io.github.ilyaslabs.foodstack.payment.impl;

import io.github.ilyaslabs.foodstack.payment.api.CardSessionView;
import io.github.ilyaslabs.foodstack.payment.api.MerchantView;
import io.github.ilyaslabs.foodstack.payment.api.PaymentApi;
import io.github.ilyaslabs.foodstack.payment.api.PaymentOptionsView;
import io.github.ilyaslabs.foodstack.payment.api.PaymentView;
import io.github.ilyaslabs.foodstack.payment.api.StartCardRequest;
import io.github.ilyaslabs.foodstack.payment.core.application.MerchantAccounts;
import io.github.ilyaslabs.foodstack.payment.core.application.PaymentService;
import io.github.ilyaslabs.foodstack.platform.security.ModuleAccess;

import org.springframework.stereotype.Component;

/**
 * {@link PaymentApi} for other modules. Payment options are public; the order starts a card payment
 * with the customer's token, so only that customer (or the platform) may start or read it; support
 * and finance staff may read any (TDD 9.1).
 */
@Component
class PaymentApiImpl implements PaymentApi {

    private final PaymentService payments;

    private final MerchantAccounts accounts;

    PaymentApiImpl(PaymentService payments, MerchantAccounts accounts) {
        this.payments = payments;
        this.accounts = accounts;
    }

    @Override
    public MerchantView resolveMerchant(String storeId) {
        ModuleAccess.requireAuthenticated();
        return this.accounts.resolve(storeId);
    }

    @Override
    public PaymentOptionsView options() {
        return this.payments.options();
    }

    @Override
    public CardSessionView startCard(StartCardRequest request) {
        ModuleAccess.requireServiceSelfOr(request.customerId());
        return this.payments.startCard(request);
    }

    @Override
    public PaymentView get(String orderId) {
        String customerId = this.payments.customerOf(orderId);
        if (customerId == null) {
            ModuleAccess.requireAuthenticated();
            return null;
        }
        ModuleAccess.requireServiceSelfOr(customerId, "order:manage", "payment:manage");
        return this.payments.get(orderId);
    }
}
