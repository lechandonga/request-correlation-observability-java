package com.github.lechandonga.requestcorrelationobservabilityjava.config;

import com.github.lechandonga.requestcorrelationobservabilityjava.correlation.CorrelationFilter;
import com.github.lechandonga.requestcorrelationobservabilityjava.observability.RequestMetrics;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.EnumSet;

/**
 * 显式注册 {@link CorrelationFilter}，使其同时覆盖 REQUEST 与 ASYNC 派发。
 *
 * <p>过滤器作为普通过滤器被自动注册时默认只作用于 REQUEST 派发；异步请求完成后的
 * ASYNC 派发也需要经过过滤器，以完成在途计数与耗时统计的收口，因此这里显式注册。</p>
 */
@Configuration
public class FilterConfig {

    @Bean
    public CorrelationFilter correlationFilter(RequestMetrics metrics) {
        return new CorrelationFilter(metrics);
    }

    @Bean
    public FilterRegistrationBean<CorrelationFilter> correlationFilterRegistration(
            CorrelationFilter filter) {
        FilterRegistrationBean<CorrelationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
