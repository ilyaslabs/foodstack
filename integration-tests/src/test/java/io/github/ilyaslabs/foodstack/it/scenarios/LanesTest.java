package io.github.ilyaslabs.foodstack.it.scenarios;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Every journey runs in both lanes: a {@code *Scenario} class left out of {@link Lane} would never run. */
class LanesTest {

    @Test
    void everyScenarioIsInTheLanes() throws IOException, URISyntaxException {
        Path folder = Path.of(Lane.class.getResource("Lane.class").toURI()).getParent();
        List<String> scenarios;
        try (Stream<Path> files = Files.list(folder)) {
            scenarios = files.map((file) -> file.getFileName().toString())
                .filter((name) -> name.endsWith("Scenario.class"))
                .map((name) -> name.substring(0, name.length() - ".class".length()))
                .sorted()
                .toList();
        }
        List<String> inLanes = Arrays.stream(Lane.class.getDeclaredClasses())
            .filter((nested) -> nested.isAnnotationPresent(Nested.class))
            .map((nested) -> nested.getSuperclass().getSimpleName())
            .sorted()
            .toList();
        assertThat(scenarios).isNotEmpty();
        assertThat(inLanes).isEqualTo(scenarios);
    }
}
