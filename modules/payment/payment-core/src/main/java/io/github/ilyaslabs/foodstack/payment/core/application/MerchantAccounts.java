package io.github.ilyaslabs.foodstack.payment.core.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.ilyaslabs.foodstack.payment.api.MerchantView;
import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway;
import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway.GatewayException;
import io.github.ilyaslabs.foodstack.payment.core.persistence.MerchantAccountDocument;
import io.github.ilyaslabs.foodstack.payment.core.persistence.StoreCollectionDocument;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Brand and branch merchant accounts and which one a payment goes into (PRD DP-1 to DP-7, BB-9, TDD
 * 5.4). An owner connects an account with the gateway's keys; it is verified by a test call first and
 * only then replaces the owner's previous one, so a failed attempt never stops a working account. A
 * branch's payments go into its own account, else its brand's, while the deployment switch is on,
 * finance allows the branch, and the ledger has not suspended it for going over its credit limit.
 */
public class MerchantAccounts {

    private static final Logger log = LoggerFactory.getLogger(MerchantAccounts.class);

    /** Owners and staff of a branch connect accounts with this; operator staff only look. */
    public static final String PAYMENT_ACCOUNT = "payment:account";

    private static final List<String> OPERATOR_VIEWERS = List.of("payment:manage", "store:payment-collection:manage",
            "store:manage");

    private final MongoTemplate mongo;

    private final TransactionTemplate transaction;

    private final SettingsApi settings;

    private final CardGateway operatorGateway;

    private final CardGateway.AccountGateways accountGateways;

    private final PiiCipher cipher;

    private final String publicUrl;

    private final Clock clock;

    private final Map<String, CardGateway> opened = new ConcurrentHashMap<>();

    /**
     * @param operatorGateway the operator's configured gateway, or {@code null} for none
     * @param accountGateways opens it on a store's account, or {@code null} when no gateway is configured
     * @param cipher encrypts the keys, or {@code null} when no PII key is set (then nothing can be connected)
     */
    public MerchantAccounts(MongoTemplate mongo, TransactionTemplate transaction, SettingsApi settings,
            CardGateway operatorGateway, CardGateway.AccountGateways accountGateways, PiiCipher cipher, String publicUrl,
            Clock clock) {
        this.mongo = mongo;
        this.transaction = transaction;
        this.settings = settings;
        this.operatorGateway = operatorGateway;
        this.accountGateways = accountGateways;
        this.cipher = cipher;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        this.clock = clock;
    }

    // ---- choosing the account -------------------------------------------------------------------

    /** Whose account a card payment for the branch goes into now. */
    public MerchantView resolve(String storeId) {
        if (this.operatorGateway == null || !this.settings.getOrdering().directCollectionEnabled()) {
            return MerchantView.BY_OPERATOR;
        }
        StoreCollectionDocument collection = this.mongo.findById(storeId, StoreCollectionDocument.class);
        if (collection == null || !StoreCollectionDocument.DIRECT.equals(collection.mode()) || collection.suspended()) {
            return MerchantView.BY_OPERATOR;
        }
        MerchantAccountDocument account = current(MerchantAccountDocument.STORE, storeId);
        if (!usable(account) && collection.brandId() != null) {
            account = current(MerchantAccountDocument.BRAND, collection.brandId());
        }
        return usable(account) ? new MerchantView(account.id(), MerchantView.STORE) : MerchantView.BY_OPERATOR;
    }

    private boolean usable(MerchantAccountDocument account) {
        return account != null && MerchantAccountDocument.ACTIVE.equals(account.status())
                && account.gateway().equals(this.operatorGateway.id());
    }

