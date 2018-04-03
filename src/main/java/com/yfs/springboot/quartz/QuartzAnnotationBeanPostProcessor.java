package com.yfs.springboot.quartz;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.quartz.CronTriggerFactoryBean;
import org.springframework.scheduling.quartz.JobDetailFactoryBean;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.util.StringUtils;

import com.yfs.springboot.quartz.annotation.QuartzScheduled;

public class QuartzAnnotationBeanPostProcessor implements BeanPostProcessor, ApplicationContextAware, ApplicationListener<ContextRefreshedEvent>, DisposableBean {

    @Value("${quartz.scheduler.instanceName:}")
    private String quartzInstanceName;

    @Value("${quartz.datasource.driver-class-name}")
    private String myDSDriver;

    @Value("${quartz.datasource.url}")
    private String myDSURL;

    @Value("${quartz.datasource.username}")
    private String myDSUser;

    @Value("${quartz.datasource.password}")
    private String myDSPassword;

    @Value("${quartz.datasource.maxConnections:10}")
    private String myDSMaxConnections;

    @Value("${quartz.cluster:false}")
    private boolean cluster;

    private List<Trigger> triggers = new ArrayList<Trigger>();

    private ConfigurableApplicationContext applicationContext;

    private DefaultListableBeanFactory defaultListableBeanFactory;

    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = (ConfigurableApplicationContext) applicationContext;
        defaultListableBeanFactory = (DefaultListableBeanFactory) this.applicationContext.getBeanFactory();
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(final Object bean, final String beanName) throws BeansException {
        Arrays.asList(bean.getClass().getDeclaredMethods()).stream().filter(m -> m.getAnnotation(QuartzScheduled.class) != null).forEach(m -> {
            String mname = m.getName();
            String beanMethodName = beanName + StringUtils.capitalize(mname);
            String jobBeanName = beanMethodName + "JobDetail";
            String triggerBeanName = beanMethodName + "Trigger";

            BeanDefinitionBuilder jobDetailBuilder = BeanDefinitionBuilder.genericBeanDefinition(JobDetailFactoryBean.class);
            jobDetailBuilder.addPropertyValue("durability", true);
            jobDetailBuilder.addPropertyValue("jobClass", MyQuartzJobBean.class);
            Map<String, String> jobDataAsMap = new HashMap<>();
            jobDataAsMap.put("targetObject", beanName);
            jobDataAsMap.put("targetMethod", mname);
            jobDetailBuilder.addPropertyValue("jobDataAsMap", jobDataAsMap);
            defaultListableBeanFactory.registerBeanDefinition(jobBeanName, jobDetailBuilder.getBeanDefinition());

            BeanDefinitionBuilder triggerBuilder = BeanDefinitionBuilder.genericBeanDefinition(CronTriggerFactoryBean.class);
            JobDetail jobDetail = applicationContext.getBean(jobBeanName, JobDetail.class);
            triggerBuilder.addPropertyValue("jobDetail", jobDetail);
            triggerBuilder.addPropertyValue("cronExpression", m.getAnnotation(QuartzScheduled.class).value());
            defaultListableBeanFactory.registerBeanDefinition(triggerBeanName, triggerBuilder.getBeanDefinition());

            triggers.add(applicationContext.getBean(triggerBeanName, Trigger.class));
        });
        return bean;
    }

    @Override
    public void destroy() throws Exception {
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (event.getApplicationContext() == this.applicationContext) {
            finishRegistration();
        }
    }

    private void finishRegistration() {
        if (!triggers.isEmpty()) {
            BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(SchedulerFactoryBean.class);
            ThreadPoolTaskExecutor taskExecutor = defaultListableBeanFactory.getBean(ThreadPoolTaskExecutor.class);
            if (taskExecutor != null) {
                builder.addPropertyValue("taskExecutor", taskExecutor);
            }
            builder.addPropertyValue("triggers", triggers.toArray());
            builder.addPropertyValue("overwriteExistingJobs", true);
            builder.addPropertyValue("applicationContextSchedulerContextKey", "applicationContext");

            builder.addPropertyValue("quartzProperties", quartzProperties());
            String beanName = "scheduledExecutorFactoryBean";
            defaultListableBeanFactory.registerBeanDefinition(beanName, builder.getBeanDefinition());
            try {
                applicationContext.getBean(beanName, Scheduler.class).start();
            } catch (BeansException | SchedulerException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private Properties quartzProperties() {
        Properties prop = new Properties();
        prop.put("quartz.scheduler.instanceName", quartzInstanceName);
        prop.put("org.quartz.scheduler.instanceId", "AUTO");
        prop.put("org.quartz.scheduler.skipUpdateCheck", "true");

        if (cluster) {
            prop.put("org.quartz.jobStore.class", "org.quartz.impl.jdbcjobstore.JobStoreTX");
            prop.put("org.quartz.jobStore.driverDelegateClass", "org.quartz.impl.jdbcjobstore.StdJDBCDelegate");
            prop.put("org.quartz.jobStore.dataSource", "myDS");
            prop.put("org.quartz.jobStore.tablePrefix", "QRTZ_");
            prop.put("org.quartz.jobStore.isClustered", "true");
            prop.put("org.quartz.jobStore.useProperties", "false");

            prop.put("org.quartz.jobStore.clusterCheckinInterval", "20000");
            prop.put("org.quartz.jobStore.maxMisfiresToHandleAtATime", "1");
            prop.put("org.quartz.jobStore.misfireThreshold", "120000");

            prop.put("org.quartz.dataSource.myDS.driver", myDSDriver);
            prop.put("org.quartz.dataSource.myDS.URL", myDSURL);
            prop.put("org.quartz.dataSource.myDS.user", myDSUser);
            prop.put("org.quartz.dataSource.myDS.password", myDSPassword);
            prop.put("org.quartz.dataSource.myDS.maxConnections", myDSMaxConnections);
            prop.put("org.quartz.dataSource.myDS.validationQuery", "select 0");
        }

        prop.put("org.quartz.plugin.shutdownhook.class", "org.quartz.plugins.management.ShutdownHookPlugin");
        prop.put("org.quartz.plugin.shutdownhook.cleanShutdown", "true");
        return prop;
    }
}
