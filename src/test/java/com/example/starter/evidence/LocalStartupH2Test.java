package com.example.starter.evidence;

import com.example.starter.StarterApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地启动入口冒烟测试：直接加载 src/main/resources/application.yaml 的默认配置，
 * 验证不提供 DB_URL 等环境变量时，应用使用嵌入式 H2（MODE=MySQL）启动并自动建表。
 * 上下文由测试框架管理，方法结束即关闭，不启动常驻服务。
 */
class LocalStartupH2Test {

    @Test
    void applicationStartsWithEmbeddedH2DefaultsAndInitializesSchema() throws Exception {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                StarterApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.config.location=file:src/main/resources/application.yaml")
                .run()) {

            DataSource dataSource = context.getBean(DataSource.class);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            // 借出表与既有表均由 schema.sql 自动建成
            for (String table : List.of("evidence", "transfer_record", "seal_inspection",
                    "loan_record", "command_log")) {
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + table, Integer.class);
                assertThat(count).isZero();
            }

            // 默认数据源为 H2 内存库（MySQL 兼容模式）
            try (java.sql.Connection connection = dataSource.getConnection()) {
                assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("H2");
                assertThat(connection.getMetaData().getURL()).contains("jdbc:h2:mem:");
            }
            Map<String, Object> mode = jdbc.queryForMap(
                    "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS "
                            + "WHERE SETTING_NAME = 'MODE'");
            assertThat(String.valueOf(mode.get("SETTING_VALUE")).toUpperCase()).contains("MYSQL");

            if (dataSource instanceof EmbeddedDatabase embedded) {
                embedded.shutdown();
            }
        }
    }
}
