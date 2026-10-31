package io.github.ilyaslabs.foodstack;

import io.github.ilyaslabs.moduleswitch.test.ModuleRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Module-Switch rules on every module API: no overloads, {@code @Param} everywhere, no reaching into another module's -impl. */
class ModuleStructureTest {

    @Test
    @DisplayName("every module follows the Module-Switch rules")
    void modulesFollowTheRules() {
        ModuleRules.verify("io.github.ilyaslabs.foodstack");
    }
}
