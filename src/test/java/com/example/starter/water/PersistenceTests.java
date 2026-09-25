package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CarryoverRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.WindowRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 持久化验证：数据写入文件型数据库后，模拟重启（全新连接与仓储实例）重新读取，
 * 水量、限供与申请状态必须保持不变。
 */
class PersistenceTests {

    private static final String URL_PREFIX = "jdbc:h2:file:";
    private static final String URL_SUFFIX = ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";

    @Test
    void dataSurvivesReconnect() throws Exception {
        Path dir = Files.createDirectories(Path.of("build", "persist-test")).toAbsolutePath();
        String dbPath = dir.resolve("water-" + UUID.randomUUID()).toString().replace('\\', '/');
        String url = URL_PREFIX + dbPath + URL_SUFFIX;

        // 第一次“运行”：建表并写入窗口、已批准申请与生效限供
        SimpleDriverDataSource first = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = first.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo1 = new WaterRepository(new JdbcTemplate(first));
        long windowId = repo1.insertWindow("wk-persist", "ch-persist", 1_000L, 2_000L,
                new BigDecimal("10.000"), 3, 1L);
        repo1.insertAllocation("ak-persist", windowId, "user-1", new BigDecimal("3.500"), "alice", 2L);
        AllocationRow submitted = repo1.findAllocationByKey("ak-persist");
        repo1.updateAllocationStatus(submitted.id(), "APPROVED", 3L);
        repo1.insertCurtailment(windowId, new BigDecimal("8.000"), 4L);
        repo1.insertCommand("cmd-persist", "ALLOCATION_SUBMIT", "ALLOCATION_SUBMIT|ak-persist", 5L);
        repo1.updateCommandResponse("cmd-persist", "{\"status\":\"REQUESTED\"}");

        // 第二次“运行”：全新连接与仓储实例读取同一文件库
        WaterRepository repo2 = new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        WindowRow window = repo2.findWindowById(windowId);
        assertNotNull(window);
        assertEquals(new BigDecimal("10.000"), window.plannedVolume());
        assertEquals("wk-persist", window.windowKey());

        AllocationRow allocation = repo2.findAllocationByKey("ak-persist");
        assertNotNull(allocation);
        assertEquals("APPROVED", allocation.status());
        assertEquals(new BigDecimal("3.500"), allocation.amount());
        assertEquals("alice", allocation.requester());

        CurtailmentRow curtailment = repo2.findActiveCurtailment(windowId);
        assertNotNull(curtailment);
        assertEquals(new BigDecimal("8.000"), curtailment.volume());

        assertEquals(new BigDecimal("3.500"), repo2.sumApprovedAmount(windowId));
        assertEquals("{\"status\":\"REQUESTED\"}", repo2.findCommand("cmd-persist").response());
    }

    @Test
    void carryoverDataSurvivesReconnect() throws Exception {
        Path dir = Files.createDirectories(Path.of("build", "persist-test")).toAbsolutePath();
        String dbPath = dir.resolve("carryover-" + UUID.randomUUID()).toString().replace('\\', '/');
        String url = URL_PREFIX + dbPath + URL_SUFFIX;

        // 第一次“运行”：建表，写入两个同季度窗口、已批准申请、余量扣减与结转流水
        SimpleDriverDataSource first = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = first.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo1 = new WaterRepository(new JdbcTemplate(first));
        long sourceId = repo1.insertWindow("wk-co-src", "ch-co-src", 1_000L, 2_000L,
                new BigDecimal("10.000"), 2, 1L);
        long targetId = repo1.insertWindow("wk-co-tgt", "ch-co-tgt", 3_000L, 4_000L,
                new BigDecimal("5.000"), 2, 1L);
        repo1.insertAllocation("ak-co-src", sourceId, "user-1", new BigDecimal("4.000"), "alice", 2L);
        AllocationRow source = repo1.findAllocationByKey("ak-co-src");
        repo1.updateAllocationStatus(source.id(), "APPROVED", 3L);
        assertEquals(1, repo1.deductCarriedOut(source.id(), new BigDecimal("1.500")));
        // 余量不足时条件扣减必须失败（真实数据库约束语义）
        assertEquals(0, repo1.deductCarriedOut(source.id(), new BigDecimal("2.501")));
        repo1.insertApprovedAllocation("ck-co-1", targetId, "user-1", new BigDecimal("1.500"), "user-1", 4L);
        repo1.insertCarryover("ck-co-1", sourceId, targetId, "user-1", new BigDecimal("1.500"), "ck-co-1", 4L);

        // 第二次“运行”：全新连接与仓储实例读取同一文件库
        WaterRepository repo2 = new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        WindowRow sourceWindow = repo2.findWindowById(sourceId);
        assertNotNull(sourceWindow);
        assertEquals(2, sourceWindow.quarter());

        AllocationRow reloaded = repo2.findAllocationByKey("ak-co-src");
        assertNotNull(reloaded);
        assertEquals(new BigDecimal("4.000"), reloaded.amount());
        assertEquals(new BigDecimal("0.000"), reloaded.consumedVolume());
        assertEquals(new BigDecimal("1.500"), reloaded.carriedOutVolume());

        AllocationRow targetAllocation = repo2.findAllocationByKey("ck-co-1");
        assertNotNull(targetAllocation);
        assertEquals("APPROVED", targetAllocation.status());
        assertEquals(new BigDecimal("1.500"), repo2.sumApprovedAmount(targetId));

        CarryoverRow carryover = repo2.findCarryoverByKey("ck-co-1");
        assertNotNull(carryover);
        assertEquals(sourceId, carryover.sourceWindowId());
        assertEquals(targetId, carryover.targetWindowId());
        assertEquals("user-1", carryover.userId());
        assertEquals(new BigDecimal("1.500"), carryover.amount());
        assertEquals(1, repo2.listCarryoversByUser("user-1").size());
    }
}
