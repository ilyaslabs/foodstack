package io.github.ilyaslabs.foodstack.notification.web;

import java.util.Map;

import io.github.ilyaslabs.foodstack.notification.core.application.PushService;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phones and browsers that receive push messages (TDD 8.3): an app registers its FCM token after
 * the person allows notifications and removes it on sign-out. The push settings an app needs to
 * ask for a token are public, since they are in every web page anyway.
 */
@RestController
class DevicesController {

    private final PushService push;

    private final Map<String, Object> webConfig;

    private final String vapidKey;

    DevicesController(PushService push, JsonMapper jsonMapper,
            @Value("${foodstack.notification.push.web-config:}") String webConfig,
            @Value("${foodstack.notification.push.vapid-key:}") String vapidKey) {
        this.push = push;
        this.webConfig = webConfig.isBlank() ? null : parse(jsonMapper, webConfig);
        this.vapidKey = vapidKey.isBlank() ? null : vapidKey;
    }

    record DeviceRequest(String token, String platform, String app) {
    }

    record PushConfig(boolean enabled, Map<String, Object> firebase, String vapidKey) {
    }

    @GetMapping("/api/v1/devices/push-config")
    PushConfig config() {
        boolean web = this.push.enabled() && this.webConfig != null && this.vapidKey != null;
        return new PushConfig(web, web ? this.webConfig : null, web ? this.vapidKey : null);
    }

    @PostMapping("/api/v1/devices")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<Void> register(@RequestBody DeviceRequest request) {
        this.push.register(CurrentUser.id(), request.token(), request.platform(), request.app());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/devices/remove")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<Void> remove(@RequestBody DeviceRequest request) {
        this.push.unregister(CurrentUser.id(), request.token());
        return ResponseEntity.noContent().build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(JsonMapper jsonMapper, String json) {
        try {
            return jsonMapper.readValue(json, Map.class);
        }
        catch (RuntimeException ex) {
            throw new IllegalStateException("foodstack.notification.push.web-config (FCM_WEB_CONFIG) is not valid JSON", ex);
        }
    }
}
