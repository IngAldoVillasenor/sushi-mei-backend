package com.cardovia.merkon.backend.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class TransactionalEmailConfiguration {

    @Bean("emailVerificationDeliveryExecutor")
    TaskExecutor emailVerificationDeliveryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Bounded, application-owned work: external provider latency must not
        // consume request threads or create an unbounded Cloud Run queue.
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("merkon-email-verification-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean
    @ConditionalOnProperty(prefix = "merkon.transactional-email", name = "enabled", havingValue = "true")
    TransactionalEmailSender resendTransactionalEmailSender(RestClient.Builder builder,
                                                             TransactionalEmailProperties properties) {
        return new ResendTransactionalEmailSender(builder, properties);
    }

    @Bean
    @ConditionalOnMissingBean(TransactionalEmailSender.class)
    TransactionalEmailSender disabledTransactionalEmailSender() {
        return email -> {
            throw new TransactionalEmailDeliveryException("DELIVERY_DISABLED");
        };
    }
}
