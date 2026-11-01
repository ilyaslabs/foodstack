package io.github.ilyaslabs.foodstack.platform.security;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Runs background work (event handlers, rebuilds, seeding) as the platform itself: with a service
 * token as the current authentication, so local module calls pass their permission checks, and as
 * the {@code Authorization} header Module-Switch forwards, so remote modules see the same token
 * (TDD 9.1). Work already running for a user keeps that user.
 */
public class ServiceCalls {

    private final ServiceTokens tokens;

    private final JwtDecoder decoder;

    private final FoodstackJwtAuthenticationConverter converter = new FoodstackJwtAuthenticationConverter();

    public ServiceCalls(ServiceTokens tokens, JwtDecoder decoder) {
        this.tokens = tokens;
        this.decoder = decoder;
    }

    public void run(Runnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }

    public <T> T call(Supplier<T> work) {
        org.springframework.security.core.Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current != null && current.isAuthenticated()
                && !(current instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
            return work.get();
        }
        String token = this.tokens.accessToken();
        SecurityContext previousSecurity = SecurityContextHolder.getContext();
        RequestAttributes previousRequest = RequestContextHolder.getRequestAttributes();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(this.converter.convert(this.decoder.decode(token)));
        SecurityContextHolder.setContext(context);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(bearerRequest(token)));
        try {
            return work.get();
        }
        finally {
            SecurityContextHolder.setContext(previousSecurity);
            if (previousRequest != null) {
                RequestContextHolder.setRequestAttributes(previousRequest);
            }
            else {
                RequestContextHolder.resetRequestAttributes();
            }
        }
    }

    /** A stand-in request that only answers the Authorization header, which Module-Switch reads to forward it. */
    static HttpServletRequest bearerRequest(String token) {
        String value = "Bearer " + token;
        return (HttpServletRequest) Proxy.newProxyInstance(ServiceCalls.class.getClassLoader(),
                new Class<?>[] { HttpServletRequest.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getHeader" -> HttpHeaders.AUTHORIZATION.equalsIgnoreCase((String) args[0]) ? value : null;
                    case "getHeaders" -> HttpHeaders.AUTHORIZATION.equalsIgnoreCase((String) args[0])
                            ? Collections.enumeration(java.util.List.of(value)) : Collections.emptyEnumeration();
                    case "getHeaderNames" -> Collections.enumeration(java.util.List.of(HttpHeaders.AUTHORIZATION));
                    case "getAttributeNames" -> Collections.emptyEnumeration();
                    case "toString" -> "ServiceCallRequest";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
