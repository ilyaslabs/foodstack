package io.github.ilyaslabs.foodstack.it.support;

import static io.restassured.RestAssured.given;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/**
 * Talks to the platform only through its public HTTP API, like the web apps do. Each request goes
 * to the context that owns its path ({@link ModuleRoutes}), so in the microservices run every
 * call crosses the same boundary the gateway would.
 */
public final class ApiClient {

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final FoodstackPlatform platform;

    private String accessToken;

    private String idToken;

    private final Map<String, String> sessionCookies = new HashMap<>();

    ApiClient(FoodstackPlatform platform) {
        this.platform = platform;
    }

    public String accessToken() {
        return this.accessToken;
    }

    /** A client that sends the given token, for checks that a token is (no longer) accepted. */
    public ApiClient withToken(String token) {
        ApiClient copy = new ApiClient(this.platform);
        copy.accessToken = token;
        return copy;
    }

    /** A request to the owner of {@code path}; send it with {@code .get(path)}, {@code .post(path)}, and so on. */
    public RequestSpecification request(String path) {
        RequestSpecification request = given().baseUri(this.platform.baseUrlFor(path)).redirects().follow(false);
        if (this.accessToken != null) {
            request = request.auth().oauth2(this.accessToken);
        }
        return request;
    }

    public Response get(String path) {
        return request(path).get(path);
    }

    public Response postJson(String path, Object body) {
        return request(path).contentType(ContentType.JSON).body(body).post(path);
    }

    /**
     * Signs in through the authorization code flow with PKCE, as the back-office app does: the
     * browser is sent to the login page, posts the form, and returns with a code that the app
     * exchanges for an access token.
     */
    public ApiClient logIn(String email, String password) {
        String verifier = randomUrlSafe(48);
        String state = randomUrlSafe(12);
        Map<String, String> query = new HashMap<>();
        query.put("response_type", "code");
        query.put("client_id", FoodstackPlatform.BACKOFFICE_CLIENT);
        query.put("redirect_uri", FoodstackPlatform.BACKOFFICE_REDIRECT);
        query.put("scope", "openid");
        query.put("state", state);
        query.put("code_challenge", challenge(verifier));
        query.put("code_challenge_method", "S256");

        Response authorize = browser("/oauth2/authorize", Map.of()).queryParams(query).get("/oauth2/authorize");
        expectRedirect(authorize, "/login");
        Map<String, String> cookies = new HashMap<>(authorize.getCookies());

        Response loginPage = browser("/login", cookies).get("/login");
        cookies.putAll(loginPage.getCookies());
        Matcher csrf = CSRF.matcher(loginPage.asString());
        if (!csrf.find()) {
            throw new AssertionError("The login page has no CSRF token");
        }

        Response login = browser("/login", cookies).contentType(ContentType.URLENC)
            .formParam("username", email)
            .formParam("password", password)
            .formParam("_csrf", csrf.group(1))
            .post("/login");
        cookies.putAll(login.getCookies());
        String savedRequest = expectRedirect(login, "/oauth2/authorize");

        Response resumed = browser("/oauth2/authorize", cookies).urlEncodingEnabled(false)
            .get(pathAndQuery(savedRequest));
        String callback = expectRedirect(resumed, FoodstackPlatform.BACKOFFICE_REDIRECT);
        Map<String, String> params = queryOf(callback);
        if (!state.equals(params.get("state"))) {
            throw new AssertionError("The authorization server returned another state");
        }

        Response token = given().baseUri(this.platform.baseUrlFor("/oauth2/token"))
            .contentType(ContentType.URLENC)
            .formParam("grant_type", "authorization_code")
            .formParam("code", params.get("code"))
            .formParam("redirect_uri", FoodstackPlatform.BACKOFFICE_REDIRECT)
            .formParam("client_id", FoodstackPlatform.BACKOFFICE_CLIENT)
            .formParam("code_verifier", verifier)
            .post("/oauth2/token");
        if (token.statusCode() != 200) {
            throw new AssertionError("Token exchange failed: " + token.statusCode() + " " + token.asString());
        }
        this.accessToken = token.path("access_token");
        this.idToken = token.path("id_token");
        this.sessionCookies.putAll(cookies);
        return this;
    }

    /**
     * Signs out as the apps do (OIDC RP-initiated logout), ending the identity session. Returns the
     * response, which redirects to the post-logout URI.
     */
    public Response logOut(String postLogoutRedirectUri) {
        return browser("/connect/logout", this.sessionCookies)
            .queryParam("client_id", FoodstackPlatform.BACKOFFICE_CLIENT)
            .queryParam("post_logout_redirect_uri", postLogoutRedirectUri)
            .queryParam("id_token_hint", this.idToken)
            .get("/connect/logout");
    }

    /** Whether the identity session still signs the browser in without the login page. */
    public boolean hasIdentitySession() {
        Response authorize = browser("/oauth2/authorize", this.sessionCookies)
            .queryParam("response_type", "code")
            .queryParam("client_id", FoodstackPlatform.BACKOFFICE_CLIENT)
            .queryParam("redirect_uri", FoodstackPlatform.BACKOFFICE_REDIRECT)
            .queryParam("scope", "openid")
            .queryParam("code_challenge", challenge(randomUrlSafe(48)))
            .queryParam("code_challenge_method", "S256")
            .get("/oauth2/authorize");
        String location = authorize.header("Location");
        return location != null && location.startsWith(FoodstackPlatform.BACKOFFICE_REDIRECT);
    }

    private RequestSpecification browser(String path, Map<String, String> cookies) {
        return given().baseUri(this.platform.baseUrlFor(path)).redirects().follow(false)
            .accept("text/html").cookies(cookies);
    }

    private static String expectRedirect(Response response, String expected) {
        String location = response.header("Location");
        if (response.statusCode() != 302 || location == null || !location.contains(expected)) {
            throw new AssertionError("Expected a redirect to " + expected + " but got " + response.statusCode()
                    + " Location=" + location);
        }
        return location;
    }

    private static String pathAndQuery(String url) {
        URI uri = URI.create(url);
        return uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");
    }

    private static Map<String, String> queryOf(String url) {
        Map<String, String> params = new HashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq), java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    private static String randomUrlSafe(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
