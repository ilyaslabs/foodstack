package io.github.ilyaslabs.foodstack.notification.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Sharded;

/**
 * A device that receives push messages, in {@code notification_devices} (id = SHA-256 of the token),
 * shard key {@code { userId: hashed }}: a user's devices are read together. The token is encrypted.
 *
 * @param id SHA-256 of the token
 * @param userId owner
 * @param tokenCipher the FCM registration token, encrypted
 * @param platform {@code WEB}, {@code ANDROID} or {@code IOS}
 * @param app {@code web} (customer app) or {@code backoffice} (store, rider and operator app)
 * @param createdAt registered at
 * @param seenAt last registered at (apps register again on start)
 */
@Document("notification_devices")
@Sharded(shardKey = "userId", immutableKey = true)
public record DeviceDocument(@Id String id, String userId, String tokenCipher, String platform, String app, Instant createdAt,
        Instant seenAt) {
}
