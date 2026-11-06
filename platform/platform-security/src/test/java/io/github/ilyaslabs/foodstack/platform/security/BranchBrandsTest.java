package io.github.ilyaslabs.foodstack.platform.security;

import java.util.List;

import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;

class BranchBrandsTest {

    private LettuceConnectionFactory connectionFactory;

    private StringRedisTemplate redis;

    private String namespace;

    @BeforeEach
    void connect() {
        this.connectionFactory = new LettuceConnectionFactory(FoodstackContainers.redis().getHost(),
                FoodstackContainers.redis().getMappedPort(6379));
        this.connectionFactory.afterPropertiesSet();
        this.redis = new StringRedisTemplate(this.connectionFactory);
        this.namespace = FoodstackContainers.uniqueName();
    }

    @AfterEach
    void disconnect() {
        CurrentUser.useBranchBrands(null);
        SecurityContextHolder.clearContext();
        this.connectionFactory.destroy();
    }

    @Test
    @DisplayName("a branch remembered by the store module is known in every other process")
    void sharedThroughRedis() {
        BranchBrands store = new BranchBrands(this.redis, this.namespace, 10);
        BranchBrands order = new BranchBrands(this.redis, this.namespace, 10);

        store.remember("s1", "b1");

        assertThat(this.redis.opsForValue().get(this.namespace + ":store:branch-brand:s1")).isEqualTo("b1");
        assertThat(order.brandOf("s1")).contains("b1");
        assertThat(order.brandOf("s2")).isEmpty();
        assertThat(order.brandOf(null)).isEmpty();
    }

    @Test
    @DisplayName("keeps answers in memory, so a branch keeps working while Redis is away")
    void cached() {
        BranchBrands brands = new BranchBrands(this.redis, this.namespace, 10);
        brands.remember("s1", "b1");
        BranchBrands other = new BranchBrands(this.redis, this.namespace, 10);
        assertThat(other.brandOf("s1")).contains("b1");

        this.redis.delete(this.namespace + ":store:branch-brand:s1");

        assertThat(other.brandOf("s1")).contains("b1");
    }

    @Test
    @DisplayName("a brand owner manages every branch of the brand; a branch manager only their own")
    void managesStore() {
        BranchBrands brands = new BranchBrands(this.redis, this.namespace, 10);
        brands.remember("s1", "b1");
        brands.remember("s2", "b1");
        brands.remember("s3", "b2");
        CurrentUser.useBranchBrands(brands);

        signIn("brand-owner", List.of("b1"), List.of());
        assertThat(CurrentUser.managesStore("s1")).isTrue();
        assertThat(CurrentUser.managesStore("s2")).isTrue();
        assertThat(CurrentUser.managesStore("s3")).isFalse();
        assertThat(CurrentUser.ownsBrandOf("s2")).isTrue();

        signIn("manager", List.of(), List.of("s1"));
        assertThat(CurrentUser.managesStore("s1")).isTrue();
        assertThat(CurrentUser.managesStore("s2")).isFalse();
        assertThat(CurrentUser.ownsBrandOf("s1")).isFalse();
    }

    private static void signIn(String subject, List<String> brandIds, List<String> storeIds) {
        Jwt.Builder token = Jwt.withTokenValue("t").header("alg", "none").subject(subject);
        if (!brandIds.isEmpty()) {
            token.claim("brandIds", brandIds);
        }
        if (!storeIds.isEmpty()) {
            token.claim("storeIds", storeIds);
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token.build()));
    }
}
