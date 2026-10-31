package io.github.ilyaslabs.foodstack.platform.core;

import java.util.regex.Pattern;

/**
 * Names of topics and CloudEvents types (TDD 7.1).
 * <ul>
 * <li>topic: {@code foodstack.<module>.events}</li>
 * <li>type: {@code foodstack.<module>.<EventName>.v<N>}</li>
 * <li>source: {@code foodstack/<module>}</li>
 * </ul>
 */
public final class EventTypes {

    private static final Pattern MODULE_NAME = Pattern.compile("[a-z][a-z0-9]*");

    private EventTypes() {
    }

    public static String topic(String module) {
        return "foodstack." + requireModule(module) + ".events";
    }

    public static String deadLetterTopic(String module) {
        return topic(module) + ".dlt";
    }

    public static String source(String module) {
        return "foodstack/" + requireModule(module);
    }

    public static String type(String module, Class<? extends ModuleEvent> event) {
        EventVersion version = event.getAnnotation(EventVersion.class);
        return "foodstack." + requireModule(module) + "." + event.getSimpleName() + ".v"
                + ((version != null) ? version.value() : 1);
    }

    public static String requireModule(String module) {
        if (module == null || !MODULE_NAME.matcher(module).matches()) {
            throw new IllegalArgumentException("Invalid module name: " + module);
        }
        return module;
    }
}
