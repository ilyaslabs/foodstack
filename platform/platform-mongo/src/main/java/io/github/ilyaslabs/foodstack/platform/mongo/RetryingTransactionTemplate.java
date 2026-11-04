package io.github.ilyaslabs.foodstack.platform.mongo;

import com.mongodb.MongoException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A transaction template that runs the whole transaction again when MongoDB labels the failure
 * {@code TransientTransactionError} (TDD 7.5): two transactions writing the same document at the
 * same moment, a primary stepping down, or a shard briefly out of reach. Under load these are
 * expected, and the right answer is to retry, not to fail the customer's order. The callback must
 * only touch the module's database (plus its outbox), which is how every module writes.
 */
public class RetryingTransactionTemplate extends TransactionTemplate {

    static final int MAX_ATTEMPTS = 4;

    private static final Log logger = LogFactory.getLog(RetryingTransactionTemplate.class);

    public RetryingTransactionTemplate(PlatformTransactionManager transactionManager) {
        super(transactionManager);
    }

    @Override
    public <T> T execute(TransactionCallback<T> action) throws TransactionException {
        for (int attempt = 1;; attempt++) {
            try {
                return super.execute(action);
            }
            catch (RuntimeException ex) {
                if (attempt >= MAX_ATTEMPTS || !isTransient(ex)) {
                    throw ex;
                }
                logger.debug("Transient transaction error, attempt " + attempt + "; retrying", ex);
                try {
                    Thread.sleep(5L * attempt * attempt);
                }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }

    static boolean isTransient(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongo && mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
