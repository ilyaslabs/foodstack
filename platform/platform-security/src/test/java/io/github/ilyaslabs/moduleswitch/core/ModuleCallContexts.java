package io.github.ilyaslabs.moduleswitch.core;

import java.util.Map;
import java.util.function.Supplier;

/** Test access to Module-Switch's package-private call context, as its HTTP server sets it. */
public final class ModuleCallContexts {

    private ModuleCallContexts() {
    }

    public static <T> T asRemoteCall(String module, Map<String, String> headers, Supplier<T> work) {
        return ModuleCallContext.runWith(new ModuleCallContext(module, headers), work);
    }
}
