package io.github.ilyaslabs.foodstack.payment.web;

import io.github.ilyaslabs.foodstack.payment.core.application.MerchantAccounts;
import io.github.ilyaslabs.foodstack.payment.core.application.MerchantAccounts.AccountView;
import io.github.ilyaslabs.foodstack.payment.core.application.MerchantAccounts.CollectionView;
import io.github.ilyaslabs.foodstack.platform.core.EpochSeconds;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Brand and branch merchant accounts (PRD DP-3, DP-4, BB-9): owners connect their gateway keys,
 * which are verified by a test call and never shown again; owners and finance see where a branch's
 * card payments go.
 */
@RestController
@RequestMapping("/api/v1/payments/merchant-accounts")
class MerchantAccountsController {

    private final MerchantAccounts accounts;

    MerchantAccountsController(MerchantAccounts accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    CollectionResponse view(@RequestParam String storeId) {
        return response(this.accounts.view(storeId));
    }

    @PutMapping("/{scope}/{ownerId}")
    @PreAuthorize("hasAuthority('payment:account')")
    AccountResponse connect(@PathVariable String scope, @PathVariable String ownerId, @RequestBody ConnectRequest request) {
        return response(this.accounts.connect(scope(scope), ownerId, request.secretKey(), request.webhookSecret()));
    }

    @DeleteMapping("/{scope}/{ownerId}")
    @PreAuthorize("hasAuthority('payment:account')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disconnect(@PathVariable String scope, @PathVariable String ownerId) {
        this.accounts.disconnect(scope(scope), ownerId);
    }

    private static String scope(String path) {
        return switch (path) {
            case "brands" -> "BRAND";
            case "stores" -> "STORE";
            default -> throw new ModuleException.NotFound("Use brands or stores", "MERCHANT_ACCOUNT_NOT_FOUND");
        };
    }

    private static CollectionResponse response(CollectionView v) {
        return new CollectionResponse(v.storeId(), v.brandId(), v.enabled(), v.gateway(), v.mode(), v.suspended(),
                response(v.storeAccount()), response(v.brandAccount()), v.collectedBy(), v.accountId(), v.storeWebhookUrl(),
                v.brandWebhookUrl(), v.canConnectStore(), v.canConnectBrand());
    }

    private static AccountResponse response(AccountView a) {
        if (a == null) {
            return null;
        }
        return new AccountResponse(a.id(), a.scope(), a.ownerId(), a.gateway(), a.keyHint(), a.status(), a.lastError(),
                EpochSeconds.of(a.verifiedAt()));
    }

    /**
     * The gateway's keys for the account.
     *
     * @param secretKey secret API key, such as {@code sk_live_...}
     * @param webhookSecret the signing secret of the webhook endpoint pointed at the address shown
     */
    record ConnectRequest(String secretKey, String webhookSecret) {
    }

    /** A connected account; {@code verifiedAt} in epoch seconds. */
    record AccountResponse(String id, String scope, String ownerId, String gateway, String keyHint, String status,
            String lastError, Long verifiedAt) {
    }

    /** Where a branch's card payments go (see {@link CollectionView}). */
    record CollectionResponse(String storeId, String brandId, boolean enabled, String gateway, String mode,
            boolean suspended, AccountResponse storeAccount, AccountResponse brandAccount, String collectedBy, String accountId,
            String storeWebhookUrl, String brandWebhookUrl, boolean canConnectStore, boolean canConnectBrand) {
    }
}
