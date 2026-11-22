package cn.com.servyou.work.core.imbot;

import cn.com.servyou.work.ServyouWorkBackendApplication;
import cn.com.servyou.work.dao.mapper.ImBotMapper;
import cn.com.servyou.work.dao.mapper.ImBotMessageMapper;
import cn.com.servyou.work.dao.mapper.ImBotSessionMapper;
import cn.com.servyou.work.dao.mapper.ImBotUserMapper;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ImBotMapperRegistrationTest {

    @Test
    void autoConfigurationRegistersAllBotMappers() {
        new ApplicationContextRunner()
                .withInitializer(context -> AutoConfigurationPackages.register((BeanDefinitionRegistry) context.getBeanFactory(),
                        ServyouWorkBackendApplication.class.getPackage().getName()))
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        MybatisPlusAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:im_bot_mapper_registration",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "mybatis-plus.mapper-locations=classpath*:/mapper/**/*.xml")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ImBotMapper.class);
                    assertThat(context).hasSingleBean(ImBotMessageMapper.class);
                    assertThat(context).hasSingleBean(ImBotSessionMapper.class);
                    assertThat(context).hasSingleBean(ImBotUserMapper.class);
                });
    }
}
