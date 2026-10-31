package io.github.ilyaslabs.foodstack.platform.mongo;

import com.mongodb.client.MongoClient;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Everything one module needs to reach its own database, {@code foodstack_<module>}.
 * <p>
 * Created by a module's configuration class through {@link ModuleMongoFactory}, so it exists only
 * where the module is {@code local}. No other module ever receives it.
 */
public final class ModuleMongo implements DisposableBean {

    private final String module;

    private final String databaseName;

    private final MongoDatabaseFactory databaseFactory;

    private final MongoTemplate template;

    private final MongoTransactionManager transactionManager;

    private final MongoClient ownClient;

    ModuleMongo(String module, String databaseName, MongoDatabaseFactory databaseFactory, MongoConverter converter,
            MongoClient ownClient) {
        this.module = module;
        this.databaseName = databaseName;
        this.databaseFactory = databaseFactory;
        this.template = new MongoTemplate(databaseFactory, converter);
        this.transactionManager = new MongoTransactionManager(databaseFactory);
        this.ownClient = ownClient;
    }

    public String module() {
        return this.module;
    }

    public String databaseName() {
        return this.databaseName;
    }

    /** {@code <module>_<name>}: every collection carries the module prefix (TDD 6.1). */
    public String collection(String name) {
        return this.module + "_" + name;
    }

    public MongoDatabaseFactory databaseFactory() {
        return this.databaseFactory;
    }

    public MongoTemplate template() {
        return this.template;
    }

    public MongoTransactionManager transactionManager() {
        return this.transactionManager;
    }

    public TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(this.transactionManager);
    }

    @Override
    public void destroy() {
        if (this.ownClient != null) {
            this.ownClient.close();
        }
    }
}
