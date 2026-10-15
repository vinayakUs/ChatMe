package com.example.registration.ratelimit.redis;

import java.util.Map;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

import com.example.registration.ratelimit.RedisLeakyBucketRateLimiterConfiguration;

public class LeakyBucketConfigRegistrar
        implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    private Environment env;

    @Override
    public void setEnvironment(Environment env) {
        this.env = env;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        Map<String, RedisLeakyBucketRateLimiterConfiguration> configs = Binder.get(env)
                .bind("rate-limits.leaky-bucket",
                      Bindable.mapOf(String.class, RedisLeakyBucketRateLimiterConfiguration.class))
                .orElse(Map.of());

        configs.forEach((key, cfg) -> {
            var named = new RedisLeakyBucketRateLimiterConfiguration(
                    key, cfg.maxCapacity(), cfg.permitRegenerationPeriod(), cfg.minDelay());

            registry.registerBeanDefinition(key,
                    BeanDefinitionBuilder
                        .genericBeanDefinition(RedisLeakyBucketRateLimiterConfiguration.class, () -> named)
                        .getBeanDefinition());
        });
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {}
}