package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.RainConfirmationRow;
import com.example.starter.water.WaterRepository.RainRecordRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.WaterRepository.WriteoffItemRow;
import com.example.starter.water.WaterRepository.WriteoffRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
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
 * 持久化验证：同一 JVM 内使用命名 H2 内存库（MODE=MySQL），建表写入后以全新连接与仓储实例
 * 重新读取，验证持有额度口径、转让流水、命令日志、降雨证据与核销快照保持一致。
 * 本题不要求跨进程重启恢复。
 */
class PersistenceTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:persist-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @Test
    void heldAmountsAndTransfersAreReadableFromNewConnection() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));

        // 第一次“运行”：建表并写入窗口、已批准申请、转让扣减、生效限供与命令
        SimpleDriverDataSource first = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = first.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo1 = new WaterRepository(new JdbcTemplate(first));
        long windowId = repo1.insertWindow("wk-persist", "ch-persist", 1_000L, 2_000L,
                new BigDecimal("10.000"), 1L);
        repo1.insertAllocation("ak-source", windowId, "user-1", new BigDecimal("6.000"), "alice",
                new BigDecimal("0.100000"), 2L);
        repo1.insertAllocation("ak-target", windowId, "user-2", new BigDecimal("2.500"), "bob",
                null, 3L);
        AllocationRow source = repo1.findAllocationByKey("ak-source");
        AllocationRow target = repo1.findAllocationByKey("ak-target");
        // 普通批准：源持有额度等于原申请水量
        repo1.updateAllocationStatus(source.id(), "APPROVED", 4L);
        // 转让 2.5：源持有 3.5、目标 APPROVED 持有 2.5、写不可变流水
        repo1.decrementHeldAmount(source.id(), new BigDecimal("2.500"), 5L);
        repo1.updateAllocationStatus(target.id(), "APPROVED", 5L);
        repo1.insertTransfer("tk-persist", windowId, "ak-source", "ak-target",
                new BigDecimal("2.500"), "alice", 5L);
        repo1.insertCurtailment(windowId, new BigDecimal("8.000"), 6L);
        repo1.insertCommand("cmd-persist", "TRANSFER",
                "TRANSFER|tk-persist|ak-source|ak-target|alice", 7L);
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
        // 转出后持有 3.5
        assertEquals(new BigDecimal("3.500"), source2.heldAmount());
        assertEquals("alice", source2.requester());
        // 每毫米抵扣系数持久化（DECIMAL(19,6)）
        assertEquals(new BigDecimal("0.100000"), source2.creditCoefficient());
        // null 系数语义稳定：表示不参与降雨抵扣
        assertNull(repo2.findAllocationByKey("ak-target").creditCoefficient());

        AllocationRow target2 = repo2.findAllocationByKey("ak-target");
        assertEquals("APPROVED", target2.status());
        assertEquals(new BigDecimal("2.500"), target2.amount());
        assertEquals(new BigDecimal("2.500"), target2.heldAmount());

        // 容量统计汇总 APPROVED 申请的当前持有额度：3.5 + 2.5 = 6
        assertEquals(new BigDecimal("6.000"), repo2.sumApprovedAmount(windowId));

        TransferRow transfer = repo2.findTransferByKey("tk-persist");
        assertNotNull(transfer);
        assertEquals(windowId, transfer.windowId());
        assertEquals("ak-source", transfer.sourceAllocationKey());
        assertEquals("ak-target", transfer.targetAllocationKey());
        assertEquals(new BigDecimal("2.500"), transfer.amount());
        assertEquals(1, repo2.listTransfers(windowId).size());

        CurtailmentRow curtailment = repo2.findActiveCurtailment(windowId);
        assertNotNull(curtailment);
        assertEquals(new BigDecimal("8.000"), curtailment.volume());

        assertEquals("{\"transferKey\":\"tk-persist\"}", repo2.findCommand("cmd-persist").response());
    }

    @Test
    void cancelledAllocationHeldAmountIsZero() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo = new WaterRepository(new JdbcTemplate(dataSource));
        long windowId = repo.insertWindow("wk-cancel", "ch-cancel", 1L, 2L, new BigDecimal("5"), 1L);
        repo.insertAllocation("ak-cancel", windowId, "user-1", new BigDecimal("3.000"), "alice", null, 2L);
        AllocationRow allocation = repo.findAllocationByKey("ak-cancel");
        repo.updateAllocationStatus(allocation.id(), "APPROVED", 3L);
        assertEquals(new BigDecimal("3.000"), repo.sumApprovedAmount(windowId));
        // 取消：持有额度归零，容量汇总不再计入
        repo.updateAllocationStatus(allocation.id(), "CANCELLED", 4L);
        AllocationRow cancelled = repo.findAllocationByKey("ak-cancel");
        assertEquals("CANCELLED", cancelled.status());
        // 原申请水量保留，持有额度归零
        assertEquals(new BigDecimal("3.000"), cancelled.amount());
        assertEquals(0, cancelled.heldAmount().compareTo(BigDecimal.ZERO));
        assertEquals(0, repo.sumApprovedAmount(windowId).compareTo(BigDecimal.ZERO));
    }

    @Test
    void rainConfirmationsEffectivenessAndUniqueWindowConstraintHoldOnH2() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo = new WaterRepository(new JdbcTemplate(dataSource));
        long w1 = repo.insertWindow("wk-rain1", "ch-rain1", 100L, 200L, new BigDecimal("10"), 1L);
        long w2 = repo.insertWindow("wk-rain2", "ch-rain2", 300L, 400L, new BigDecimal("10"), 1L);

        long rainId = repo.insertRainRecord("rk-1", w1, new BigDecimal("12.500"), 150L, "clerk", 10L);
        // 两名不同水务角色确认
        repo.insertRainConfirmation(rainId, "water-officer-a", 11L);
        // 同一操作人不能重复确认：唯一约束在 H2 上真实生效
        assertThrows(DuplicateKeyException.class,
                () -> repo.insertRainConfirmation(rainId, "water-officer-a", 12L));
        repo.insertRainConfirmation(rainId, "water-officer-b", 12L);
        repo.markRainEffective(rainId, 12L);

        RainRecordRow effective = repo.findRainByKey("rk-1");
        assertEquals("EFFECTIVE", effective.status());
        assertEquals(Long.valueOf(12L), effective.effectiveNanos());
        List<RainConfirmationRow> confirmations = repo.listRainConfirmations(rainId);
        assertEquals(2, confirmations.size());
        assertEquals("water-officer-a", confirmations.get(0).confirmer());
        assertEquals("water-officer-b", confirmations.get(1).confirmer());
        assertTrue(repo.existsEffectiveRain(w1));
        assertFalse(repo.existsEffectiveRain(w2));

        // 同窗口第二条记录不能再生效：生成列 + 唯一约束在 H2 上真实拦截
        long rainId2 = repo.insertRainRecord("rk-2", w1, new BigDecimal("3.000"), 160L, "clerk", 13L);
        assertThrows(DuplicateKeyException.class, () -> repo.markRainEffective(rainId2, 14L));
        // 另一窗口可以各自生效
        long rainOther = repo.insertRainRecord("rk-3", w2, new BigDecimal("1.000"), 350L, "clerk", 15L);
        repo.markRainEffective(rainOther, 16L);

        // 撤销：状态与撤销证据落库，生效时刻作为历史证据保留
        repo.markRainRevoked(rainId, 20L, "supervisor");
        RainRecordRow revoked = repo.findRainByKey("rk-1");
        assertEquals("REVOKED", revoked.status());
        assertEquals(Long.valueOf(20L), revoked.revokedNanos());
        assertEquals("supervisor", revoked.revokedBy());
        assertEquals(Long.valueOf(12L), revoked.effectiveNanos());
        // 撤销后同窗口可登记新生效记录
        repo.markRainEffective(rainId2, 21L);
        assertEquals("EFFECTIVE", repo.findRainByKey("rk-2").status());

        // 非正降雨量被 CHECK 约束拒绝
        assertThrows(Exception.class, () ->
                repo.insertRainRecord("rk-bad", w2, new BigDecimal("0.000"), 360L, "clerk", 17L));
    }

    @Test
    void writeoffSnapshotAndConditionalDeductionPersistOnH2() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo1 = new WaterRepository(new JdbcTemplate(dataSource));
        long windowId = repo1.insertWindow("wk-wo", "ch-wo", 1L, 2L, new BigDecimal("10.000"), 1L);
        repo1.insertRainRecord("rk-wo", windowId, new BigDecimal("10.000"), 1L, "clerk", 2L);
        repo1.insertAllocation("ak-wo", windowId, "user-1", new BigDecimal("5.000"), "alice",
                new BigDecimal("0.200000"), 3L);
        AllocationRow allocation = repo1.findAllocationByKey("ak-wo");
        repo1.updateAllocationStatus(allocation.id(), "APPROVED", 4L);

        // 核销 5：抵扣 10mm * 0.2 = 2，配额扣减 3，守恒 2 + 3 = 5
        long writeoffId = repo1.insertWriteoff("wo-1", windowId, new BigDecimal("5.000"),
                new BigDecimal("2.000"), new BigDecimal("3.000"), "alice", 5L);
        repo1.insertWriteoffItem(writeoffId, 0, "ak-wo", new BigDecimal("5.000"),
                new BigDecimal("2.000"), new BigDecimal("3.000"), new BigDecimal("0.200000"),
                "[{\"rainKey\":\"rk-wo\",\"rainMillis\":\"10\",\"credit\":\"2\"}]");
        assertTrue(repo1.deductHeldAmountIfSufficient(allocation.id(), new BigDecimal("3.000"), 5L));
        // 持有额度已为 2：再扣 2.001 条件更新 0 行，不允许为负
        assertFalse(repo1.deductHeldAmountIfSufficient(allocation.id(), new BigDecimal("2.001"), 6L));
        assertEquals(new BigDecimal("2.000"), repo1.findAllocationByKey("ak-wo").heldAmount());

        // 新连接重读：核销单、明细快照、守恒 CHECK 全部可读
        WaterRepository repo2 = new WaterRepository(new JdbcTemplate(
                new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
        WriteoffRow writeoff = repo2.findWriteoffByKey("wo-1");
        assertNotNull(writeoff);
        assertEquals("SETTLED", writeoff.status());
        assertEquals(new BigDecimal("5.000"), writeoff.totalAmount());
        assertEquals(new BigDecimal("2.000"), writeoff.totalCredit());
        assertEquals(new BigDecimal("3.000"), writeoff.totalQuota());
        List<WriteoffItemRow> items = repo2.listWriteoffItems(writeoffId);
        assertEquals(1, items.size());
        WriteoffItemRow item = items.get(0);
        assertEquals(0, item.seqNo());
        assertEquals("ak-wo", item.allocationKey());
        assertEquals(new BigDecimal("0.200000"), item.coefficient());
        assertTrue(item.appliedRainKeys().contains("rk-wo"));
        // 明细守恒：credit + quota = amount
        assertEquals(0, item.credit().add(item.quotaDeduction()).compareTo(item.amount()));

        // 破坏守恒的明细被 CHECK 约束拒绝
        assertThrows(Exception.class, () -> repo2.insertWriteoffItem(writeoffId, 1, "ak-wo",
                new BigDecimal("5.000"), new BigDecimal("1.000"), new BigDecimal("1.000"),
                null, "[]"));
    }
}
