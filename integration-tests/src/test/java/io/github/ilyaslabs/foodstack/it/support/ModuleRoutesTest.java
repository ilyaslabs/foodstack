package io.github.ilyaslabs.foodstack.it.support;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every public path a module's controllers serve is routed to that module, both here and in the
 * Helm chart's HTTPRoutes. A path missing from the map works on one server and in the monolith,
 * and answers 404 once the modules run as separate services.
 */
class ModuleRoutesTest {

    private static final Pattern MODULE = Pattern.compile("^io\\.github\\.ilyaslabs\\.foodstack\\.(\\w+)\\.web\\b.*");

    @Test
    void everyControllerPathIsRoutedToItsModule() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (BeanDefinition found : scanner.findCandidateComponents("io.github.ilyaslabs.foodstack")) {
            Class<?> type = Class.forName(found.getBeanClassName());
            Matcher module = MODULE.matcher(type.getName());
            if (!module.matches()) {
                continue;
            }
            for (String path : paths(type)) {
                checked++;
                String owner;
                try {
                    owner = ModuleRoutes.moduleOf(path);
                }
                catch (IllegalArgumentException ex) {
                    owner = "nobody";
                }
                if (!owner.equals(module.group(1))) {
                    wrong.add(path + " (" + type.getSimpleName() + ", module " + module.group(1) + ", routed to " + owner + ")");
                }
            }
        }
        assertThat(checked).isGreaterThan(100);
        assertThat(wrong).isEmpty();
    }

    @Test
    void helmChartRoutesTheSamePrefixes() throws IOException {
        String helpers = Files.readString(Path.of("../k8s/helm/foodstack/templates/_helpers.tpl"));
        String block = helpers.substring(helpers.indexOf("define \"foodstack.prefixes\""));
        block = block.substring(block.indexOf('\n') + 1, block.indexOf("{{- end -}}"));
        Map<String, List<String>> chart = new LinkedHashMap<>();
        for (String line : block.strip().split("\n")) {
            String[] parts = line.split(":", 2);
            String list = parts[1].strip();
            chart.put(parts[0].strip(),
                    Arrays.stream(list.substring(1, list.length() - 1).split(",")).map(String::strip).sorted().toList());
        }
        Map<String, List<String>> tests = new LinkedHashMap<>();
        ModuleRoutes.PREFIXES.forEach((module, prefixes) -> tests.put(module, prefixes.stream().sorted().toList()));
        assertThat(chart).containsExactlyInAnyOrderEntriesOf(tests);
    }

    private static List<String> paths(Class<?> type) {
        RequestMapping onType = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        String[] bases = (onType != null && onType.path().length > 0) ? onType.path() : new String[] { "" };
        List<String> paths = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            RequestMapping onMethod = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (onMethod == null) {
                continue;
            }
            String[] ends = (onMethod.path().length > 0) ? onMethod.path() : new String[] { "" };
            for (String base : bases) {
                for (String end : ends) {
                    paths.add(base + end);
                }
            }
        }
        return paths;
    }
}
