package io.github.ilyaslabs.foodstack.identity.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.Document;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * OAuth2 authorizations (pending requests, codes, tokens) in {@code identity_authorizations}, so
 * logins survive restarts and work across replicas. Same model as the authorization server's JDBC
 * implementation; token lookups go through indexed SHA-256 hashes, and a TTL index removes
 * authorizations once every token has expired.
 */
public class MongoOAuth2AuthorizationService implements OAuth2AuthorizationService {

    public static final String COLLECTION = "identity_authorizations";

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private static final String CODE = "authorizationCode";

    private static final String ACCESS = "accessToken";

    private static final String ID_TOKEN = "oidcIdToken";

    private static final String REFRESH = "refreshToken";

    private final MongoTemplate mongo;

    private final RegisteredClientRepository clients;

    private final JsonMapper jsonMapper;

    public MongoOAuth2AuthorizationService(MongoTemplate mongo, RegisteredClientRepository clients) {
        this.mongo = mongo;
        this.clients = clients;
        // Token claims hold numbers (tv), instants (iat, auth_time) and JDK collections (roles, perms),
        // including the immutable ones List.of and Set.of create.
        BasicPolymorphicTypeValidator.Builder types = BasicPolymorphicTypeValidator.builder()
            .allowIfSubType(Number.class)
            .allowIfSubType(Boolean.class)
            .allowIfSubType(java.time.Instant.class)
            .allowIfSubType("java.util.");
        this.jsonMapper = JsonMapper.builder()
            .addModules(SecurityJacksonModules.getModules(MongoOAuth2AuthorizationService.class.getClassLoader(), types))
            .build();
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        this.mongo.save(toDocument(authorization), COLLECTION);
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        this.mongo.remove(Query.query(Criteria.where("_id").is(authorization.getId())), COLLECTION);
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return toAuthorization(this.mongo.findById(id, Document.class, COLLECTION));
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        Criteria criteria;
        if (tokenType == null) {
            String hash = hash(token);
            criteria = new Criteria().orOperator(Criteria.where("state").is(token), Criteria.where(CODE + ".hash").is(hash),
                    Criteria.where(ACCESS + ".hash").is(hash), Criteria.where(ID_TOKEN + ".hash").is(hash),
                    Criteria.where(REFRESH + ".hash").is(hash));
        }
        else if (OAuth2ParameterNames.STATE.equals(tokenType.getValue())) {
            criteria = Criteria.where("state").is(token);
        }
        else if (OAuth2ParameterNames.CODE.equals(tokenType.getValue())) {
            criteria = Criteria.where(CODE + ".hash").is(hash(token));
        }
        else if (OAuth2TokenType.ACCESS_TOKEN.equals(tokenType)) {
            criteria = Criteria.where(ACCESS + ".hash").is(hash(token));
        }
        else if (OidcParameterNames.ID_TOKEN.equals(tokenType.getValue())) {
            criteria = Criteria.where(ID_TOKEN + ".hash").is(hash(token));
        }
        else if (OAuth2TokenType.REFRESH_TOKEN.equals(tokenType)) {
            criteria = Criteria.where(REFRESH + ".hash").is(hash(token));
        }
        else {
            return null;
        }
        return toAuthorization(this.mongo.findOne(Query.query(criteria), Document.class, COLLECTION));
    }

    private Document toDocument(OAuth2Authorization authorization) {
        Document document = new Document("_id", authorization.getId())
            .append("registeredClientId", authorization.getRegisteredClientId())
            .append("principalName", authorization.getPrincipalName())
            .append("grantType", authorization.getAuthorizationGrantType().getValue())
            .append("authorizedScopes", new ArrayList<>(authorization.getAuthorizedScopes()))
            .append("attributes", this.jsonMapper.writeValueAsString(authorization.getAttributes()));
        String state = authorization.getAttribute(OAuth2ParameterNames.STATE);
        if (state != null) {
            document.append("state", state);
        }
        Instant expiresAt = null;
        expiresAt = later(expiresAt, putToken(document, CODE, authorization.getToken(OAuth2AuthorizationCode.class)));
        OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getToken(OAuth2AccessToken.class);
        expiresAt = later(expiresAt, putToken(document, ACCESS, accessToken));
        if (accessToken != null) {
            document.get(ACCESS, Document.class)
                .append("type", accessToken.getToken().getTokenType().getValue())
                .append("scopes", new ArrayList<>(accessToken.getToken().getScopes()));
        }
        expiresAt = later(expiresAt, putToken(document, ID_TOKEN, authorization.getToken(OidcIdToken.class)));
        expiresAt = later(expiresAt, putToken(document, REFRESH, authorization.getRefreshToken()));
        // A pending authorization request (no token yet) lives as long as a code would.
        document.append("expiresAt", Date.from((expiresAt != null) ? expiresAt : Instant.now().plusSeconds(600)));
        return document;
    }

