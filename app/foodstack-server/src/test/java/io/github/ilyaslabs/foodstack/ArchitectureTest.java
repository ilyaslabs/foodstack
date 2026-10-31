package io.github.ilyaslabs.foodstack;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.List;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

/** The layer rules of TDD 4.2 and Testing Strategy 2, checked on the classes of every module. */
class ArchitectureTest {

    private static final String ROOT = "io.github.ilyaslabs.foodstack";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);
    }

    @Test
    @DisplayName("controllers live only in a module's -web")
    void controllersOnlyInWeb() {
        classes().that().areAnnotatedWith(RestController.class)
            .should().resideInAPackage(ROOT + ".*.web..")
            .check(classes);
    }

    @Test
    @DisplayName("-impl and -web use only the application services of their own -core")
    void implAndWebUseOnlyCoreApplication() {
        for (String module : FoodstackModules.ALL) {
            noClasses().that().resideInAnyPackage(ROOT + "." + module + ".impl..", ROOT + "." + module + ".web..")
                .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + "." + module + ".core.persistence..",
                    ROOT + "." + module + ".core.domain..",
                    ROOT + "." + module + ".core.storage..",
                    ROOT + "." + module + ".core.processing..",
                    ROOT + "." + module + ".core.seed..")
                .check(classes);
        }
    }

    @Test
    @DisplayName("repositories are used only inside their own -core")
    void repositoriesStayInCore() {
        for (String module : FoodstackModules.ALL) {
            classes().that().resideInAPackage(ROOT + "." + module + ".core.persistence..")
                .should().onlyBeAccessed().byAnyPackage(ROOT + "." + module + ".core..")
                .check(classes);
        }
    }

    @Test
    @DisplayName("no module depends on another module's -core, -impl or -web")
    void modulesTalkOnlyThroughApis() {
        for (String module : FoodstackModules.ALL) {
            List<String> others = FoodstackModules.ALL.stream().filter(other -> !other.equals(module)).toList();
            for (String other : others) {
                noClasses().that().resideInAPackage(ROOT + "." + module + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + "." + other + ".core..", ROOT + "." + other + ".impl..", ROOT + "." + other + ".web..")
                    .check(classes);
            }
        }
    }

    @Test
    @DisplayName("platform libraries never depend on a module")
    void platformIsIndependent() {
        for (String module : FoodstackModules.ALL) {
            noClasses().that().resideInAPackage(ROOT + ".platform..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + "." + module + "..")
                .check(classes);
        }
    }
}
