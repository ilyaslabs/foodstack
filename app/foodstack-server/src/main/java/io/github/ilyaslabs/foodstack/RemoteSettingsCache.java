package io.github.ilyaslabs.foodstack;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;

import io.github.ilyaslabs.foodstack.settings.api.CachingSettingsApi;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;

import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Where the settings module runs in another service, keeps its answers for 30 seconds in this one
 * (TDD 7.5, gap 12d), so page views and checkouts do not each call it. Where it runs here, its own
 * cache already answers in memory and nothing is wrapped. The integration tests shorten the time,
 * so a journey that changes settings need not wait half a minute.
 */
class RemoteSettingsCache implements BeanPostProcessor {

    static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private final Duration ttl;

    RemoteSettingsCache(Duration ttl) {
        this.ttl = ttl;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof SettingsApi settings && isRemote(settings)) {
            return new CachingSettingsApi(settings, this.ttl, Clock.systemUTC());
        }
        return bean;
    }

    /** Module Switch calls a remote module through a JDK proxy with its HTTP handler. */
    static boolean isRemote(Object api) {
        return Proxy.isProxyClass(api.getClass())
                && Proxy.getInvocationHandler(api).getClass().getSimpleName().equals("HttpModuleInvocationHandler");
    }
}
