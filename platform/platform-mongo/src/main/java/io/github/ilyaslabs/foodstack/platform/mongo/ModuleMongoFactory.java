package io.github.ilyaslabs.foodstack.platform.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.util.StringUtils;

/**
 * Builds a {@link ModuleMongo} per module: the shared cluster client with the module's own
 * database, or a dedicated client when {@code foodstack.modules.<module>.mongodb.uri} is set.
 */
public class ModuleMongoFactory {

    private final MongoClient sharedClient;

    private final String sharedConnectionString;

    private final MongoMappingContext mappingContext;

    private final MongoCustomConversions conversions;

    private final FoodstackMongoProperties properties;

    private final Environment environment;

    public ModuleMongoFactory(MongoClient sharedClient, String sharedConnectionString,
            MongoMappingContext mappingContext, MongoCustomConversions conversions,
            FoodstackMongoProperties properties, Environment environment) {
        this.sharedClient = sharedClient;
        this.sharedConnectionString = sharedConnectionString;
        this.mappingContext = mappingContext;
        this.conversions = conversions;
        this.properties = properties;
        this.environment = environment;
    }

    public ModuleMongo create(String module) {
        String databaseName = databaseName(module);
        String override = overrideUri(module);
        MongoClient ownClient = StringUtils.hasText(override) ? MongoClients.create(override) : null;
        MongoClient client = (ownClient != null) ? ownClient : this.sharedClient;
        MongoDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(client, databaseName);
        MappingMongoConverter converter = new MappingMongoConverter(new DefaultDbRefResolver(factory),
                this.mappingContext);
        converter.setCustomConversions(this.conversions);
        converter.setCodecRegistryProvider(factory);
        converter.afterPropertiesSet();
        return new ModuleMongo(module, databaseName, factory, converter, ownClient);
    }

    /** {@code <prefix><module>}, for example {@code foodstack_settings}. */
    public String databaseName(String module) {
        return this.properties.getDatabasePrefix() + module;
    }

    /** The connection string the module's data lives behind, with its database in the path. */
    public String connectionString(String module) {
        String override = overrideUri(module);
        String base = StringUtils.hasText(override) ? override : this.sharedConnectionString;
        return MongoUris.withDatabase(base, databaseName(module));
    }

    private String overrideUri(String module) {
        return this.environment.getProperty("foodstack.modules." + module + ".mongodb.uri");
    }

}
