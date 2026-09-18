package com.hmdp.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scans stereotype candidates the way the real context would and fails on duplicate bean names.
 * Runs without MySQL/Redis, so it protects CI too.
 *
 * <p>Regression: {@code SeckillConsumeGateHealthIndicator} was annotated
 * {@code @Component("seckillConsumeGate")}, colliding with the default name of
 * {@code com.hmdp.seckill.SeckillConsumeGate} — the app failed at startup
 * (ConflictingBeanDefinitionException) while all unit tests stayed green.
 */
class BeanNameUniquenessTest {

    @Test
    void stereotypeBeanNamesAreUnique() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        AnnotationBeanNameGenerator generator = new AnnotationBeanNameGenerator();
        Map<String, String> beanNameToClass = new HashMap<String, String>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.hmdp")) {
            String className = bd.getBeanClassName();
            AnnotatedBeanDefinition definition =
                    new AnnotatedGenericBeanDefinition(Class.forName(className));
            String beanName = generator.generateBeanName(definition, null);
            String prev = beanNameToClass.put(beanName, className);
            assertTrue(prev == null,
                    "duplicate bean name '" + beanName + "': " + prev + " vs " + className
                            + " — rename one @Component explicitly or drop the explicit name");
        }
        assertTrue(beanNameToClass.size() > 20, "scan should cover the app's stereotypes");
    }
}
