package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.RainfallConfirmationRow;
import com.example.starter.water.WaterRepository.RainfallRow;
import com.example.starter.water.WaterRepository.WriteOffRainRow;
import com.example.starter.water.WaterRepository.WriteOffRow;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 降雨记录、双角色确认与核销固化的真实 H2 持久化验证：
 * 使用独立命名内存库（MODE=MySQL）与新连接复读，验证唯一约束、守恒 CHECK 约束、
 * 固化快照在撤销后不变。本题不要求跨进程重启恢复。
 */
class RainPersistenceTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:rain-persist-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private WaterRepository newRepository(String url) {
        return new WaterRepository(
                new JdbcTemplate(new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
    }

    @Test
    void rainfallConfirmationsAndWriteOffSnapshotsAreReadableFromNewConnection() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        SimpleDriverDataSource first = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = first.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo1 = new WaterRepository(new JdbcTemplate(first));
        long windowId = repo1.insertWindow("wk-rain", "ch-rain", 1L, 2L, new BigDecimal("100"), 1L);
        repo1.insertAllocation("ak-rain", windowId, "user-1", new BigDecimal("10.000"), "alice", 2L);
        AllocationRow allocation = repo1.findAllocationByKey("ak-rain");
        repo1.updateAllocationStatus(allocation.id(), "APPROVED", 3L);
        repo1.updateCreditCoefficient(allocation.id(), new BigDecimal("0.200000"), 4L);

        // 登记降雨 + 两名不同角色确认生效
        long rainId = repo1.insertRainfall("rk-rain", windowId, new BigDecimal("10.000"), "registrar", 5L);
        repo1.insertConfirmation(rainId, "role-a", "officer-a", 6L);
        repo1.insertConfirmation(rainId, "role-b", "officer-b", 7L);
        List<RainfallConfirmationRow> confirmations = repo1.listConfirmations(rainId);
        assertEquals(2, confirmations.size());
        repo1.markRainfallEffective(rainId, 2, 7L);

        // 核销：10mm × 0.2 = 2 抵扣，5 请求量 -> 配额扣 3；持有 10 -> 7
        long writeOffId = repo1.insertWriteOff("wok-rain", null, windowId, "ak-rain",
                new BigDecimal("5.000"), new BigDecimal("10.000"), new BigDecimal("0.200000"),
                new BigDecimal("2.000"), new BigDecimal("3.000"),
                new BigDecimal("10.000"), new BigDecimal("7.000"), "alice", 8L);
        repo1.insertWriteOffRain(writeOffId, "rk-rain", new BigDecimal("10.000"), 8L);
        repo1.decrementHeldAmount(allocation.id(), new BigDecimal("3.000"), 8L);

        // 撤销降雨（核销之后）
        repo1.revokeRainfall(rainId, "registrar", 9L);

        // 新连接复读：申请系数、持有额度、降雨状态、核销固化快照均一致
        WaterRepository repo2 = newRepository(url);
        AllocationRow allocation2 = repo2.findAllocationByKey("ak-rain");
        assertEquals(new BigDecimal("0.200000"), allocation2.creditCoefficient());
        assertEquals(new BigDecimal("7.000"), allocation2.heldAmount());

        RainfallRow rain2 = repo2.findRainfallByKey("rk-rain");
        assertEquals("REVOKED", rain2.status());
        assertEquals(2, rain2.confirmCount());
        assertEquals(9L, rain2.revokedNanos());
        assertEquals("registrar", rain2.revokedBy());

        WriteOffRow writeOff = repo2.findWriteOffByKey("wok-rain");
        assertNotNull(writeOff);
        // 撤销不改写已固化的降雨量、系数、抵扣与扣减
        assertEquals(new BigDecimal("10.000"), writeOff.rainfallMm());
        assertEquals(new BigDecimal("0.200000"), writeOff.coefficient());
        assertEquals(new BigDecimal("2.000"), writeOff.creditAmount());
        assertEquals(new BigDecimal("3.000"), writeOff.quotaDeduction());
        assertEquals(new BigDecimal("7.000"), writeOff.heldAfter());
        // 守恒
        assertEquals(writeOff.requestedAmount(),
                writeOff.creditAmount().add(writeOff.quotaDeduction()));

        List<WriteOffRainRow> snapshots = repo2.listWriteOffRains(writeOff.id());
        assertEquals(1, snapshots.size());
        assertEquals("rk-rain", snapshots.get(0).rainKey());
        assertEquals(new BigDecimal("10.000"), snapshots.get(0).rainfallMm());

        // 汇总
        assertEquals(new BigDecimal("2.000"), repo2.sumCreditByAllocation("ak-rain"));
        assertEquals(new BigDecimal("3.000"), repo2.sumQuotaDeductionByAllocation("ak-rain"));
        assertEquals(1, repo2.countWriteOffsByAllocation("ak-rain"));
        assertEquals(new BigDecimal("5.000"), repo2.sumWriteOffColumn(windowId, "requested_amount"));
    }

    @Test
    void duplicateRoleConfirmationAndDuplicateKeysAreRejectedByH2Constraints() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo = new WaterRepository(new JdbcTemplate(dataSource));
        long windowId = repo.insertWindow("wk-dup", "ch-dup", 1L, 2L, new BigDecimal("10"), 1L);
        long rainId = repo.insertRainfall("rk-dup", windowId, new BigDecimal("5"), "registrar", 2L);
        repo.insertConfirmation(rainId, "role-a", "officer-a", 3L);
        // 同降雨记录同角色重复确认 -> 唯一约束
        assertThrows(DuplicateKeyException.class,
                () -> repo.insertConfirmation(rainId, "role-a", "officer-a2", 4L));
        // 降雨业务键重复 -> 唯一约束
        assertThrows(DuplicateKeyException.class,
                () -> repo.insertRainfall("rk-dup", windowId, new BigDecimal("5"), "registrar", 5L));
    }

    @Test
    void conservationCheckConstraintRejectsCreditExceedingRequested() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo = new WaterRepository(new JdbcTemplate(dataSource));
        long windowId = repo.insertWindow("wk-chk", "ch-chk", 1L, 2L, new BigDecimal("10"), 1L);
        repo.insertAllocation("ak-chk", windowId, "u", new BigDecimal("10"), "alice", 2L);
        // 非法守恒：抵扣 4 + 配额扣减 3 != 请求量 5，且抵扣超过请求量 -> CHECK 拒绝
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class,
                () -> repo.insertWriteOff("wok-bad", null, windowId, "ak-chk",
                        new BigDecimal("5.000"), new BigDecimal("0"), new BigDecimal("0"),
                        new BigDecimal("4.000"), new BigDecimal("3.000"),
                        new BigDecimal("10.000"), new BigDecimal("7.000"), "alice", 3L));
        assertTrue(ex.getMessage() == null || ex.getMessage().contains("chk_write_off_conservation")
                || ex.getMostSpecificCause().getMessage().contains("chk_write_off_conservation"));
    }
}
