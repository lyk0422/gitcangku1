package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.TransferRow;
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
                new BigDecimal("10.000"), 1L);
        repo1.insertAllocation("ak-persist", windowId, "user-1", new BigDecimal("3.500"), "alice", 2L);
        repo1.insertAllocation("ak-persist-target", windowId, "user-2", new BigDecimal("1.250"), "bob", 2L);
        AllocationRow submitted = repo1.findAllocationByKey("ak-persist");
        AllocationRow target = repo1.findAllocationByKey("ak-persist-target");
        // 普通批准：持有额度 = 原申请水量
        repo1.updateAllocationStatus(submitted.id(), "APPROVED", new BigDecimal("3.500"), 3L);
        // 模拟同窗口转让：源扣减 1.250、目标批准持有 1.250、写不可变流水
        repo1.decreaseHeldAmountIfSufficient(submitted.id(), new BigDecimal("1.250"), 6L);
        repo1.approveTargetWithHeldAmount(target.id(), new BigDecimal("1.250"), 6L);
        repo1.insertTransfer("tk-persist", windowId, submitted.id(), target.id(),
                new BigDecimal("1.250"), new BigDecimal("2.250"), 6L);
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
        assertEquals(new BigDecimal("2.250"), allocation.heldAmount());
        assertEquals("alice", allocation.requester());

        AllocationRow targetAfter = repo2.findAllocationByKey("ak-persist-target");
        assertNotNull(targetAfter);
        assertEquals("APPROVED", targetAfter.status());
        assertEquals(new BigDecimal("1.250"), targetAfter.amount());
        assertEquals(new BigDecimal("1.250"), targetAfter.heldAmount());

        TransferRow transfer = repo2.findTransferByKey("tk-persist");
        assertNotNull(transfer);
        assertEquals(new BigDecimal("1.250"), transfer.amount());
        assertEquals(new BigDecimal("2.250"), transfer.sourceHeldAfter());
        assertEquals(submitted.id(), transfer.sourceAllocationId());
        assertEquals(target.id(), transfer.targetAllocationId());

        CurtailmentRow curtailment = repo2.findActiveCurtailment(windowId);
        assertNotNull(curtailment);
        assertEquals(new BigDecimal("8.000"), curtailment.volume());

        // 容量统计汇总 APPROVED 的当前持有额度：2.250 + 1.250 = 3.500
        assertEquals(new BigDecimal("3.500"), repo2.sumApprovedAmount(windowId));
        assertEquals(new BigDecimal("4.750"), repo2.sumApprovedOriginalAmount(windowId));
        assertEquals("{\"status\":\"REQUESTED\"}", repo2.findCommand("cmd-persist").response());
    }
}
