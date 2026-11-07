package io.github.ilyaslabs.foodstack.it.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/**
 * Stands in for Google's key endpoint (PRD ID-2): publishes one RSA key and signs ID tokens with
 * it, so the journeys check a social sign-in end to end without calling Google.
 */
public final class SocialProviderStub {

    public static final String ISSUER = "https://accounts.google.com";

    private static SocialProviderStub instance;

    private final RSAKey key;

    private final HttpServer server;

    private SocialProviderStub() {
        try {
            this.key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
            this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            byte[] jwks = new JWKSet(this.key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            this.server.createContext("/certs", (exchange) -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                exchange.getResponseBody().write(jwks);
                exchange.close();
            });
            this.server.start();
        }
        catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static synchronized SocialProviderStub get() {
        if (instance == null) {
            instance = new SocialProviderStub();
        }
        return instance;
    }

    public String jwksUri() {
        return "http://localhost:" + this.server.getAddress().getPort() + "/certs";
    }

    /** An ID token as Google would issue it to the app with {@code clientId}. */
    public String idToken(String clientId, String subject, String email, String name) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER)
            .audience(clientId)
            .subject(subject)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(600)))
            .claim("email", email)
            .claim("email_verified", true)
            .claim("name", name)
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(this.key.getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(this.key));
        }
        catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
        return jwt.serialize();
    }
}
