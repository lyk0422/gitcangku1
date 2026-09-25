package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.GrantResponse;
import com.example.starter.consent.dto.RecordResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RecordViewResponse;
import com.example.starter.consent.dto.ScopeResponse;

/**
 * 验证本地启动入口（h2 profile）：嵌入式 H2（MODE=MySQL）自动建表并可完成真实业务调用。
 *
 * <p>使用独立命名内存库，避免与其他测试的 consent-test 库互相影响。
 */
@SpringBootTest
@ActiveProfiles("h2")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:local-profile-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LocalH2ProfileTest {

    @Autowired
    private ConsentService consentService;

    @Test
    void localH2ProfileAutoInitializesAndRunsBusinessFlow() {
        GrantResponse grant = consentService.grant(
                new GrantRequest("local-g-1", "local-subj", Purpose.RESEARCH));
        assertThat(grant.epoch()).isEqualTo(1);
        assertThat(grant.status()).isEqualTo(GrantStatus.ACTIVE);

        RecordResponse scopedRecord = consentService.write(
                new RecordWriteRequest("local-w-1", "local-subj", Purpose.RESEARCH,
                        "rec-m", "marketing-payload", "marketing", "营销子范围"));
        assertThat(scopedRecord.scopeKey()).isEqualTo("marketing");
        ScopeResponse scope = consentService.listScopes("local-subj", Purpose.RESEARCH, 1).get(0);
        assertThat(scope.scopeKey()).isEqualTo("marketing");
        assertThat(scope.status()).isEqualTo(ScopeStatus.ACTIVE);

        RecordResponse defaultRecord = consentService.write(
                new RecordWriteRequest("local-w-2", "local-subj", Purpose.RESEARCH,
                        "rec-d", "default-payload"));
        assertThat(defaultRecord.scopeKey()).isNull();

        List<RecordViewResponse> views = consentService.listEpochRecords("local-subj", Purpose.RESEARCH, 1);
        assertThat(views).hasSize(2);
        assertThat(views).allMatch(RecordViewResponse::usable);
    }
}
