package com.example.starter.evidence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 合成数据初始化（幂等）：本地运行与 H2 测试启动后即可演示完整跨案移交。
 * <ul>
 *   <li>案件：CASE-A（保管人 alice）→ CASE-B（保管人 bob），各自持有有效授权；</li>
 *   <li>移交令 ORDER-2026-001 v1：CASE-A → CASE-B，VALID，长期有效（validTo 为 null）；</li>
 *   <li>证物 EV-DEMO-001、EV-DEMO-002：在库 SEALED，位于库位 VAULT-A-01/02，封签版本 1。</li>
 * </ul>
 * 已存在的数据不重复插入；内存库随 JVM 退出而丢失，不提供跨进程恢复。
 */
@Component
@Order(0)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final JdbcTemplate jdbc;
    private final CaseCustodianGrantRepository grantRepository;
    private final TransferOrderRepository orderRepository;
    private final EvidenceClock clock;

    public DemoDataSeeder(JdbcTemplate jdbc,
                          CaseCustodianGrantRepository grantRepository,
                          TransferOrderRepository orderRepository,
                          EvidenceClock clock) {
        this.jdbc = jdbc;
        this.grantRepository = grantRepository;
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        LocalDateTime now = clock.nowUtc();
        seedGrant("CASE-A", "alice", now);
        seedGrant("CASE-B", "bob", now);
        seedGrant("CASE-1", "alice", now);
        if (orderRepository.findByVersion("ORDER-2026-001", 1).isEmpty()) {
            // 2000-01-01T00:00:00Z 起生效，validTo=null 表示长期有效。
            orderRepository.insert("ORDER-2026-001", 1, "CASE-A", "CASE-B",
                    OrderStatus.VALID, LocalDateTime.of(2000, 1, 1, 0, 0), null, now);
        }
        seedEvidence("EV-DEMO-001", "VAULT-A-01", now);
        seedEvidence("EV-DEMO-002", "VAULT-A-02", now);
        log.info("合成数据初始化完成（幂等）");
    }

    private void seedGrant(String caseKey, String custodianId, LocalDateTime now) {
        if (grantRepository.findActive(caseKey, custodianId).isEmpty()) {
            try {
                grantRepository.insert(caseKey, custodianId, now);
            } catch (DuplicateKeyException ignored) {
                // 并发启动或重复初始化：唯一约束兜底，忽略。
            }
        }
    }

    private void seedEvidence(String evidenceKey, String location, LocalDateTime now) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence WHERE evidence_key = ?", Integer.class, evidenceKey);
        if (count != null && count > 0) {
            return;
        }
        try {
            jdbc.update("""
                            INSERT INTO evidence
                                (evidence_key, case_key, category, seal_no, custodian_id, status,
                                 location, seal_version, created_at, updated_at)
                            VALUES (?, 'CASE-A', 'DOCUMENT', ?, 'alice', 'SEALED', ?, 1, ?, ?)
                            """,
                    evidenceKey, "SEAL-" + evidenceKey, location, now, now);
        } catch (DuplicateKeyException ignored) {
            // 唯一约束兜底，忽略。
        }
    }
}
