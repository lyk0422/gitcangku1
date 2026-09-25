package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.CancelRequest;
import com.example.starter.incident.dto.Requests.CleanupRequest;
import com.example.starter.incident.dto.Requests.DependencyRequest;
import com.example.starter.incident.dto.Requests.EscalateRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.CleanupView;
import com.example.starter.incident.dto.Responses.DependencyView;
import com.example.starter.incident.dto.Responses.DrillBatchView;
import com.example.starter.incident.dto.Responses.IncidentView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 演练沙盘隔离测试（真实 H2，MODE=MySQL）：
 * 覆盖沙盘域标记、incidentKey/commandKey 两域独立、查询默认域与显式 includeDrill、
 * 跨域依赖拦截 422、演练升级零真实副作用、取消终态、批次清理原子性与墓碑、
 * 失败不占键、批次清单与清理历史。
 */
@SpringBootTest
class DrillSandboxTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_notifications");
        jdbc.update("DELETE FROM incident_dependencies");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incidents");
        jdbc.update("DELETE FROM drill_batches");
    }

    private static String key() {
        return "KEY-" + UUID.randomUUID();
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private static void assertApi(ThrowingCallable call, HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    private IncidentView reportReal(String incidentKey) {
        return service.report(new ReportRequest(incidentKey, "S3", "真实事件", "reporter-real"));
    }

    private IncidentView reportDrill(String batchKey, String incidentKey) {
        return service.report(new ReportRequest(incidentKey, "S3", "演练事件", "reporter-drill",
                "drill-secret", batchKey));
    }

    private IncidentView commanding(String domain, String key, String commander) {
        service.takeover(Domain.valueOf(domain), key, commander, new TakeoverRequest(key()));
        return service.get(Domain.valueOf(domain), key);
    }

    private void toTerminal(String domain, String key, String commander) {
        Domain d = Domain.valueOf(domain);
        service.changeStatus(d, key, commander, new StatusRequest(key(), "CONTAINED"));
        service.changeStatus(d, key, commander, new StatusRequest(key(), "RESOLVED"));
    }

    // ---------- 沙盘隔离标记与查询默认域 ----------

    @Test
    void drillMarker_routesToDrillDomain_andSameIncidentKeyReusable() {
        reportReal("DUP-1");
        IncidentView drill = reportDrill("BATCH-A", "DUP-1");

        assertThat(drill.domain()).isEqualTo("DRILL");
        assertThat(drill.drillBatchKey()).isEqualTo("BATCH-A");
        IncidentView real = service.get(Domain.REAL, "DUP-1");
        assertThat(real.domain()).isEqualTo("REAL");
        assertThat(real.drillBatchKey()).isNull();

        // 默认查询只返回真实域：演练键在真实域 404，清单不含演练
        assertApiStatus(() -> service.get(Domain.REAL, "DUP-1-NOT-REAL"), HttpStatus.NOT_FOUND);
        assertThat(service.list(false)).extracting(IncidentView::incidentKey).doesNotContain("DUP-1-DRILL");
        reportDrill("BATCH-A", "DUP-1-DRILL");
        assertThat(service.list(false)).allSatisfy(v -> assertThat(v.domain()).isEqualTo("REAL"));
        // 显式 includeDrill 才能看到演练域，且结果必须标注域
        List<IncidentView> all = service.list(true);
        assertThat(all).filteredOn(v -> v.domain().equals("DRILL"))
                .extracting(IncidentView::incidentKey).containsExactlyInAnyOrder("DUP-1", "DUP-1-DRILL");
        assertThat(service.get(Domain.DRILL, "DUP-1").domain()).isEqualTo("DRILL");
    }

    @Test
    void drillReport_requiresBatchKey() {
        assertApiStatus(() -> service.report(new ReportRequest("D-NOBATCH", "S1", "x", "r",
                "drillKey", null)), HttpStatus.BAD_REQUEST);
    }

    // ---------- 两域统计、查询与幂等键空间独立 ----------

    @Test
    void statsAndIdempotency_areIndependentPerDomain() {
        reportReal("STAT-R");
        reportDrill("BATCH-STAT", "STAT-D");

        var realOnly = service.stats(false);
        assertThat(realOnly).containsOnlyKeys("REAL");
        @SuppressWarnings("unchecked")
        var realStats = (java.util.Map<String, Object>) realOnly.get("REAL");
        assertThat(realStats.get("total")).isEqualTo(1L);

        var both = service.stats(true);
        assertThat(both).containsOnlyKeys("REAL", "DRILL");
        @SuppressWarnings("unchecked")
        var drillStats = (java.util.Map<String, Object>) both.get("DRILL");
        assertThat(drillStats.get("domain")).isEqualTo("DRILL");
        assertThat(drillStats.get("total")).isEqualTo(1L);

        // 同 commandKey 在两域各自生效：REAL 先接管成功
        String sharedCommandKey = key();
        reportReal("IDEM-R");
        IncidentView realTakeover = service.takeover(Domain.REAL, "IDEM-R", "alice",
                new TakeoverRequest(sharedCommandKey));
        assertThat(realTakeover.commander()).isEqualTo("alice");
        // DRILL 用同一键不跨域重放，而是独立执行一次演练接管
        reportDrill("BATCH-IDEM", "IDEM-D");
        IncidentView drillTakeover = service.takeover(Domain.DRILL, "IDEM-D", "alice",
                new TakeoverRequest(sharedCommandKey));
        assertThat(drillTakeover.commander()).isEqualTo("alice");
        // 真实域事件与演练域事件互不影响
        assertThat(service.get(Domain.REAL, "IDEM-R").status()).isEqualTo("COMMANDING");
        assertThat(service.get(Domain.DRILL, "IDEM-D").status()).isEqualTo("COMMANDING");
    }

    @Test
    void failedWrite_doesNotOccupyCommandKey() {
        reportDrill("BATCH-F", "FAIL-1");
        service.takeover(Domain.DRILL, "FAIL-1", "alice", new TakeoverRequest(key()));
        String commandKey = key();
        // 非指挥人写入失败（409），占位幂等键随事务回滚
        assertApiStatus(() -> service.addAction(Domain.DRILL, "FAIL-1", "bob",
                new ActionRequest(commandKey, "A1", "NOTE", "n", Instant.parse("2026-09-21T08:00:00Z"))),
                HttpStatus.CONFLICT);
        // 同一 commandKey 可被随后的合法请求成功使用（未被失败占用）
        IncidentView ok = service.changeStatus(Domain.DRILL, "FAIL-1", "alice",
                new StatusRequest(commandKey, "CONTAINED"));
        assertThat(ok.status()).isEqualTo("CONTAINED");
    }

    // ---------- 跨域拦截 ----------

    @Test
    void dependency_crossDomainReference_returns422() {
        reportReal("CROSS-R");
        commanding("REAL", "CROSS-R", "alice");
        reportDrill("BATCH-CROSS", "CROSS-D");
        commanding("DRILL", "CROSS-D", "alice");

        // 真实事件不得依赖演练事件
        assertApi(() -> service.addDependency(Domain.REAL, "CROSS-R", "alice",
                new DependencyRequest(key(), "CROSS-D")), HttpStatus.UNPROCESSABLE_ENTITY,
                "CROSS_DOMAIN_REFERENCE");
        // 演练任务的阻塞事件必须也在演练域
        assertApi(() -> service.addDependency(Domain.DRILL, "CROSS-D", "alice",
                new DependencyRequest(key(), "CROSS-R")), HttpStatus.UNPROCESSABLE_ENTITY,
                "CROSS_DOMAIN_REFERENCE");
        // 两域都不存在的阻塞事件 → 404
        assertApiStatus(() -> service.addDependency(Domain.REAL, "CROSS-R", "alice",
                new DependencyRequest(key(), "GHOST")), HttpStatus.NOT_FOUND);
        // 同域依赖成功
        reportReal("CROSS-R2");
        commanding("REAL", "CROSS-R2", "bob");
        DependencyView rr = service.addDependency(Domain.REAL, "CROSS-R2", "bob",
                new DependencyRequest(key(), "CROSS-R"));
        assertThat(rr.blockedByIncidentKey()).isEqualTo("CROSS-R");
        reportDrill("BATCH-CROSS", "CROSS-D2");
        DependencyView ddOk = service.addDependency(Domain.DRILL, "CROSS-D", "alice",
                new DependencyRequest(key(), "CROSS-D2"));
        assertThat(ddOk.blockedByIncidentKey()).isEqualTo("CROSS-D2");
        // 跨域失败不产生依赖边：CROSS-R 无出边
        assertThat(service.history(Domain.REAL, "CROSS-R").dependencies()).isEmpty();
        // CROSS-R2 的出边只指向同域 CROSS-R
        assertThat(service.history(Domain.REAL, "CROSS-R2").dependencies())
                .extracting(DependencyView::blockedByIncidentKey).containsExactly("CROSS-R");
        // 演练出边只指向同域 CROSS-D2
        assertThat(service.history(Domain.DRILL, "CROSS-D").dependencies())
                .extracting(DependencyView::blockedByIncidentKey).containsExactly("CROSS-D2");
    }

    // ---------- 演练升级不触发真实副作用 ----------

    @Test
    void drillEscalation_recordsButNoRealSideEffect() {
        reportDrill("BATCH-ESC", "ESC-D");
        service.takeover(Domain.DRILL, "ESC-D", "alice", new TakeoverRequest(key()));
        IncidentView escalated = service.escalate(Domain.DRILL, "ESC-D", "alice",
                new EscalateRequest(key(), "S1", "演练升级，不应外发"));
        assertThat(escalated.severity()).isEqualTo("S1");
        long drillId = incidentRepository.findByKey(Domain.DRILL, "ESC-D").orElseThrow().id();
        // 演练升级只落升级记录，不产生任何真实通知
        assertThat(incidentRepository.listEscalations(drillId)).hasSize(1);
        assertThat(incidentRepository.countNotifications(drillId)).isZero();

        // 真实域升级才触发真实通知副作用
        reportReal("ESC-R");
        service.takeover(Domain.REAL, "ESC-R", "alice", new TakeoverRequest(key()));
        service.escalate(Domain.REAL, "ESC-R", "alice",
                new EscalateRequest(key(), "S1", "真实升级"));
        long realId = incidentRepository.findByKey(Domain.REAL, "ESC-R").orElseThrow().id();
        assertThat(incidentRepository.countNotifications(realId)).isEqualTo(1);

        // 升级只能升到更高等级
        assertApiStatus(() -> service.escalate(Domain.DRILL, "ESC-D", "alice",
                new EscalateRequest(key(), "S2", "回退等级")), HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ---------- 演练取消终态 ----------

    @Test
    void drillCancel_onlyReportedByReporter_becomesTerminal() {
        reportDrill("BATCH-CAN", "CAN-1");
        // 非上报人不能取消
        assertApiStatus(() -> service.cancelDrill("CAN-1", "someone-else", new CancelRequest(key())),
                HttpStatus.CONFLICT);
        IncidentView cancelled = service.cancelDrill("CAN-1", "reporter-drill", new CancelRequest(key()));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        // 终态后不能接管，也不能再追加处置或推进状态
        assertApiStatus(() -> service.takeover(Domain.DRILL, "CAN-1", "alice", new TakeoverRequest(key())),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> service.addAction(Domain.DRILL, "CAN-1", "alice", new ActionRequest(key(),
                "A1", "NOTE", "n", Instant.parse("2026-09-21T08:00:00Z"))),
                HttpStatus.CONFLICT);
        // 真实域事件不能走演练取消
        reportReal("CAN-R");
        assertApiStatus(() -> service.cancelDrill("CAN-R", "reporter-real", new CancelRequest(key())),
                HttpStatus.NOT_FOUND);
    }

    // ---------- 批次清理原子性 ----------

    @Test
    void cleanup_atomicDelete_isolatesOtherBatchesAndReal() {
        // 目标批次：一个 RESOLVED、一个 CANCELLED，含处置/交接/升级/依赖边
        reportDrill("BATCH-DEL", "DEL-1");
        service.takeover(Domain.DRILL, "DEL-1", "alice", new TakeoverRequest(key()));
        service.addAction(Domain.DRILL, "DEL-1", "alice", new ActionRequest(key(), "A1",
                "NOTE", "处置", Instant.parse("2026-09-21T08:00:00Z")));
        service.escalate(Domain.DRILL, "DEL-1", "alice",
                new EscalateRequest(key(), "S1", "演练升级"));
        toTerminal("DRILL", "DEL-1", "alice");
        reportDrill("BATCH-DEL", "DEL-2");
        service.cancelDrill("DEL-2", "reporter-drill", new CancelRequest(key()));
        // DEL-1 依赖 DEL-2（同域）
        service.addDependency(Domain.DRILL, "DEL-1", "alice",
                new DependencyRequest(key(), "DEL-2"));

        // 其他演练批次与真实事件不受影响
        reportDrill("BATCH-KEEP", "KEEP-D");
        reportReal("KEEP-R");

        String cleanupKey = key();
        CleanupView result = service.cleanupDrillBatch(new CleanupRequest(cleanupKey, "BATCH-DEL"));
        assertThat(result.deletedIncidentCount()).isEqualTo(2);

        // 事件及其任务/依赖边/交接/升级全部删除
        assertApiStatus(() -> service.get(Domain.DRILL, "DEL-1"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.get(Domain.DRILL, "DEL-2"), HttpStatus.NOT_FOUND);
        assertThat(service.list(true)).extracting(IncidentView::incidentKey)
                .contains("KEEP-D", "KEEP-R").doesNotContain("DEL-1", "DEL-2");
        Integer depCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_dependencies", Integer.class);
        assertThat(depCount).isZero();
        Integer escCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_escalations", Integer.class);
        assertThat(escCount).isZero();

        // 清理后对已删事件的后续演练写入 → 404
        assertApiStatus(() -> service.changeStatus(Domain.DRILL, "DEL-1", "alice",
                new StatusRequest(key(), "CLOSED")), HttpStatus.NOT_FOUND);

        // 同批次标识不可复用于新演练
        assertApi(() -> reportDrill("BATCH-DEL", "NEW-DEL"), HttpStatus.UNPROCESSABLE_ENTITY,
                "BATCH_ALREADY_CLEANED");

        // cleanupKey 同键同参重放首次结果
        CleanupView replay = service.cleanupDrillBatch(new CleanupRequest(cleanupKey, "BATCH-DEL"));
        assertThat(replay).isEqualTo(result);
        // 同键异参 → 409
        assertApiStatus(() -> service.cleanupDrillBatch(new CleanupRequest(cleanupKey, "BATCH-KEEP")),
                HttpStatus.CONFLICT);

        // 清理历史可查
        DrillBatchView batch = service.getDrillBatch("BATCH-DEL");
        assertThat(batch.cleaned()).isTrue();
        assertThat(batch.deletedIncidentCount()).isEqualTo(2);
        assertThat(batch.incidents()).isEmpty();
        assertThat(service.listDrillBatches()).extracting(DrillBatchView::batchKey)
                .contains("BATCH-DEL", "BATCH-KEEP");
    }

    @Test
    void cleanup_withUnfinishedIncident_422ListsAndDeletesNothing_keyReusable() {
        reportDrill("BATCH-U", "U-DONE");
        service.takeover(Domain.DRILL, "U-DONE", "alice", new TakeoverRequest(key()));
        toTerminal("DRILL", "U-DONE", "alice");
        reportDrill("BATCH-U", "U-OPEN");
        service.takeover(Domain.DRILL, "U-OPEN", "alice", new TakeoverRequest(key()));

        String cleanupKey = key();
        assertThatThrownBy(() -> service.cleanupDrillBatch(new CleanupRequest(cleanupKey, "BATCH-U")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("BATCH_NOT_TERMINAL");
                    assertThat(e.getMessage()).contains("U-OPEN");
                });
        // 整批未删除任何数据
        assertThat(service.get(Domain.DRILL, "U-DONE").status()).isEqualTo("RESOLVED");
        assertThat(service.get(Domain.DRILL, "U-OPEN").status()).isEqualTo("COMMANDING");
        DrillBatchView batch = service.getDrillBatch("BATCH-U");
        assertThat(batch.cleaned()).isFalse();

        // 失败不占 cleanupKey：终结未竟事件后同键重试成功
        service.changeStatus(Domain.DRILL, "U-OPEN", "alice", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus(Domain.DRILL, "U-OPEN", "alice", new StatusRequest(key(), "RESOLVED"));
        CleanupView retry = service.cleanupDrillBatch(new CleanupRequest(cleanupKey, "BATCH-U"));
        assertThat(retry.deletedIncidentCount()).isEqualTo(2);
    }

    @Test
    void cleanup_unknownBatch_404() {
        assertApiStatus(() -> service.cleanupDrillBatch(new CleanupRequest(key(), "GHOST-BATCH")),
                HttpStatus.NOT_FOUND);
    }
}
