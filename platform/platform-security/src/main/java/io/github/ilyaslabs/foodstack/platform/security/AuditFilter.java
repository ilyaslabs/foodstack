package io.github.ilyaslabs.foodstack.platform.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.ilyaslabs.foodstack.platform.core.AuditEntry;
import io.github.ilyaslabs.foodstack.platform.core.AuditSink;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Records every back-office write (PRD AD-3): a {@code POST}, {@code PUT}, {@code PATCH} or
 * {@code DELETE} under {@code /api/} that succeeds for a user with an operator or store role. Customers
 * and riders are not recorded (their writes are orders and location pings, not administration), nor
 * are sign-ins here: the identity module records those itself. The JSON sent and answered is kept
 * with secrets masked and cut to {@link #MAX_BODY} characters, so the log shows the state after each
 * write; the entry before it for the same path shows the state before.
 */
public class AuditFilter extends OncePerRequestFilter {

    /** Characters of each body kept. */
    static final int MAX_BODY = 16_384;

    /** Roles whose writes are administration. */
    static final Set<String> AUDITED_ROLES = Set.of("ADMIN", "FINANCE", "SUPPORT", "BRAND_OWNER", "STORE_OWNER",
            "STORE_STAFF");

    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** Fields whose values are never written to the log. */
    private static final Pattern SECRET = Pattern.compile(
            "(?i).*(password|secret|token|cvc|cvv|cardnumber|iban|accountnumber|apikey|privatekey).*");

    private static final Log logger = LogFactory.getLog(AuditFilter.class);

    private final AuditSink sink;

    private final JsonMapper json;

    private final Clock clock;

    public AuditFilter(AuditSink sink, JsonMapper json, Clock clock) {
        this.sink = sink;
        this.json = json;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !WRITES.contains(request.getMethod()) || !path.startsWith("/api/") || path.startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication user = SecurityContextHolder.getContext().getAuthentication();
        if (!audited(user) || isStream(request)) {
            chain.doFilter(request, response);
            return;
        }
        ContentCachingRequestWrapper cachedRequest = new ContentCachingRequestWrapper(request, MAX_BODY * 4);
        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(cachedRequest, cachedResponse);
            int status = cachedResponse.getStatus();
            if (status >= 200 && status < 300) {
                record(user, cachedRequest, status, cachedResponse);
            }
        }
        finally {
            cachedResponse.copyBodyToResponse();
        }
    }

    private void record(Authentication user, ContentCachingRequestWrapper request, int status,
            ContentCachingResponseWrapper response) {
        try {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String action = request.getMethod() + " " + ((pattern != null) ? pattern : path);
            String[] segments = path.split("/");
            String area = (segments.length > 3) ? segments[3] : "";
            String name = (user instanceof JwtAuthenticationToken jwt) ? jwt.getToken().getClaimAsString("name") : null;
            List<String> roles = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter((a) -> a.startsWith("ROLE_"))
                .map((a) -> a.substring(5))
                .sorted()
                .toList();
            AuditEntry entry = new AuditEntry(Ids.newId(), this.clock.instant(), user.getName(), name, roles, area, action, path,
                    status, body(request.getContentAsByteArray(), request.getContentType()),
                    body(response.getContentAsByteArray(), response.getContentType()), request.getRemoteAddr(),
                    MDC.get("traceId"));
            this.sink.record(entry);
        }
        catch (RuntimeException ex) {
            logger.warn("Could not record an audit entry for " + request.getMethod() + " " + request.getRequestURI(), ex);
        }
    }

    /** The body as masked JSON, or {@code null} when there is none or it is not JSON. */
    String body(byte[] bytes, String contentType) {
        if (bytes.length == 0 || contentType == null || !contentType.toLowerCase().contains("json")) {
            return null;
        }
        String text;
        try {
            JsonNode tree = this.json.readTree(bytes);
            mask(tree);
            text = this.json.writeValueAsString(tree);
        }
        catch (RuntimeException ex) {
            text = new String(bytes, StandardCharsets.UTF_8);
        }
        return (text.length() > MAX_BODY) ? text.substring(0, MAX_BODY) + "…" : text;
    }

    private static void mask(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> secrets = new java.util.ArrayList<>();
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                if (SECRET.matcher(field.getKey()).matches() && !field.getValue().isNull()) {
                    secrets.add(field.getKey());
                }
                else {
                    mask(field.getValue());
                }
            }
            secrets.forEach((key) -> object.put(key, "***"));
        }
        else if (node != null && node.isArray()) {
            for (JsonNode element : node) {
                mask(element);
            }
        }
    }

    static boolean audited(Authentication user) {
        return user != null && user.isAuthenticated() && user.getAuthorities()
            .stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch((a) -> a.startsWith("ROLE_") && AUDITED_ROLES.contains(a.substring(5)));
    }

    private static boolean isStream(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.toLowerCase().startsWith("multipart/");
    }
}