    /** The gateway for a payment's account: the operator's for {@code null} or {@code operator}. */
    public CardGateway gateway(String accountId) {
        if (accountId == null || PaymentService.OPERATOR_ACCOUNT.equals(accountId)) {
            return this.operatorGateway;
        }
        return this.opened.computeIfAbsent(accountId, (id) -> {
            MerchantAccountDocument account = this.mongo.findById(id, MerchantAccountDocument.class);
            if (account == null || this.accountGateways == null || this.cipher == null) {
                throw new ModuleException.Unavailable("This payment's merchant account is not available",
                        "MERCHANT_ACCOUNT_UNAVAILABLE");
            }
            return this.accountGateways.open(this.cipher.decrypt(account.secretKey()),
                    this.cipher.decrypt(account.webhookSecret()));
        });
    }

    /** The gateway refused a store account's keys while taking a payment: new orders go to the operator. */
    public void markFailed(String accountId, String error) {
        if (accountId == null || PaymentService.OPERATOR_ACCOUNT.equals(accountId)) {
            return;
        }
        log.warn("Merchant account {} was refused by the gateway: {}", accountId, error);
        this.mongo.updateFirst(
                Query.query(Criteria.where("_id").is(accountId).and("status").is(MerchantAccountDocument.ACTIVE)),
                new Update().set("status", MerchantAccountDocument.FAILED).set("lastError", error)
                    .set("updatedAt", this.clock.instant()),
                MerchantAccountDocument.class);
    }

    /** The accounts whose webhooks arrive at an owner's address, current first. */
    public List<MerchantAccountDocument> webhookAccounts(String scope, String ownerId) {
        return this.mongo.find(Query.query(Criteria.where("scope").is(scope).and("ownerId").is(ownerId))
            .with(Sort.by(Sort.Order.desc("current"), Sort.Order.desc("updatedAt")))
            .limit(5), MerchantAccountDocument.class);
    }

    // ---- what the store and ledger modules say ----------------------------------------------------

    /** Finance changed who collects for the branch. */
    public void collectionChanged(String storeId, String brandId, String mode) {
        this.mongo.upsert(Query.query(Criteria.where("_id").is(storeId)),
                new Update().set("brandId", brandId).set("mode", mode).set("updatedAt", this.clock.instant())
                    .setOnInsert("suspended", false),
                StoreCollectionDocument.class);
    }

    /** The ledger says the branch is over its credit limit, or back within it. */
    public void suspended(String storeId, String brandId, boolean suspended) {
        this.mongo.upsert(Query.query(Criteria.where("_id").is(storeId)),
                new Update().set("suspended", suspended).set("updatedAt", this.clock.instant())
                    .setOnInsert("brandId", brandId).setOnInsert("mode", "OPERATOR"),
                StoreCollectionDocument.class);
    }

    // ---- owners' screens ------------------------------------------------------------------------

    /**
     * What the branch's owners and finance see: whether the branch may collect, the accounts and
     * where its card payments go now.
     */
    public CollectionView view(String storeId) {
        requireView(storeId);
        StoreCollectionDocument collection = this.mongo.findById(storeId, StoreCollectionDocument.class);
        String brandId = (collection != null) ? collection.brandId() : CurrentUser.brandOf(storeId).orElse(null);
        MerchantAccountDocument own = current(MerchantAccountDocument.STORE, storeId);
        MerchantAccountDocument brand = (brandId != null) ? current(MerchantAccountDocument.BRAND, brandId) : null;
        MerchantView now = resolve(storeId);
        return new CollectionView(storeId, brandId, this.settings.getOrdering().directCollectionEnabled(),
                (this.operatorGateway != null) ? this.operatorGateway.id() : "none", (collection != null) ? collection.mode()
                        : "OPERATOR",
                collection != null && collection.suspended(), account(own), account(brand), now.collectedBy(),
                now.accountId(), webhookUrl(MerchantAccountDocument.STORE, storeId),
                (brandId != null) ? webhookUrl(MerchantAccountDocument.BRAND, brandId) : null,
                CurrentUser.hasAuthority(PAYMENT_ACCOUNT) && CurrentUser.managesStore(storeId),
                brandId != null && CurrentUser.hasAuthority(PAYMENT_ACCOUNT) && CurrentUser.brandIds().contains(brandId));
    }

