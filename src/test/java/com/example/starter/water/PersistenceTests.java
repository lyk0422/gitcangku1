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
                new BigDecimal("10.000"), 2, 1L);
        long targetWindowId = repo1.insertWindow("wk-persist-2", "ch-persist", 3_000L, 4_000L,
                new BigDecimal("5.000"), 2, 1L);
        repo1.insertAllocation("ak-persist", windowId, "user-1", new BigDecimal("3.500"), "alice", false, 2L);
        AllocationRow submitted = repo1.findAllocationByKey("ak-persist");
        repo1.updateAllocationStatus(submitted.id(), "APPROVED", 3L);
        repo1.insertCurtailment(windowId, new BigDecimal("8.000"), 4L);
        repo1.insertCommand("cmd-persist", "ALLOCATION_SUBMIT", "ALLOCATION_SUBMIT|ak-persist", 5L);
        repo1.updateCommandResponse("cmd-persist", "{\"status\":\"REQUESTED\"}");
        // 结转：源申请扣减 1.500，目标窗口新建 APPROVED 申请并写入流水
        repo1.deductCarryableRemainder(submitted.id(), new BigDecimal("1.500"), 6L);
        long targetAllocationId = repo1.insertAllocation("carryover:co-persist", targetWindowId, "user-1",
                new BigDecimal("1.500"), "user-1", true, 6L);
        repo1.insertCarryover("co-persist", "user-1", submitted.id(), windowId, targetWindowId,
                targetAllocationId, new BigDecimal("1.500"), 6L);

        // 第二次“运行”：全新连接与仓储实例读取同一文件库
        WaterRepository repo2 = new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        WindowRow window = repo2.findWindowById(windowId);
        assertNotNull(window);
        assertEquals(new BigDecimal("10.000"), window.plannedVolume());
        assertEquals("wk-persist", window.windowKey());
        assertEquals(2, window.quarter());

        AllocationRow allocation = repo2.findAllocationByKey("ak-persist");
        assertNotNull(allocation);
        assertEquals("APPROVED", allocation.status());
        assertEquals(new BigDecimal("3.500"), allocation.amount());
        assertEquals(new BigDecimal("1.500"), allocation.carriedOut());
        assertEquals("alice", allocation.requester());

        CurtailmentRow curtailment = repo2.findActiveCurtailment(windowId);
        assertNotNull(curtailment);
        assertEquals(new BigDecimal("8.000"), curtailment.volume());

        assertEquals(new BigDecimal("3.500"), repo2.sumApprovedAmount(windowId));
        assertEquals("{\"status\":\"REQUESTED\"}", repo2.findCommand("cmd-persist").response());

        CarryoverRow carryover = repo2.findCarryoverByKey("co-persist");
        assertNotNull(carryover);
        assertEquals(windowId, carryover.sourceWindowId());
        assertEquals(targetWindowId, carryover.targetWindowId());
        assertEquals(new BigDecimal("1.500"), carryover.amount());
        assertEquals("user-1", carryover.userId());
        AllocationRow targetAllocation = repo2.findAllocationByKey("carryover:co-persist");
        assertNotNull(targetAllocation);
        assertEquals("APPROVED", targetAllocation.status());
        assertEquals(new BigDecimal("1.500"), repo2.sumApprovedAmount(targetWindowId));
    }
}
