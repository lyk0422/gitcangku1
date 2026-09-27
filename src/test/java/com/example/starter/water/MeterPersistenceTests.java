package com.example.starter.water;

import com.example.starter.water.MeterRepository.AnomalyRow;
import com.example.starter.water.MeterRepository.ChannelRow;
import com.example.starter.water.MeterRepository.ReadingRow;
import com.example.starter.water.MeterRepository.ResolutionRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计量表持久化与约束验证：独立命名 H2 内存库（MODE=MySQL），直接验证 SQL 唯一约束、
 * CHECK 约束与事务回滚无半成品。这些数据库边界不被应用层提前拦截，必须在真实 H2 上验证。
 * 本题不要求跨进程重启恢复，内存数据随 JVM 退出丢失。
 */
class MeterPersistenceTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:meter-persist-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private MeterRepository newRepo(SimpleDriverDataSource ds) {
        try (Connection connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new MeterRepository(new JdbcTemplate(ds));
    }

    private SimpleDriverDataSource dataSource() {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        return new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
    }

    @Test
    void duplicateChannelRegistrationIsIgnoredAndUniqueConstraintsRejectDuplicates() {
        MeterRepository repo = newRepo(dataSource());
        assertTrue(repo.registerChannelIfAbsent("ch-1", "alice", 1L));
        // 重复注册幂等忽略，返回 false 且不改变首次注册信息
        assertFalse(repo.registerChannelIfAbsent("ch-1", "bob", 2L));
        ChannelRow channel = repo.findChannel("ch-1");
        assertEquals("alice", channel.createdBy());
        assertEquals(1L, channel.createdNanos());

        repo.insertRuleVersion("ch-1", 1, new BigDecimal("1.000000"), 10, "alice", 1L);
        // (channel_id, version) 唯一
        assertThrows(DuplicateKeyException.class,
                () -> repo.insertRuleVersion("ch-1", 1, new BigDecimal("2.000000"), 20, "bob", 2L));
        // 不同渠道可各自使用版本 1
        repo.registerChannelIfAbsent("ch-2", "bob", 1L);
        repo.insertRuleVersion("ch-2", 1, new BigDecimal("1.000000"), 10, "bob", 1L);

        ReadingRow r1 = repo.insertReading("ch-1", 100L, new BigDecimal("1.000"), 0, "alice", 1, "cmd-1", 1L);
        // 同渠道同测量时刻唯一
        assertThrows(DuplicateKeyException.class,
                () -> repo.insertReading("ch-1", 100L, new BigDecimal("2.000"), 0, "bob", 1, "cmd-2", 2L));
        // 不同渠道同刻允许
        repo.insertReading("ch-2", 100L, new BigDecimal("1.000"), 0, "bob", 1, "cmd-3", 1L);
        // CHECK：负累计值拒绝
        assertThrows(DataIntegrityViolationException.class,
                () -> repo.insertReading("ch-1", 200L, new BigDecimal("-0.001"), 0, "alice", 1, "cmd-4", 2L));

        ReadingRow r2 = repo.insertReading("ch-1", 200L, new BigDecimal("10.000"), 1, "alice", 1, "cmd-5", 2L);
        AnomalyRow anomaly = anomaly("ch-1", r1.id(), r2.id(), 100L, 200L);
        AnomalyRow inserted = repo.insertAnomaly(anomaly);
        // 同一 (reading_id, anomaly_type) 唯一：再插一条同读数同类型被拒
        assertThrows(DuplicateKeyException.class, () -> repo.insertAnomaly(
                anomaly("ch-1", r1.id(), r2.id(), 100L, 200L)));

        repo.insertResolution(new ResolutionRow(0L, inserted.id(), "ch-1", "bob", "诊断",
                150L, new BigDecimal("1.000"), "cmd-r", 3L));
        // 一条异常至多一次解除
        assertThrows(DuplicateKeyException.class, () -> repo.insertResolution(
                new ResolutionRow(0L, inserted.id(), "ch-1", "carol", "再次诊断",
                        160L, new BigDecimal("1.000"), "cmd-r2", 4L)));
    }

    @Test
    void failedTransactionLeavesNoPartialEvidence() {
        SimpleDriverDataSource ds = dataSource();
        MeterRepository repo = newRepo(ds);
        repo.registerChannelIfAbsent("ch-tx", "alice", 1L);
        repo.insertRuleVersion("ch-tx", 1, new BigDecimal("1.000000"), 10, "alice", 1L);
        repo.insertReading("ch-tx", 100L, new BigDecimal("1.000"), 0, "alice", 1, "cmd-init", 1L);

        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        assertThrows(DuplicateKeyException.class, () -> tx.executeWithoutResult(status -> {
            // 先插入一条合法读数……
            repo.insertReading("ch-tx", 200L, new BigDecimal("2.000"), 0, "alice", 1, "cmd-bad", 2L);
            // ……随后插入同刻读数触发唯一约束，整个事务回滚
            repo.insertReading("ch-tx", 200L, new BigDecimal("3.000"), 0, "alice", 1, "cmd-bad2", 2L);
        }));

        // 回滚后查不到任何半成品：链头仍是时刻 100 的读数
        ReadingRow head = repo.findLatestReading("ch-tx");
        assertEquals(100L, head.readingUtcNanos());
        assertEquals(0, new BigDecimal("1.000").compareTo(head.cumulativeVolume()));
        assertEquals(1, repo.listReadings("ch-tx").size());
        assertEquals(0, repo.countActiveAnomalies("ch-tx"));
        assertFalse(repo.channelFrozen("ch-tx"));
    }

    private AnomalyRow anomaly(String channelId, long previousReadingId, long readingId,
                               long previousUtc, long readingUtc) {
        return new AnomalyRow(0L, channelId, "FLOW_RATE", 1, previousReadingId, readingId,
                previousUtc, readingUtc, new BigDecimal("0.000"), new BigDecimal("10.000"),
                new BigDecimal("10.000"), new BigDecimal("10.000000000"), new BigDecimal("1.000000000"),
                new BigDecimal("0.900000"), 60, null, new BigDecimal("0.100000000"), null,
                "alice", "cmd-a", 2L);
    }
}
