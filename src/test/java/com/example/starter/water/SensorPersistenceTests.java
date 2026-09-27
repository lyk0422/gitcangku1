package com.example.starter.water;

import com.example.starter.water.SensorRepository.AnomalyRow;
import com.example.starter.water.SensorRepository.ChannelRow;
import com.example.starter.water.SensorRepository.ReadingRow;
import com.example.starter.water.SensorRepository.ResolutionRow;
import com.example.starter.water.SensorRepository.SensorRuleRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 传感异常新表的持久化验证：同一 JVM 内命名 H2 内存库（MODE=MySQL），
 * 验证规则版本/读数/异常/冻结/解除/核销的跨连接读取、唯一约束、CHECK 约束与解除不改写原证据。
 */
class SensorPersistenceTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:sensor-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private SimpleDriverDataSource newDatabase(String url) {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return dataSource;
    }

    private SensorRepository repo(SimpleDriverDataSource ds) {
        return new SensorRepository(new JdbcTemplate(ds));
    }

    @Test
    void sensorEvidenceIsReadableFromNewConnectionAndResolutionKeepsAnomalyImmutable() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        SimpleDriverDataSource ds = newDatabase(url);
        SensorRepository r = repo(ds);

        r.insertChannel("ch-ev", 1L);
        r.insertSensorRule("ch-ev", 1, new BigDecimal("2.500000"), 30, "alice", 2L);
        r.insertSensorRule("ch-ev", 2, new BigDecimal("1.000000"), 45, "bob", 3L);
        long r1 = r.insertReading("bk-1", "ch-ev", 0, 100L, new BigDecimal("100.000"), 1, "alice", 4L);
        long r2 = r.insertReading("bk-1", "ch-ev", 1, 200L, new BigDecimal("90.000"), 1, "alice", 4L);

        AnomalyRow anomaly = new AnomalyRow(0L, "ch-ev", "bk-1", 1, r1, r2, 100L, 200L,
                new BigDecimal("100.000"), new BigDecimal("90.000"), "[\"REGRESSION\"]",
                new BigDecimal("-10.000"), new BigDecimal("0.001667"), new BigDecimal("-6.000000"),
                30, new BigDecimal("2.500000"), "alice", 4L);
        long anomalyId = r.insertAnomaly(anomaly);
        r.insertFreeze("ch-ev", anomalyId, 4L);
        // 解除前：存在未解除异常
        assertTrue(r.hasOpenAnomaly("ch-ev"));
        r.insertResolution(anomalyId, "ch-ev", "现场诊断", new BigDecimal("99.000"), 200L, "bob", 9L);
        // 解除后：无未解除异常
        assertFalse(r.hasOpenAnomaly("ch-ev"));

        // 全新连接重读
        SensorRepository r2repo = repo(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", ""));
        ChannelRow channel = r2repo.lockChannel("ch-ev");
        assertNotNull(channel);
        SensorRuleRow latest = r2repo.findLatestSensorRule("ch-ev");
        assertEquals(2, latest.ruleVersion());
        assertEquals(new BigDecimal("1.000000"), latest.maxFlowRate());

        // 读数跨连接、按时刻稳定排序
        List<ReadingRow> readings = r2repo.listReadingsByChannel("ch-ev");
        assertEquals(2, readings.size());
        assertEquals(100L, readings.get(0).readingNanos());
        assertEquals("bk-1", readings.get(1).batchKey());

        // 解除不改写原异常证据
        AnomalyRow stored = r2repo.findAnomalyById(anomalyId);
        assertEquals("[\"REGRESSION\"]", stored.reasons());
        assertEquals(new BigDecimal("-10.000"), stored.actualDelta());
        assertEquals("alice", stored.detectorActor());
        ResolutionRow resolution = r2repo.findResolutionByAnomaly(anomalyId);
        assertNotNull(resolution);
        assertEquals(new BigDecimal("99.000"), resolution.replacementCumulative());
        assertEquals("bob", resolution.resolverActor());
        assertEquals(200L, resolution.replacementNanos());

        // 诊断排序：异常按主键
        assertEquals(1, r2repo.listAnomaliesByChannel("ch-ev").size());
    }

    @Test
    void uniqueAndCheckConstraintsAreEnforcedByH2() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        SimpleDriverDataSource ds = newDatabase(url);
        SensorRepository r = repo(ds);
        WaterRepository water = new WaterRepository(new JdbcTemplate(ds));

        r.insertChannel("ch-db", 1L);
        r.insertSensorRule("ch-db", 1, new BigDecimal("2"), 30, "alice", 2L);
        // (渠道, 版本号) 唯一
        assertThrows(DuplicateKeyException.class, () ->
                r.insertSensorRule("ch-db", 1, new BigDecimal("3"), 10, "bob", 3L));

        r.insertReading("bk-a", "ch-db", 0, 100L, new BigDecimal("10"), 1, "alice", 4L);
        // (渠道, 读数时刻) 唯一
        assertThrows(DuplicateKeyException.class, () ->
                r.insertReading("bk-b", "ch-db", 0, 100L, new BigDecimal("11"), 1, "bob", 5L));
        // (批次键, 批内序号) 唯一
        assertThrows(DuplicateKeyException.class, () ->
                r.insertReading("bk-a", "ch-db", 0, 200L, new BigDecimal("12"), 1, "alice", 6L));
        // 累计读数非负 CHECK
        assertThrows(DataIntegrityViolationException.class, () ->
                r.insertReading("bk-c", "ch-db", 0, 300L, new BigDecimal("-1"), 1, "alice", 7L));

        long r1 = r.findLatestReading("ch-db").id();
        long r2id = r.insertReading("bk-d", "ch-db", 0, 400L, new BigDecimal("9"), 1, "alice", 8L);
        long anomalyId = r.insertAnomaly(new AnomalyRow(0L, "ch-db", "bk-d", 1, r1, r2id, 100L, 400L,
                new BigDecimal("10"), new BigDecimal("9"), "[\"REGRESSION\"]",
                new BigDecimal("-1"), new BigDecimal("0.000005"), new BigDecimal("-1"),
                30, new BigDecimal("2"), "alice", 8L));
        r.insertResolution(anomalyId, "ch-db", "诊断", new BigDecimal("9.5"), 400L, "bob", 9L);
        // 一条异常只能解除一次
        assertThrows(DuplicateKeyException.class, () ->
                r.insertResolution(anomalyId, "ch-db", "再次解除", new BigDecimal("9.6"), 400L,
                        "carol", 10L));

        // 核销流水：业务键唯一、amount > 0 CHECK
        long windowId = water.insertWindow("wk-s", "ch-db", 1L, 2L, new BigDecimal("100"), 1L);
        r.insertWriteOff("wo-1", "ak-1", windowId, "ch-db", new BigDecimal("5"), 500L, "alice", 11L);
        assertThrows(DuplicateKeyException.class, () ->
                r.insertWriteOff("wo-1", "ak-2", windowId, "ch-db", new BigDecimal("1"), 501L,
                        "alice", 12L));
        assertThrows(DataIntegrityViolationException.class, () ->
                r.insertWriteOff("wo-2", "ak-1", windowId, "ch-db", new BigDecimal("0"), 502L,
                        "alice", 13L));

        // 核销汇总与明细排序
        r.insertWriteOff("wo-3", "ak-1", windowId, "ch-db", new BigDecimal("2"), 600L, "alice", 14L);
        assertEquals(0, new BigDecimal("7").compareTo(r.sumWriteOffs("ak-1")));
        assertEquals(2, r.listWriteOffsByAllocation("ak-1").size());
        assertNull(r.findWriteOffByKey("missing"));
    }
}
