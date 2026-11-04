package io.github.ilyaslabs.foodstack.platform.mongo;

import java.util.concurrent.atomic.AtomicInteger;

import com.mongodb.MongoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryingTransactionTemplateTest {

    private final RetryingTransactionTemplate template = new RetryingTransactionTemplate(new NoOpTransactions());

    private static RuntimeException writeConflict() {
        MongoException conflict = new MongoException(112, "WriteConflict");
        conflict.addLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
        return new UncategorizedMongoDbException("Write conflict", conflict);
    }

    @Test
    @DisplayName("runs the transaction again after a transient error such as a write conflict")
    void retriesTransient() {
        AtomicInteger runs = new AtomicInteger();
        String result = this.template.execute((status) -> {
            if (runs.incrementAndGet() < 3) {
                throw writeConflict();
            }
            return "placed";
        });
        assertThat(result).isEqualTo("placed");
        assertThat(runs).hasValue(3);
    }

    @Test
    @DisplayName("gives up after four attempts")
    void givesUp() {
        AtomicInteger runs = new AtomicInteger();
        assertThatThrownBy(() -> this.template.execute((status) -> {
            runs.incrementAndGet();
            throw writeConflict();
        })).isInstanceOf(UncategorizedMongoDbException.class);
        assertThat(runs).hasValue(RetryingTransactionTemplate.MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("does not retry other failures")
    void noRetryOtherwise() {
        AtomicInteger runs = new AtomicInteger();
        assertThatThrownBy(() -> this.template.execute((status) -> {
            runs.incrementAndGet();
            throw new DataIntegrityViolationException("duplicate");
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(runs).hasValue(1);
    }

    static class NoOpTransactions extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
