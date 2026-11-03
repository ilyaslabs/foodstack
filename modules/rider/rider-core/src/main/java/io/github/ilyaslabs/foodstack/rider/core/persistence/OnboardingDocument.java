package io.github.ilyaslabs.foodstack.rider.core.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An operator rider's papers in {@code rider_onboarding} (PRD DR-1): identity and vehicle documents
 * and where their pay goes. The id is the rider's user id; shard key {@code { _id: hashed }}.
 * Document numbers and account numbers are encrypted with the deployment's PII key.
 *
 * @param id the rider's user id
 * @param documents identity and vehicle documents, newest last
 * @param payout where the rider's pay goes, or {@code null} until set
 * @param updatedAt last change
 * @param version optimistic lock
 */
@Document("rider_onboarding")
public record OnboardingDocument(@Id String id, List<IdDocument> documents, Payout payout, Instant updatedAt,
        @Version Long version) {

    public List<IdDocument> documents() {
        return (this.documents != null) ? this.documents : List.of();
    }

    /**
     * One document.
     *
     * @param id document id (UUIDv7)
     * @param type {@code NATIONAL_ID}, {@code PASSPORT}, {@code DRIVING_LICENCE}, {@code VEHICLE_REGISTRATION} or {@code OTHER}
     * @param numberCipher the document number, encrypted
     * @param numberLast4 the last 4 characters of the number, for lists
     * @param frontMediaId photo of the front (private media, purpose {@code RIDER_DOCUMENT})
     * @param backMediaId photo of the back, or {@code null}
     * @param expiresOn ISO date the document expires, or {@code null}
     * @param addedAt when it was added
     * @param addedBy who added it
     * @param verifiedAt when staff checked it against the photos, or {@code null}
     * @param verifiedBy who checked it, or {@code null}
     */
    public record IdDocument(String id, String type, String numberCipher, String numberLast4, String frontMediaId,
            String backMediaId, String expiresOn, Instant addedAt, String addedBy, Instant verifiedAt, String verifiedBy) {
    }

    /**
     * Where the rider's pay goes.
     *
     * @param method {@code BANK} or {@code MOBILE_WALLET}
     * @param accountName the name on the account
     * @param provider the bank or wallet name
     * @param accountCipher the account number, IBAN or wallet number, encrypted
     * @param accountLast4 its last 4 characters, for screens and payout reports
     * @param updatedAt last change
     * @param updatedBy who changed it
     */
    public record Payout(String method, String accountName, String provider, String accountCipher, String accountLast4,
            Instant updatedAt, String updatedBy) {
    }
}