    /**
     * Connects an account for a brand or branch: the keys are tried with a test call, then the account
     * replaces the owner's previous one. Errors: {@code DIRECT_COLLECTION_OFF} (400) when the deployment
     * or the branch may not collect, {@code MERCHANT_ACCOUNT_REFUSED} (400) with the gateway's reason.
     */
    public AccountView connect(String scope, String ownerId, String secretKey, String webhookSecret) {
        requireOwner(scope, ownerId);
        if (this.operatorGateway == null || this.accountGateways == null) {
            throw new ModuleException.BadRequest("No card gateway is set up on this platform", "PAYMENT_UNAVAILABLE");
        }
        if (this.cipher == null) {
            throw new ModuleException.Unavailable("Merchant accounts need FOODSTACK_PII_KEY on the server",
                    "MERCHANT_ACCOUNTS_UNAVAILABLE");
        }
        requireAllowed(scope, ownerId);
        String key = (secretKey != null) ? secretKey.strip() : "";
        String hook = (webhookSecret != null) ? webhookSecret.strip() : "";
        if (key.length() < 8 || key.length() > 300 || hook.length() < 8 || hook.length() > 300) {
            throw new ModuleException.BadRequest("Enter the secret key and the webhook signing secret from the gateway",
                    "MERCHANT_ACCOUNT_INVALID");
        }
        try {
            this.accountGateways.open(key, hook).verify();
        }
        catch (GatewayException ex) {
            throw new ModuleException.BadRequest("The gateway refused these keys: " + ex.getMessage(),
                    "MERCHANT_ACCOUNT_REFUSED");
        }
        Instant now = this.clock.instant();
        MerchantAccountDocument account = new MerchantAccountDocument(Ids.newId(), scope, ownerId,
                this.operatorGateway.id(), this.cipher.encrypt(key), this.cipher.encrypt(hook),
                key.substring(key.length() - 4), MerchantAccountDocument.ACTIVE, true, null, now, CurrentUser.id(), now);
        try {
            this.transaction.executeWithoutResult((status) -> {
                retire(scope, ownerId, now);
                this.mongo.insert(account);
            });
        }
        catch (DuplicateKeyException ex) {
            throw new ModuleException.Conflict("Someone connected an account at the same time; reload",
                    "MERCHANT_ACCOUNT_CHANGED");
        }
        return account(account);
    }

    /** Stops using the owner's account; payments already taken keep it for refunds. */
    public void disconnect(String scope, String ownerId) {
        requireOwner(scope, ownerId);
        retire(scope, ownerId, this.clock.instant());
    }

    private void retire(String scope, String ownerId, Instant now) {
        this.mongo.updateMulti(Query.query(Criteria.where("scope").is(scope).and("ownerId").is(ownerId).and("current").is(true)),
                new Update().set("current", false).set("status", MerchantAccountDocument.REVOKED).set("updatedAt", now),
                MerchantAccountDocument.class);
    }

    private MerchantAccountDocument current(String scope, String ownerId) {
        return this.mongo.findOne(Query.query(Criteria.where("scope").is(scope).and("ownerId").is(ownerId).and("current").is(true)),
                MerchantAccountDocument.class);
    }

    private void requireView(String storeId) {
        boolean operator = OPERATOR_VIEWERS.stream().anyMatch(CurrentUser::hasAuthority);
        if (!operator && !(CurrentUser.hasAuthority(PAYMENT_ACCOUNT) && CurrentUser.managesStore(storeId))) {
            throw new ModuleException.Forbidden("You do not manage this branch's payments", "STORE_ACCESS_DENIED");
        }
    }

