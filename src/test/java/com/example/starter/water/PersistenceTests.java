package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.UsageRow;
import com.example.starter.water.WaterRepository.WindowRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 持久化验证：同一 JVM 内使用命名 H2 内存库（MODE=MySQL），建表写入后以全新连接与仓储实例
 * 重新读取，验证持有额度/累计已用口径、转让与核销流水、命令日志保持一致。本题不要求跨进程重启恢复。
 */
class PersistenceTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:persist-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void heldUsedAmountsTransfersAndUsagesAreReadableFromNewConnection() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));

        // 第一次“运行”：建表并写入窗口、已批准申请、转让扣减、核销扣减、生效限供与命令
        SimpleDriverDataSource first = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = first.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo1 = new WaterRepository(new JdbcTemplate(first));
        long windowId = repo1.insertWindow("wk-persist", "ch-persist", 1_000L, 2_000L,
                new BigDecimal("10.000"), 1L);
        repo1.insertAllocation("ak-source", windowId, "user-1", new BigDecimal("6.000"), "alice", 2L);
        repo1.insertAllocation("ak-target", windowId, "user-2", new BigDecimal("2.500"), "bob", 3L);
        AllocationRow source = repo1.findAllocationByKey("ak-source");
        AllocationRow target = repo1.findAllocationByKey("ak-target");
        // 普通批准：源持有额度等于原申请水量，已用量初始为 0
        repo1.updateAllocationStatus(source.id(), "APPROVED", 4L);
        // 转让 2.5：源持有 3.5、目标 APPROVED 持有 2.5、写不可变转让流水
        repo1.decrementHeldAmount(source.id(), new BigDecimal("2.500"), 5L);
        repo1.updateAllocationStatus(target.id(), "APPROVED", 5L);
        repo1.insertTransfer("tk-persist", windowId, "ak-source", "ak-target",
                new BigDecimal("2.500"), "alice", 5L);
        // 核销 1.5：源持有 3.5 -> 2.0、累计已用 0 -> 1.5、写不可变核销流水
        repo1.consumeHeldAmount(source.id(), new BigDecimal("1.500"), 6L);
        repo1.insertUsage("uk-persist", "ak-source", windowId, new BigDecimal("1.500"), "alice", 6L);
        repo1.insertCurtailment(windowId, new BigDecimal("8.000"), 7L);
        repo1.insertCommand("cmd-persist", "TRANSFER",
                "TRANSFER|tk-persist|ak-source|ak-target|alice", 8L);
        repo1.updateCommandResponse("cmd-persist", "{\"transferKey\":\"tk-persist\"}");

        // 第二次读取：全新连接与仓储实例，同一 JVM 内数据仍在
        WaterRepository repo2 = new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        WindowRow window = repo2.findWindowById(windowId);
        assertNotNull(window);
        assertEquals(new BigDecimal("10.000"), window.plannedVolume());
        assertEquals("wk-persist", window.windowKey());

        AllocationRow source2 = repo2.findAllocationByKey("ak-source");
        assertNotNull(source2);
        assertEquals("APPROVED", source2.status());
        // 原申请水量不可改写
        assertEquals(new BigDecimal("6.000"), source2.amount());
        // 转出 2.5、核销 1.5 后持有 2.0，累计已用 1.5
        assertEquals(new BigDecimal("2.000"), source2.heldAmount());
        assertEquals(new BigDecimal("1.500"), source2.usedAmount());
        assertEquals("alice", source2.requester());

        AllocationRow target2 = repo2.findAllocationByKey("ak-target");
        assertEquals("APPROVED", target2.status());
        assertEquals(new BigDecimal("2.500"), target2.amount());
        assertEquals(new BigDecimal("2.500"), target2.heldAmount());
        assertEquals(0, target2.usedAmount().compareTo(BigDecimal.ZERO));

        // 容量统计：APPROVED 持有（未用）额度 = 2.0 + 2.5 = 4.5；全部申请累计已用 = 1.5
        assertEquals(new BigDecimal("4.500"), repo2.sumApprovedAmount(windowId));
        assertEquals(new BigDecimal("1.500"), repo2.sumUsedAmount(windowId));

        TransferRow transfer = repo2.findTransferByKey("tk-persist");
        assertNotNull(transfer);
        assertEquals(windowId, transfer.windowId());
        assertEquals("ak-source", transfer.sourceAllocationKey());
        assertEquals("ak-target", transfer.targetAllocationKey());
        assertEquals(new BigDecimal("2.500"), transfer.amount());
        assertEquals(1, repo2.listTransfers(windowId).size());

        UsageRow usage = repo2.findUsageByKey("uk-persist");
        assertNotNull(usage);
        assertEquals(windowId, usage.windowId());
        assertEquals("ak-source", usage.allocationKey());
        assertEquals(new BigDecimal("1.500"), usage.amount());
        assertEquals("alice", usage.actor());
        List<UsageRow> usages = repo2.listUsages(windowId);
        assertEquals(1, usages.size());
        assertEquals(1, repo2.listUsagesByAllocation("ak-source").size());
        assertEquals(0, repo2.listUsagesByAllocation("ak-target").size());

        CurtailmentRow curtailment = repo2.findActiveCurtailment(windowId);
        assertNotNull(curtailment);
        assertEquals(new BigDecimal("8.000"), curtailment.volume());

        assertEquals("{\"transferKey\":\"tk-persist\"}", repo2.findCommand("cmd-persist").response());
    }

    @Test
    void cancelledAllocationUsedAmountStillOccupiesWindow() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo = new WaterRepository(new JdbcTemplate(dataSource));
        long windowId = repo.insertWindow("wk-cancel", "ch-cancel", 1L, 2L, new BigDecimal("5"), 1L);
        repo.insertAllocation("ak-cancel", windowId, "user-1", new BigDecimal("3.000"), "alice", 2L);
        AllocationRow allocation = repo.findAllocationByKey("ak-cancel");
        repo.updateAllocationStatus(allocation.id(), "APPROVED", 3L);
        // 已占用 = 持有 3 + 已用 0 = 3
        assertEquals(new BigDecimal("3.000"), repo.sumApprovedAmount(windowId));
        assertEquals(0, repo.sumUsedAmount(windowId).compareTo(BigDecimal.ZERO));
        // 核销 1：持有 2、已用 1，占用总量不变
        repo.consumeHeldAmount(allocation.id(), new BigDecimal("1.000"), 4L);
        repo.insertUsage("uk-cancel", "ak-cancel", windowId, new BigDecimal("1.000"), "alice", 4L);
        assertEquals(new BigDecimal("2.000"), repo.sumApprovedAmount(windowId));
        assertEquals(new BigDecimal("1.000"), repo.sumUsedAmount(windowId));
        // 取消：未用持有额度 2 归零释放，已用 1 保留并继续占用窗口容量
        repo.updateAllocationStatus(allocation.id(), "CANCELLED", 5L);
        AllocationRow cancelled = repo.findAllocationByKey("ak-cancel");
        assertEquals("CANCELLED", cancelled.status());
        assertEquals(new BigDecimal("3.000"), cancelled.amount());
        assertEquals(0, cancelled.heldAmount().compareTo(BigDecimal.ZERO));
        assertEquals(new BigDecimal("1.000"), cancelled.usedAmount());
        assertEquals(0, repo.sumApprovedAmount(windowId).compareTo(BigDecimal.ZERO));
        assertEquals(new BigDecimal("1.000"), repo.sumUsedAmount(windowId));
        // 核销流水不可变、不随取消删除
        assertEquals(1, repo.listUsages(windowId).size());
    }
}
