package io.github.ilyaslabs.foodstack.promotion.impl;

import io.github.ilyaslabs.foodstack.platform.security.ModuleAccess;
import io.github.ilyaslabs.foodstack.promotion.api.CouponEvaluation;
import io.github.ilyaslabs.foodstack.promotion.api.CouponRequest;
import io.github.ilyaslabs.foodstack.promotion.api.PromotionApi;
import io.github.ilyaslabs.foodstack.promotion.core.application.CouponService;

import org.springframework.stereotype.Component;

/**
 * {@link PromotionApi} for other modules. The cart and the order act with the customer's token, so a
 * caller may check and hold coupons only for themself; the platform may do it for anyone (TDD 9.1).
 */
@Component
class PromotionApiImpl implements PromotionApi {

    private final CouponService coupons;

    PromotionApiImpl(CouponService coupons) {
        this.coupons = coupons;
    }

    @Override
    public CouponEvaluation evaluate(CouponRequest request) {
        ModuleAccess.requireServiceSelfOr(request.customerId());
        return this.coupons.evaluate(request);
    }

    @Override
    public CouponEvaluation reserve(String orderId, CouponRequest request) {
        ModuleAccess.requireServiceSelfOr(request.customerId());
        return this.coupons.reserve(orderId, request);
    }

    @Override
    public void release(String orderId) {
        String customerId = this.coupons.customerOf(orderId);
        if (customerId != null) {
            ModuleAccess.requireServiceSelfOr(customerId);
            this.coupons.release(orderId);
        }
        else {
            ModuleAccess.requireAuthenticated();
        }
    }
}
