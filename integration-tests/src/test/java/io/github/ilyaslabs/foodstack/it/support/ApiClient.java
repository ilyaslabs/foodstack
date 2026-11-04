package io.github.ilyaslabs.foodstack.it.support;

import java.util.Map;
import java.util.function.Supplier;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

/**
 * Talks to the platform only through its public HTTP API, like the web apps do. Each request goes
 * to the context that owns its path ({@link ModuleRoutes}), so in the microservices run every
 * call crosses the same boundary the gateway would.
 */
public final class ApiClient {

    private final FoodstackPlatform platform;

    private String accessToken;

    private String refreshToken;

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

    /** Signs in with email and password through the stateless auth API, as the mobile apps do. */
    public ApiClient logIn(String email, String password) {
        return keep(postAuth("/api/v1/auth/login", Map.of("client", "mobile", "email", email, "password", password)));
    }

    /** Signs in with an emailed one-time code, read with {@code readCode} (from the mailbox). */
    public ApiClient logInWithEmailCode(String email, Supplier<String> readCode) {
        expectStatus(postAuth("/api/v1/auth/code", Map.of("email", email, "purpose", "LOGIN")), 202);
        return keep(postAuth("/api/v1/auth/login", Map.of("client", "mobile", "email", email, "code", readCode.get())));
    }

    /** Forgot password: asks for a reset code, reads it with {@code readCode}, sets {@code newPassword}. */
    public ApiClient resetPassword(String email, String newPassword, Supplier<String> readCode) {
        expectStatus(postAuth("/api/v1/auth/code", Map.of("email", email, "purpose", "PASSWORD_RESET")), 202);
        return keep(postAuth("/api/v1/auth/reset",
                Map.of("client", "mobile", "email", email, "code", readCode.get(), "newPassword", newPassword)));
    }

    /** Swaps the refresh token for new tokens (keeping them when it works) and returns the answer. */
    public Response refresh() {
        Response response = postAuth("/api/v1/auth/refresh",
                (this.refreshToken != null) ? Map.of("refreshToken", this.refreshToken) : Map.<String, String>of());
        if (response.statusCode() == 200) {
            this.accessToken = response.path("accessToken");
            this.refreshToken = response.path("refreshToken");
        }
        return response;
    }

    /** The refresh token this client holds (mobile sign-in). */
    public String refreshToken() {
        return this.refreshToken;
    }

    /** Signs out of this sign-in; the refresh token stops working. */
    public Response logOut() {
        return request("/api/v1/auth/logout").post("/api/v1/auth/logout");
    }

    /** A sign-in as the browser apps do it ({@code client=web}); the refresh token comes as a cookie. */
    public Response webLogIn(String email, String password) {
        return postAuth("/api/v1/auth/login", Map.of("client", "web", "email", email, "password", password));
    }

    /** A browser refresh: only the cookie carries the token. */
    public Response webRefresh(String cookie) {
        return given().baseUri(this.platform.baseUrlFor("/api/v1/auth/refresh")).contentType(ContentType.JSON)
            .cookie("fs_refresh", cookie).body("{}").post("/api/v1/auth/refresh");
    }

    private Response postAuth(String path, Map<String, String> body) {
        return given().baseUri(this.platform.baseUrlFor(path)).contentType(ContentType.JSON).body(body).post(path);
    }

    private ApiClient keep(Response tokens) {
        expectStatus(tokens, 200);
        this.accessToken = tokens.path("accessToken");
        this.refreshToken = tokens.path("refreshToken");
        return this;
    }

    private static void expectStatus(Response response, int status) {
        if (response.statusCode() != status) {
            throw new AssertionError("Expected " + status + " but got " + response.statusCode() + ": " + response.asString());
        }
    }
}
