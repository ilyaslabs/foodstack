package io.github.ilyaslabs.foodstack.platform.mongo;

import java.time.Clock;
import java.util.List;

import com.mongodb.client.MongoClient;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

/**
 * Provides {@link ModuleMongoFactory}, from which each module builds its own
 * {@link ModuleMongo}. Repository auto-configuration must stay off
 * ({@code spring.data.mongodb.repositories.type=none}): every module enables its own repositories
 * in its configuration class, so they disappear when the module is not local.
 * <p>
 * The server excludes Boot's {@link DataMongoAutoConfiguration}: it expects one
 * {@code MongoTemplate} (for GridFS), while the server has one per module. The mapping context and
 * conversions it would provide are then declared here.
 */
@AutoConfiguration(after = DataMongoAutoConfiguration.class)
@ConditionalOnBean(MongoClient.class)
@EnableConfigurationProperties(FoodstackMongoProperties.class)
public final class FoodstackMongoAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ModuleMongoFactory moduleMongoFactory(MongoClient mongoClient, MongoConnectionDetails connectionDetails,
            MongoMappingContext mappingContext, MongoCustomConversions conversions,
            FoodstackMongoProperties properties, Environment environment) {
        return new ModuleMongoFactory(mongoClient, connectionDetails.getConnectionString().getConnectionString(),
                mappingContext, conversions, properties, environment);
    }

    @Bean
    @ConditionalOnMissingBean
    MongoCustomConversions mongoCustomConversions() {
        return new MongoCustomConversions(List.of());
    }

    @Bean
    @ConditionalOnMissingBean
    MongoMappingContext mongoMappingContext(MongoCustomConversions conversions) {
        MongoMappingContext context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.setAutoIndexCreation(false);
        return context;
    }

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
