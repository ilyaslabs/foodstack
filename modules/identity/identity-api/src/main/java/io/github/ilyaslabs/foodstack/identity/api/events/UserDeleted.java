package io.github.ilyaslabs.foodstack.identity.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * A customer's account was deleted at their request (PRD ID-7, BR-11). Topic
 * {@code foodstack.identity.events}, type {@code foodstack.identity.UserDeleted.v1}. Every module
 * that keeps personal details of the user erases or anonymises them; the user id itself stays on
 * past orders and ledger lines, where it no longer leads to a person.
 *
 * @param userId the deleted user
 */
public record UserDeleted(String userId) implements ModuleEvent {

    @Override
    public String subject() {
        return this.userId;
    }
}
