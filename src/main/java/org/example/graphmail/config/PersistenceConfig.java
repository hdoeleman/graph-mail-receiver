package org.example.graphmail.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;

@Configuration(proxyBeanMethods = false)
public class PersistenceConfig {

    /**
     * Spring Data JDBC quotes identifiers by default, so it would ask H2 for a lower-case {@code "email"} table,
     * while the unquoted names in the Flyway migrations are stored upper-case. Unquoted identifiers match them, for
     * whatever database URL is configured. Set before the context initializes, which is when it maps the entities.
     */
    @Bean
    static BeanPostProcessor unquotedIdentifiers() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof RelationalMappingContext context) {
                    context.setForceQuote(false);
                }
                return bean;
            }
        };
    }
}
