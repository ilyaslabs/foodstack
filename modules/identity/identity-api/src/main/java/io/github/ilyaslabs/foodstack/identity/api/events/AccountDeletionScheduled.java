package io.github.ilyaslabs.foodstack.identity.api.events;

import java.time.Instant;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * A customer asked to delete their account (PRD ID-7). Topic {@code foodstack.identity.events},
 * type {@code foodstack.identity.AccountDeletionScheduled.v1}. Notification emails the customer;
 * signing in again before {@code deleteAfter} cancels the deletion.
 *
 * @param userId the customer
 * @param deleteAfter when the account is deleted
 */
public record AccountDeletionScheduled(String userId, Instant deleteAfter) implements ModuleEvent {

    @Override
    public String subject() {
        return this.userId;
    }
}
