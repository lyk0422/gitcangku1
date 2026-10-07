package com.example.starter.consent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 本题合成数据初始化：本地入口启动时为合成主体 A/B 建立研究授权代次 1，
 * 便于直接演示共同记录主流程；仅写入 H2 内存库，随 JVM 退出消失。
 * 测试环境通过 app.synthetic-seed.enabled=false 关闭，场景数据由用例自建自清。
 */
@Component
public class SyntheticDataSeeder implements ApplicationRunner {

    static final String SUBJECT_A = "subj-synthetic-a";
    static final String SUBJECT_B = "subj-synthetic-b";

    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public SyntheticDataSeeder(JdbcTemplate jdbc,
                               @Value("${app.synthetic-seed.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consent_grant WHERE subject_key IN (?, ?)",
                Integer.class, SUBJECT_A, SUBJECT_B);
        if (count != null && count > 0) {
            return;
        }
        jdbc.update(
                "INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)"
                        + " VALUES (?, 'RESEARCH', 1, 'ACTIVE', ?)",
                SUBJECT_A, "seed-synthetic-a");
        jdbc.update(
                "INSERT INTO consent_grant (subject_key, purpose, epoch, status, request_id)"
                        + " VALUES (?, 'RESEARCH', 1, 'ACTIVE', ?)",
                SUBJECT_B, "seed-synthetic-b");
    }
}
