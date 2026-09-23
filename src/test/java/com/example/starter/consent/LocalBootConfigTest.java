package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.example.starter.consent.dto.BatchQueryItem;
import com.example.starter.consent.dto.BatchQueryRequest;
import com.example.starter.consent.dto.BatchQueryResponse;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.RecordWriteRequest;

import java.util.List;

/**
 * 验证本地默认启动入口：使用主 application.yaml 的 H2 默认配置与主资源 schema-h2.sql
 * （独立命名内存库），确认自动建表成功并能跑通真实授权→写入→批量查询链路。
 * 不依赖外部 MySQL 或 Docker。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:consent-bootcheck;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.schema-locations=classpath:schema-h2.sql"
})
class LocalBootConfigTest {

    @Autowired
    private ConsentService consentService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void h2DefaultSchemaInitializesAndBatchQueryWorks() {
        // 主 schema-h2.sql 自动建表成功后，三张表均可直接访问
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consent_grant", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consent_record", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_request", Integer.class)).isZero();

        consentService.grant(new GrantRequest("boot-g1", "boot-subj", Purpose.RESEARCH));
        consentService.write(new RecordWriteRequest(
                "boot-w1", "boot-subj", Purpose.RESEARCH, "boot-rec", "boot-payload"));

        BatchQueryResponse response = consentService.batchQuery(new BatchQueryRequest(
                "boot-bq1", Purpose.RESEARCH,
                List.of(new BatchQueryItem("boot-subj", 1, "boot-rec"))));
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).payload()).isEqualTo("boot-payload");
        assertThat(response.results().get(0).epoch()).isEqualTo(1);
    }
}
