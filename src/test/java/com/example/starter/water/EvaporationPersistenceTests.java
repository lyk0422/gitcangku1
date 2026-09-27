package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CorrectionRow;
import com.example.starter.water.WaterRepository.EvaporationRateRow;
import com.example.starter.water.WaterRepository.SettlementRow;
import com.example.starter.water.WaterRepository.WindowRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 蒸发结算持久化与约束验证：命名 H2 内存库（MODE=MySQL）上验证率版本唯一约束、核销守恒
 * CHECK、持有额度非负 CHECK、凭证/批次/更正的联表读取，以及新连接下证据不被改写。
 */
class EvaporationPersistenceTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:evap-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private WaterRepository newRepo(String url) {
        return new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
    }

    @Test
    void rateSettlementAndCorrectionArePersistedWithConservationConstraints() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        SimpleDriverDataSource first = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = first.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo = newRepo(url);
        LocalDate day = LocalDate.of(2026, 11, 10);
        long windowId = repo.insertWindow("wk-ev", "ch-ev", 1_000L, 2_000L, new BigDecimal("10.000"), 1L);
        repo.insertAllocation("ak-ev", windowId, "user-1", new BigDecimal("6.000"), "alice", 2L);
        AllocationRow allocation = repo.findAllocationByKey("ak-ev");
        repo.updateAllocationStatus(allocation.id(), "APPROVED", 3L);
        // 初始累计列存在且为 0
        assertEquals(0, allocation.settledDelivered().compareTo(BigDecimal.ZERO));
        assertEquals(0, allocation.settledLoss().compareTo(BigDecimal.ZERO));

        // 率版本 v1 -> v2
        long rate1 = repo.insertRateVersion("ch-ev", day, new BigDecimal("0.1000"), 1, 4L);
        long rate2 = repo.insertRateVersion("ch-ev", day, new BigDecimal("0.0500"), 2, 5L);
        EvaporationRateRow current = repo.findCurrentRate("ch-ev", day);
        assertEquals(rate2, current.id());
        assertEquals(2, current.version());
        assertEquals(new BigDecimal("0.0500"), current.rate());
        assertEquals(2, repo.listRates("ch-ev").size());
        // 同 (渠道,日期,版本) 唯一约束
        assertThrows(DataIntegrityViolationException.class,
                () -> repo.insertRateVersion("ch-ev", day, new BigDecimal("0.01"), 2, 6L));

        // 核销：送达 1.000 × v1 率 0.1000 -> 损耗 0.10，扣减 1.10（固化 v1，即使当前已 v2）
        long batchId = repo.insertBatch("bk-ev-1", windowId, 1, 7L);
        long settlementId = repo.insertSettlement("sk-ev-1", batchId, windowId, "ch-ev", allocation.id(),
                "ak-ev", rate1, 1, day, new BigDecimal("0.1000"), new BigDecimal("1.000"),
                new BigDecimal("0.10"), new BigDecimal("1.100"), 7L);
        repo.applySettlementToAllocation(allocation.id(), new BigDecimal("1.000"),
                new BigDecimal("0.10"), 7L);

        // 守恒 CHECK：上游扣减 != 送达 + 损耗 必须被数据库拒绝
        assertThrows(DataIntegrityViolationException.class, () -> repo.insertSettlement(
                "sk-bad", batchId, windowId, "ch-ev", allocation.id(), "ak-ev", rate1, 1, day,
                new BigDecimal("0.1000"), new BigDecimal("1.000"), new BigDecimal("0.10"),
                new BigDecimal("1.110"), 8L));

        // 持有额度非负 CHECK：再扣 6 会把 held(4.9) 扣成负
        assertThrows(DataIntegrityViolationException.class, () -> repo.applySettlementToAllocation(
                allocation.id(), new BigDecimal("6.000"), new BigDecimal("0"), 9L));

        // 反向更正：冲回 + 凭证置 REVERSED + 写更正凭证
        repo.applyCorrectionToAllocation(allocation.id(), new BigDecimal("1.000"),
                new BigDecimal("0.10"), 10L);
        repo.markSettlementReversed(settlementId);
        repo.insertCorrection("xk-ev-1", settlementId, "sk-ev-1", windowId, new BigDecimal("1.000"),
                new BigDecimal("0.10"), new BigDecimal("1.100"), "计量错误", 10L);

        // 全新连接读取：证据完整、当前余额恢复
        WaterRepository repo2 = newRepo(url);
        AllocationRow reread = repo2.findAllocationByKey("ak-ev");
        assertEquals("APPROVED", reread.status());
        assertEquals(new BigDecimal("6.000"), reread.heldAmount());
        assertEquals(0, reread.settledDelivered().compareTo(BigDecimal.ZERO));
        assertEquals(0, reread.settledLoss().compareTo(BigDecimal.ZERO));

        SettlementRow voucher = repo2.findSettlementByKey("sk-ev-1");
        assertNotNull(voucher);
        assertEquals("REVERSED", voucher.status());
        assertEquals("bk-ev-1", voucher.batchKey());
        assertEquals(rate1, voucher.rateId());
        assertEquals(1, voucher.rateVersion());
        assertEquals(new BigDecimal("0.1000"), voucher.rateValue());
        assertEquals(new BigDecimal("1.000"), voucher.rawDelivered());
        assertEquals(new BigDecimal("0.10"), voucher.loss());
        assertEquals(new BigDecimal("1.100"), voucher.upstreamDeduction());
        // 守恒：上游扣减 = 送达 + 损耗（存储口径）
        assertEquals(0, voucher.upstreamDeduction().compareTo(
                voucher.rawDelivered().add(voucher.loss())));

        CorrectionRow correction = repo2.findCorrectionByKey("xk-ev-1");
        assertNotNull(correction);
        assertEquals("计量错误", correction.reason());
        assertEquals(settlementId, correction.settlementId());
        assertEquals(1, repo2.listCorrections(windowId).size());
        assertEquals(1, repo2.listSettlements(windowId).size());
        assertTrue(repo2.existsSettlementOnDate("ch-ev", day));
    }

    @Test
    void settlementOnDateFreezesRateAndBatchKeyLookupWorks() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo = newRepo(url);
        LocalDate day = LocalDate.of(2026, 11, 11);
        long windowId = repo.insertWindow("wk-ev2", "ch-ev2", 1L, 2L, new BigDecimal("5"), 1L);
        repo.insertAllocation("ak-ev2", windowId, "u", new BigDecimal("5"), "alice", 2L);
        AllocationRow a = repo.findAllocationByKey("ak-ev2");
        repo.updateAllocationStatus(a.id(), "APPROVED", 3L);
        long rateId = repo.insertRateVersion("ch-ev2", day, new BigDecimal("0.2000"), 1, 4L);
        long batchId = repo.insertBatch("bk-ev2", windowId, 1, 5L);
        repo.insertSettlement("sk-ev2", batchId, windowId, "ch-ev2", a.id(), "ak-ev2", rateId, 1, day,
                new BigDecimal("0.2000"), new BigDecimal("2.000"), new BigDecimal("0.40"),
                new BigDecimal("2.400"), 5L);

        assertNotNull(repo.findBatchByKey("bk-ev2"));
        assertTrue(repo.existsSettlementOnDate("ch-ev2", day));
        // 其他日期不受冻结影响
        assertEquals(false, repo.existsSettlementOnDate("ch-ev2", day.plusDays(1)));
        WindowRow window = repo.findWindowById(windowId);
        assertEquals("ch-ev2", window.channelId());
    }
}
