package io.github.ilyaslabs.foodstack.platform.security;

import java.util.Optional;

import io.github.ilyaslabs.moduleswitch.ModuleApi;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import io.github.ilyaslabs.moduleswitch.core.ModuleCallContext;
import org.aopalliance.intercept.MethodInterceptor;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * The receiving side of a module call that came over HTTP: Module-Switch's internal endpoint runs
 * outside Spring Security, so this wraps every bean implementing a {@link ModuleApi} interface and,
 * for the length of the call, turns the forwarded {@code Authorization} header into the current
 * authentication. The {@code -impl} checks ({@link ModuleAccess}, {@link CurrentUser}) then see the
 * same user, or the same service token, as the calling module (TDD 9.1). Local calls already carry
 * an authentication and pass straight through.
 */
public class ModuleCallAuthentication implements BeanPostProcessor {

    private final ObjectProvider<JwtDecoder> decoder;

    private final FoodstackJwtAuthenticationConverter converter = new FoodstackJwtAuthenticationConverter();

    public ModuleCallAuthentication(ObjectProvider<JwtDecoder> decoder) {
        this.decoder = decoder;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (java.lang.reflect.Proxy.isProxyClass(bean.getClass())) {
            // Module-Switch's own client proxies
            return bean;
        }
        Class<?>[] apis = java.util.Arrays.stream(org.springframework.util.ClassUtils.getAllInterfaces(bean))
            .filter((type) -> type.getPackage() != null && type.getPackage().isAnnotationPresent(ModuleApi.class))
            .toArray(Class<?>[]::new);
        if (apis.length == 0) {
            return bean;
        }
        return wrap(bean, apis);
    }

    /** Proxies {@code bean} through {@code apis}, authenticating calls that carry a forwarded token. */
    Object wrap(Object bean, Class<?>[] apis) {
        ProxyFactory factory = new ProxyFactory(bean);
        factory.setInterfaces(apis);
        factory.addAdvice((MethodInterceptor) (invocation) -> {
            Optional<String> header = ModuleCallContext.current().flatMap((call) -> call.header("Authorization"));
            if (header.isEmpty() || SecurityContextHolder.getContext().getAuthentication() != null) {
                return invocation.proceed();
            }
            SecurityContext previous = SecurityContextHolder.getContext();
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authenticate(header.get()));
            SecurityContextHolder.setContext(context);
            try {
                return invocation.proceed();
            }
            finally {
                SecurityContextHolder.setContext(previous);
            }
        });
        return factory.getProxy(bean.getClass().getClassLoader());
    }

    private org.springframework.security.core.Authentication authenticate(String header) {
        if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ModuleException.Unauthorized("Unsupported authorization", "UNAUTHENTICATED");
        }
        try {
            return this.converter.convert(this.decoder.getObject().decode(header.substring(7).trim()));
        }
        catch (JwtException ex) {
            throw new ModuleException.Unauthorized("Invalid or expired token", "UNAUTHENTICATED");
        }
    }
}