    private static void requireOwner(String scope, String ownerId) {
        if (!CurrentUser.hasAuthority(PAYMENT_ACCOUNT)) {
            throw new ModuleException.Forbidden("Only the owners connect merchant accounts", "MERCHANT_ACCOUNT_DENIED");
        }
        boolean owns = MerchantAccountDocument.BRAND.equals(scope) ? CurrentUser.brandIds().contains(ownerId)
                : MerchantAccountDocument.STORE.equals(scope) && CurrentUser.managesStore(ownerId);
        if (!owns) {
            throw new ModuleException.Forbidden("You do not own this " + (MerchantAccountDocument.BRAND.equals(scope)
                    ? "brand" : "branch"), "MERCHANT_ACCOUNT_DENIED");
        }
    }

    /** Accounts are connected only once finance has allowed the branch, or a branch of the brand (PRD DP-3). */
    private void requireAllowed(String scope, String ownerId) {
        boolean allowed = this.settings.getOrdering().directCollectionEnabled()
                && this.mongo.exists(Query.query(Criteria.where(MerchantAccountDocument.BRAND.equals(scope) ? "brandId" : "_id")
                    .is(ownerId).and("mode").is(StoreCollectionDocument.DIRECT)), StoreCollectionDocument.class);
        if (!allowed) {
            throw new ModuleException.BadRequest(MerchantAccountDocument.BRAND.equals(scope)
                    ? "The operator has not allowed any branch of this brand to collect card payments itself"
                    : "The operator has not allowed this branch to collect card payments itself", "DIRECT_COLLECTION_OFF");
        }
    }

    private String webhookUrl(String scope, String ownerId) {
        if (this.operatorGateway == null) {
            return null;
        }
        return this.publicUrl + "/api/v1/payment-webhooks/" + this.operatorGateway.id() + "/"
                + (MerchantAccountDocument.BRAND.equals(scope) ? "brands/" : "stores/") + ownerId;
    }

    private static AccountView account(MerchantAccountDocument a) {
        if (a == null) {
            return null;
        }
        return new AccountView(a.id(), a.scope(), a.ownerId(), a.gateway(), a.keyHint(), a.status(), a.lastError(),
                a.verifiedAt(), a.connectedBy());
    }

    /**
     * A merchant account without its keys.
     *
     * @param id the account
     * @param scope {@code BRAND} or {@code STORE}
     * @param ownerId the brand or branch
     * @param gateway gateway id
     * @param keyHint the key's last four characters
     * @param status {@code ACTIVE} or {@code FAILED}
     * @param lastError why it failed, or {@code null}
     * @param verifiedAt when the test call passed
     * @param connectedBy who connected it
     */
    public record AccountView(String id, String scope, String ownerId, String gateway, String keyHint, String status,
            String lastError, Instant verifiedAt, String connectedBy) {
    }

    /**
     * A branch's card collection.
     *
     * @param storeId the branch
     * @param brandId its brand
     * @param enabled direct collection is on for the deployment
     * @param gateway the configured gateway, {@code none} without one
     * @param mode {@code OPERATOR} or {@code DIRECT}, as finance set it
     * @param suspended over the credit limit
     * @param storeAccount the branch's own account, or {@code null}
     * @param brandAccount the brand's account, or {@code null}
     * @param collectedBy who card payments go to now: {@code OPERATOR} or {@code STORE}
     * @param accountId the account they go into now, or {@code null} for the operator's
     * @param storeWebhookUrl the address to give the gateway for the branch's account
     * @param brandWebhookUrl the address for the brand's account, or {@code null}
     * @param canConnectStore the viewer may connect the branch's account
     * @param canConnectBrand the viewer may connect the brand's account
     */
    public record CollectionView(String storeId, String brandId, boolean enabled, String gateway, String mode,
            boolean suspended, AccountView storeAccount, AccountView brandAccount, String collectedBy, String accountId,
            String storeWebhookUrl, String brandWebhookUrl, boolean canConnectStore, boolean canConnectBrand) {
    }
}
