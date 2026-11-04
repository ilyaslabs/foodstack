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
 * cache already answers in memory and nothing is wrapped.
 */
class RemoteSettingsCache implements BeanPostProcessor {

    static final Duration TTL = Duration.ofSeconds(30);

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof SettingsApi settings && isRemote(settings)) {
            return new CachingSettingsApi(settings, TTL, Clock.systemUTC());
        }
        return bean;
    }

    /** Module Switch calls a remote module through a JDK proxy with its HTTP handler. */
    static boolean isRemote(Object api) {
        return Proxy.isProxyClass(api.getClass())
                && Proxy.getInvocationHandler(api).getClass().getSimpleName().equals("HttpModuleInvocationHandler");
    }
}