    private Instant putToken(Document document, String field, OAuth2Authorization.Token<? extends OAuth2Token> token) {
        if (token == null) {
            return null;
        }
        OAuth2Token value = token.getToken();
        Document stored = new Document("value", value.getTokenValue())
            .append("hash", hash(value.getTokenValue()))
            .append("issuedAt", (value.getIssuedAt() != null) ? Date.from(value.getIssuedAt()) : null)
            .append("expiresAt", (value.getExpiresAt() != null) ? Date.from(value.getExpiresAt()) : null)
            .append("metadata", this.jsonMapper.writeValueAsString(token.getMetadata()));
        document.append(field, stored);
        return value.getExpiresAt();
    }

    private OAuth2Authorization toAuthorization(Document document) {
        if (document == null) {
            return null;
        }
        String clientId = document.getString("registeredClientId");
        RegisteredClient client = this.clients.findById(clientId);
        if (client == null) {
            throw new DataRetrievalFailureException("The registered client '" + clientId + "' no longer exists");
        }
        Map<String, Object> attributes = readMap(document.getString("attributes"));
        OAuth2Authorization.Builder builder = OAuth2Authorization.withRegisteredClient(client)
            .id(document.getString("_id"))
            .principalName(document.getString("principalName"))
            .authorizationGrantType(new AuthorizationGrantType(document.getString("grantType")))
            .authorizedScopes(new LinkedHashSet<>(document.getList("authorizedScopes", String.class, List.of())))
            .attributes((map) -> map.putAll(attributes));
        Document code = document.get(CODE, Document.class);
        if (code != null) {
            builder.token(new OAuth2AuthorizationCode(code.getString("value"), instant(code, "issuedAt"), instant(code, "expiresAt")),
                    (metadata) -> metadata.putAll(readMap(code.getString("metadata"))));
        }
        Document access = document.get(ACCESS, Document.class);
        if (access != null) {
            OAuth2AccessToken.TokenType type = OAuth2AccessToken.TokenType.DPOP.getValue().equalsIgnoreCase(access.getString("type"))
                    ? OAuth2AccessToken.TokenType.DPOP : OAuth2AccessToken.TokenType.BEARER;
            Set<String> scopes = new LinkedHashSet<>(access.getList("scopes", String.class, List.of()));
            builder.token(new OAuth2AccessToken(type, access.getString("value"), instant(access, "issuedAt"),
                    instant(access, "expiresAt"), scopes), (metadata) -> metadata.putAll(readMap(access.getString("metadata"))));
        }
        Document idToken = document.get(ID_TOKEN, Document.class);
        if (idToken != null) {
            Map<String, Object> metadata = readMap(idToken.getString("metadata"));
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = (Map<String, Object>) metadata.getOrDefault(OAuth2Authorization.Token.CLAIMS_METADATA_NAME,
                    Collections.emptyMap());
            builder.token(new OidcIdToken(idToken.getString("value"), instant(idToken, "issuedAt"), instant(idToken, "expiresAt"),
                    claims), (map) -> map.putAll(metadata));
        }
        Document refresh = document.get(REFRESH, Document.class);
        if (refresh != null) {
            builder.token(new OAuth2RefreshToken(refresh.getString("value"), instant(refresh, "issuedAt"),
                    instant(refresh, "expiresAt")), (metadata) -> metadata.putAll(readMap(refresh.getString("metadata"))));
        }
        return builder.build();
    }

    private Map<String, Object> readMap(String json) {
        return (json == null || json.isEmpty()) ? Map.of() : this.jsonMapper.readValue(json, MAP);
    }

    private static Instant instant(Document document, String field) {
        Date date = document.getDate(field);
        return (date != null) ? date.toInstant() : null;
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return (b != null && b.isAfter(a)) ? b : a;
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
