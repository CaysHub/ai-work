package cn.com.servyou.work.core.config;

import cn.com.servyou.work.core.imbot.FeishuBotChannel;
import cn.com.servyou.work.core.imbot.ImBotRuntime;
import cn.com.servyou.work.core.imbot.WecomBotChannel;
import cn.com.servyou.work.web.controller.ImBotController;
import cn.com.servyou.work.web.controller.ImBotDocumentController;
import cn.com.servyou.work.web.controller.ImBotTakeoverController;
import cn.com.servyou.work.web.controller.ImBotToolController;
import cn.com.servyou.work.web.controller.admin.AdminImBotController;
import cn.com.servyou.work.web.controller.admin.AdminImBotTakeoverController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.SchedulingConfiguration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 使用真实组件扫描和条件装配，验证机器人关闭不缺依赖、开启能注册接口和调度。 */
class ImBotModuleConfigTest {

    /** 整个机器人模块的启动开关。 */
    private static final String ENABLED = "servyou.work.im-bot.enabled";
    /** 机器人业务组件的扫描范围。 */
    private static final String CORE_PACKAGE = "cn.com.servyou.work.core.imbot";
    /** 用户端与管理端的机器人接口。 */
    private static final Class<?>[] CONTROLLERS = {
            ImBotController.class, ImBotDocumentController.class, ImBotTakeoverController.class,
            ImBotToolController.class, AdminImBotController.class, AdminImBotTakeoverController.class
    };

    @Test
    void missingSwitchStartsWithoutBotDependenciesOrScheduledTasks() throws Exception {
        assertDisabled(new MockEnvironment());
    }

    @Test
    void explicitFalseStartsWithoutBotDependenciesOrScheduledTasks() throws Exception {
        assertDisabled(new MockEnvironment().withProperty(ENABLED, "false"));
    }

    @Test
    void localConfigurationDisablesModuleByDefault() throws Exception {
        Properties defaults = new Properties();
        try (InputStream input = new ClassPathResource("spring/local.properties").getInputStream()) {
            defaults.load(input);
        }
        assertEquals("false", defaults.getProperty(ENABLED));
        assertDisabled(new MockEnvironment().withProperty(ENABLED, defaults.getProperty(ENABLED)));
    }

    @Test
    void explicitTrueLoadsRealComponentsRoutesAndBothScheduledTasks() throws Exception {
        MockEnvironment environment = new MockEnvironment().withProperty(ENABLED, "true");
        List<Class<?>> components = components();
        try (AnnotationConfigApplicationContext context = context(environment, components)) {
            for (Class<?> type : components) {
                assertEquals(1, context.getBeansOfType(type).size(), type.getName());
            }
            assertTrue(context.containsBean("imBotInboundExecutor"));
            assertEquals(2, context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().size());
            RequestMappingHandlerMapping mapping = mappings(context);
            for (Class<?> controller : CONTROLLERS) {
                assertTrue(mapping.getHandlerMethods().values().stream()
                        .anyMatch(handler -> handler.getBeanType().equals(controller)), controller.getName());
            }
        }
    }

    /** 关闭时不提供数据库、账号、存储及模型依赖，仍须能刷新和关闭真实Spring上下文。 */
    private void assertDisabled(MockEnvironment environment) throws Exception {
        try (AnnotationConfigApplicationContext context = context(environment, new ArrayList<>())) {
            for (Class<?> type : components()) {
                assertTrue(context.getBeansOfType(type).isEmpty(), type.getName());
            }
            assertFalse(context.containsBean("imBotInboundExecutor"));
            assertTrue(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().isEmpty());
            assertTrue(mappings(context).getHandlerMethods().isEmpty());
            for (String shared : Arrays.asList("taskExecutor", "conversationExecutor", "opencodeSseExecutor",
                    "expertCompensationExecutor")) {
                assertNotNull(context.getBean(shared, ThreadPoolTaskExecutor.class));
            }
        }
    }

    /** 开启时发现所有真实机器人组件，避免漏掉嵌套连接工厂或后续新增组件。 */
    private List<Class<?>> components() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(
                true, new MockEnvironment().withProperty(ENABLED, "true"));
        List<Class<?>> types = new ArrayList<>(Arrays.asList(CONTROLLERS));
        for (BeanDefinition definition : scanner.findCandidateComponents(CORE_PACKAGE)) {
            types.add(Class.forName(definition.getBeanClassName()));
        }
        assertTrue(types.contains(ImBotRuntime.class));
        assertTrue(types.contains(WecomBotChannel.Factory.class));
        assertTrue(types.contains(FeishuBotChannel.Factory.class));
        return types;
    }

    /** 只隔离模块外部依赖；调度器不执行任务，防止装配测试访问平台或真实数据。 */
    private AnnotationConfigApplicationContext context(MockEnvironment environment, List<Class<?>> components) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setEnvironment(environment);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(scheduler.getClock()).thenReturn(Clock.systemUTC());
        context.getBeanFactory().registerSingleton("taskScheduler", scheduler);
        context.register(ThreadPoolConfig.class, AgentPlatformProperties.class, SchedulingConfiguration.class);
        Set<Class<?>> mocked = new HashSet<>();
        for (Class<?> type : components) {
            Set<Class<?>> dependencies = new HashSet<>();
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                dependencies.addAll(Arrays.asList(constructor.getParameterTypes()));
            }
            for (Field field : type.getDeclaredFields()) {
                if (field.isAnnotationPresent(Autowired.class)) dependencies.add(field.getType());
            }
            for (Class<?> dependency : dependencies) {
                if (Environment.class.isAssignableFrom(dependency)
                        || ThreadPoolTaskExecutor.class.isAssignableFrom(dependency)
                        || components.stream().anyMatch(dependency::isAssignableFrom)
                        || !mocked.add(dependency)) continue;
                context.getBeanFactory().registerSingleton(dependency.getName(), mock(dependency));
            }
        }
        context.scan(CORE_PACKAGE);
        ClassPathBeanDefinitionScanner webScanner = new ClassPathBeanDefinitionScanner(context, false);
        for (Class<?> controller : CONTROLLERS) webScanner.addIncludeFilter(new AssignableTypeFilter(controller));
        webScanner.scan("cn.com.servyou.work.web.controller");
        try {
            context.refresh();
            return context;
        } catch (RuntimeException failure) {
            context.close();
            throw failure;
        }
    }

    /** 使用Spring MVC实际解析映射，确认关闭后不会暴露机器人接口。 */
    private RequestMappingHandlerMapping mappings(AnnotationConfigApplicationContext context) {
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(context);
        mapping.afterPropertiesSet();
        return mapping;
    }
}
