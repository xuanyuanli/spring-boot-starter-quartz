package com.yfs.springboot.quartz;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QuartzAnnotationConfiguration {

    @Bean
    public QuartzAnnotationBeanPostProcessor quartzAnnotationBeanPostProcessor() {
        return new QuartzAnnotationBeanPostProcessor();
    }

}
