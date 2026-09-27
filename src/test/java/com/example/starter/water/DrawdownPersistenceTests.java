package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CurveRow;
import com.example.starter.water.WaterRepository.HourTotalRow;
import com.example.starter.water.WaterRepository.PlanItemRow;
import com.example.starter.water.WaterRepository.ReservationRow;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下泄曲线、逐小时计划/预留/释放/核销的持久化验证：同一 JVM 内使用命名 H2 内存库（MODE=MySQL），
 * 建表写入后以全新连接与仓储实例重新读取，验证版本快照、小时聚合 SQL（含释放与取消后核销口径）。
 * 本题不要求跨进程重启恢复。
 */
class DrawdownPersistenceTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:drawdown-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    private static final long H0 = 0L;
    private static final long H1 = 3_600_000_000_000L;

    private WaterRepository newRepo(String url) {
        return new WaterRepository(new JdbcTemplate(
                new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "")));
    }

    @Test
    void curveReservationsReleasesAndWriteoffsAreReadableFromNewConnection() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo1 = newRepo(url);
        long windowId = repo1.insertWindow("wk-dd", "ch-dd", H0, 2 * H1, new BigDecimal("20.000"), 1L);
        long curveV1 = repo1.insertCurve(windowId, 1, 2L);
        repo1.insertCurvePoint(curveV1, windowId, H0, new BigDecimal("10.00"));
        repo1.insertCurvePoint(curveV1, windowId, H1, new BigDecimal("10.00"));

        // 申请：10 个单位按 6/4 分布在两个小时
        long allocationId = repo1.insertAllocation("ak-dd", windowId, "user-1",
                new BigDecimal("10.000"), "alice", 3L);
        repo1.insertPlanItem(allocationId, windowId, H0, new BigDecimal("6.000"));
        repo1.insertPlanItem(allocationId, windowId, H1, new BigDecimal("4.000"));
        List<PlanItemRow> plan = repo1.listPlanItems(allocationId);
        assertEquals(2, plan.size());
        assertEquals(new BigDecimal("6.000"), plan.get(0).volume());

        // 批准：固化 v1 与逐小时预留
        repo1.approveAllocation(allocationId, curveV1, 4L);
        for (PlanItemRow item : plan) {
            repo1.insertReservation(allocationId, windowId, item.hourStartNanos(), item.volume(),
                    curveV1, 4L);
        }

        // h0 已核销 4
        repo1.insertWriteoff("wo-dd", allocationId, windowId, H0, new BigDecimal("4.000"), "alice", 5L);

        // 第二连接：曲线版本、申请快照、核销聚合
        WaterRepository repo2 = newRepo(url);
        CurveRow curve = repo2.findCurveById(curveV1);
        assertNotNull(curve);
        assertEquals(1, curve.version());
        assertEquals("ACTIVE", curve.status());
        assertEquals(2, repo2.listCurvePoints(curveV1).size());

        AllocationRow approved = repo2.findAllocationByKey("ak-dd");
        assertEquals(curveV1, approved.approvedCurveId());
        List<ReservationRow> reservations = repo2.listReservations(allocationId);
        assertEquals(2, reservations.size());
        assertEquals(curveV1, reservations.get(0).curveId());

        Map<Long, HourTotalRow> totals = repo2.listHourTotals(windowId).stream()
                .collect(Collectors.toMap(HourTotalRow::hourStartNanos, Function.identity()));
        // h0：预留 6（含已核销 4），核销 4，未核销 2
        assertEquals(0, totals.get(H0).reservedTotal().compareTo(new BigDecimal("6.000")));
        assertEquals(0, totals.get(H0).writtenOffTotal().compareTo(new BigDecimal("4.000")));
        // h1：预留 4，核销 0
        assertEquals(0, totals.get(H1).reservedTotal().compareTo(new BigDecimal("4.000")));
        assertEquals(0, repo2.outstandingReservation(allocationId, H0).compareTo(new BigDecimal("2.000")));
        assertEquals(0, repo2.outstandingReservation(allocationId, H1).compareTo(new BigDecimal("4.000")));

        // 曲线升级到 v2：历史预留仍引用 v1，新 ACTIVE 为 v2
        repo2.supersedeActiveCurve(windowId);
        long curveV2 = repo2.insertCurve(windowId, 2, 6L);
        repo2.insertCurvePoint(curveV2, windowId, H0, new BigDecimal("8.00"));
        repo2.insertCurvePoint(curveV2, windowId, H1, new BigDecimal("12.00"));
        assertEquals(curveV2, repo2.findActiveCurve(windowId).id());
        assertEquals(curveV1, repo2.listReservations(allocationId).get(0).curveId());
    }

    @Test
    void transferReleasesAreSubtractedAndCancelledKeepsWrittenOffPart() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterRepository repo = new WaterRepository(new JdbcTemplate(dataSource));
        long windowId = repo.insertWindow("wk-rel", "ch-rel", H0, H1, new BigDecimal("20.000"), 1L);
        long curveId = repo.insertCurve(windowId, 1, 2L);
        repo.insertCurvePoint(curveId, windowId, H0, new BigDecimal("10.00"));

        long sourceId = repo.insertAllocation("ak-src", windowId, "u-src",
                new BigDecimal("10.000"), "alice", 3L);
        long targetId = repo.insertAllocation("ak-tgt", windowId, "u-tgt",
                new BigDecimal("4.000"), "bob", 3L);
        repo.approveAllocation(sourceId, curveId, 4L);
        repo.insertReservation(sourceId, windowId, H0, new BigDecimal("10.000"), curveId, 4L);
        repo.approveAllocation(targetId, curveId, 5L);
        repo.insertReservation(targetId, windowId, H0, new BigDecimal("4.000"), curveId, 5L);

        // 源先核销 3，再通过转让释放 4：源当前占用 = 10 - 4(释放) = 6（含已核销 3）
        repo.insertWriteoff("wo-src", sourceId, windowId, H0, new BigDecimal("3.000"), "alice", 6L);
        repo.decrementHeldAmount(sourceId, new BigDecimal("4.000"), 7L);
        long transferId = repo.insertTransfer("tk-rel", windowId, "ak-src", "ak-tgt",
                new BigDecimal("4.000"), "alice", 7L);
        repo.insertReservationRelease(transferId, "tk-rel", sourceId, windowId, H0,
                new BigDecimal("4.000"), 7L);

        Map<Long, HourTotalRow> totals = repo.listHourTotals(windowId).stream()
                .collect(Collectors.toMap(HourTotalRow::hourStartNanos, Function.identity()));
        // 总占用 = 源 6（含核销 3）+ 目标 4 = 10
        assertEquals(0, totals.get(H0).reservedTotal().compareTo(new BigDecimal("10.000")));
        assertEquals(0, totals.get(H0).writtenOffTotal().compareTo(new BigDecimal("3.000")));
        // 源 h0 未核销余量 = 10 - 4(释放) - 3(核销) = 3
        assertEquals(0, repo.outstandingReservation(sourceId, H0).compareTo(new BigDecimal("3.000")));

        // 取消源：未核销部分全部释放，已核销 3 仍占小时
        repo.updateAllocationStatus(sourceId, "CANCELLED", 8L);
        totals = repo.listHourTotals(windowId).stream()
                .collect(Collectors.toMap(HourTotalRow::hourStartNanos, Function.identity()));
        // 占用 = 源已核销 3 + 目标 4 = 7
        assertEquals(0, totals.get(H0).reservedTotal().compareTo(new BigDecimal("7.000")));
        assertEquals(0, totals.get(H0).writtenOffTotal().compareTo(new BigDecimal("3.000")));
        assertEquals(0, repo.outstandingReservation(sourceId, H0).compareTo(BigDecimal.ZERO));

        // 快照行不删除
        assertEquals(1, repo.listReservations(sourceId).size());
        AllocationRow cancelled = repo.findAllocationByKey("ak-src");
        assertEquals("CANCELLED", cancelled.status());
        assertEquals(curveId, cancelled.approvedCurveId());
        assertNull(repo.findWriteoffByKey("missing"));
        assertTrue(repo.sumWriteoff(sourceId, H1).signum() == 0);
    }
}
