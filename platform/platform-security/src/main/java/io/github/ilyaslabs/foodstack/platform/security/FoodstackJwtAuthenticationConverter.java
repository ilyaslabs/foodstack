package io.github.ilyaslabs.foodstack.platform.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Authorities from token claims, so no lookup is needed per request: every permission in
 * {@code perms} as is ({@code hasAuthority('settings:manage')}) and every role in {@code roles} as
 * {@code ROLE_<role>}.
 */
public class FoodstackJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        for (String permission : list(jwt, TokenClaims.PERMISSIONS)) {
            authorities.add(new SimpleGrantedAuthority(permission));
        }
        for (String role : list(jwt, TokenClaims.ROLES)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    private static List<String> list(Jwt jwt, String claim) {
        List<String> values = jwt.getClaimAsStringList(claim);
        return (values != null) ? values : List.of();
    }
}
