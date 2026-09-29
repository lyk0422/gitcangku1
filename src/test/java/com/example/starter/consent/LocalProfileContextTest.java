package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 验证 local profile 的本地入口：H2 内存库（MODE=MySQL）自动建表并初始化合成数据，
 * 不依赖外部 MySQL 或 Docker。使用独立命名内存库，与标准测试库隔离。
 */
@SpringBootTest
@ActiveProfiles("local")
class LocalProfileContextTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PurposeLedgerService ledgerService;

    @Test
    void localH2ContextStartsWithSchemaAndSyntheticSeedData() {
        // 自动建表成功且种子授权/记录存在
        Long grants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consent_grant WHERE subject_key = 'subj-demo'"
                        + " AND purpose = 'RESEARCH' AND epoch = 1 AND status = 'ACTIVE'",
                Long.class);
        Long records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consent_record WHERE subject_key = 'subj-demo' AND epoch = 1",
                Long.class);
        assertThat(grants).isEqualTo(1L);
        assertThat(records).isEqualTo(2L);

        // 合成额度 100、未使用；对合成对象做一次受限查询，余额变为 98 并写账本
        var quota = ledgerService.getQuota("subj-demo", Purpose.RESEARCH, 1);
        assertThat(quota.declared()).isTrue();
        assertThat(quota.quotaLimit()).isEqualTo(100L);
        assertThat(quota.remainingBalance()).isEqualTo(100L);

        var response = ledgerService.query(new com.example.starter.consent.dto.PurposeQueryRequest(
                "local-seed-query-1", "subj-demo", Purpose.RESEARCH, 1, 2,
                java.util.List.of("rec-2", "rec-1")));
        assertThat(response.quantity()).isEqualTo(2);
        assertThat(response.remainingBalance()).isEqualTo(98L);
        assertThat(ledgerService.listEntries("subj-demo", Purpose.RESEARCH, 1)).hasSize(1);
    }
}
