package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.EscalateRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskRequest;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 演练沙盘隔离测试：域标记、同键跨域复用、默认只返回真实域、跨域引用 422、
 * 演练升级零真实副作用、两域统计与幂等键空间独立。
 */
@SpringBootTest
class DrillIsolationTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM notification_outbox");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM drill_cleanups");
        jdbc.update("DELETE FROM drill_batches");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "K-" + UUID.randomUUID();
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private IncidentView reportReal(String incidentKey) {
        return service.report(new ReportRequest(incidentKey, "S1", "真实事件", "r"));
    }

    private IncidentView reportDrill(String incidentKey, String drillKey, String batch) {
        return service.report(new ReportRequest(incidentKey, "S1", "演练事件", "r", drillKey, batch));
    }

    @Test
    void sameIncidentKey_existsIndependentlyInBothDomains_andResultsLabeled() {
        IncidentView real = reportReal("INC-SAME");
        IncidentView drill = reportDrill("INC-SAME", "drill-A", "batch-A");
        assertThat(real.domain()).isEqualTo("REAL");
        assertThat(real.drillKey()).isNull();
        assertThat(drill.domain()).isEqualTo("DRILL");
        assertThat(drill.drillKey()).isEqualTo("drill-A");
        assertThat(drill.drillBatch()).isEqualTo("batch-A");

        // 默认查询只返回真实域
        assertThat(service.get(Domain.REAL, "INC-SAME").domain()).isEqualTo("REAL");
        assertThat(service.get(Domain.DRILL, "INC-SAME").domain()).isEqualTo("DRILL");
        assertApiStatus(() -> service.get(Domain.REAL, "INC-ONLY-DRILL"), HttpStatus.NOT_FOUND);

        // 列表默认仅真实域；显式 includeDrill 才见演练域
        reportDrill("INC-ONLY-DRILL", "drill-A", "batch-A");
        List<IncidentView> reals = service.list(Domain.REAL);
        List<IncidentView> drills = service.list(Domain.DRILL);
        assertThat(reals).extracting(IncidentView::incidentKey).contains("INC-SAME")
                .doesNotContain("INC-ONLY-DRILL");
        assertThat(reals).allSatisfy(v -> assertThat(v.domain()).isEqualTo("REAL"));
        assertThat(drills).extracting(IncidentView::incidentKey)
                .containsExactlyInAnyOrder("INC-SAME", "INC-ONLY-DRILL");
        assertThat(drills).allSatisfy(v -> assertThat(v.domain()).isEqualTo("DRILL"));
    }

    @Test
    void drillKey_defaultsBatchToDrillKey() {
        IncidentView drill = reportDrill("INC-D", "drill-X", null);
        assertThat(drill.drillBatch()).isEqualTo("drill-X");
    }

    @Test
    void writeToDrillIncidentViaRealDomain_isNotFound() {
        reportDrill("INC-ISO", "drill-A", "batch-A");
        assertApiStatus(() -> service.takeover(Domain.REAL, "INC-ISO", "alice",
                new TakeoverRequest(key())), HttpStatus.NOT_FOUND);
        // 真实域查询同样不可见
        assertApiStatus(() -> service.history(Domain.REAL, "INC-ISO"), HttpStatus.NOT_FOUND);
    }

    @Test
    void drillOperationsReuseBusinessRules() {
        reportDrill("INC-DRILL-RULES", "drill-A", "batch-A");
        IncidentView commanding = service.takeover(Domain.DRILL, "INC-DRILL-RULES", "alice",
                new TakeoverRequest(key()));
        assertThat(commanding.status()).isEqualTo("COMMANDING");
        assertThat(commanding.domain()).isEqualTo("DRILL");
        // 演练域同样遵守状态机：不能跳级
        assertApiStatus(() -> service.changeStatus(Domain.DRILL, "INC-DRILL-RULES", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "RESOLVED")),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 非指挥人同样 409
        assertApiStatus(() -> service.addAction(Domain.DRILL, "INC-DRILL-RULES", "bob",
                new ActionRequest(key(), "A1", "NOTE", "n", Instant.parse("2026-09-25T01:00:00Z"))),
                HttpStatus.CONFLICT);
    }

    @Test
    void crossDomainTaskBlocker_returns422() {
        // 前置事件分属不同域：真实任务只能依赖真实事件，演练任务只能依赖演练事件
        reportReal("INC-DEP-REAL");
        reportDrill("INC-DEP-DRILL", "drill-A", "batch-A");
        reportReal("INC-REAL-OWNER");
        reportDrill("INC-DRILL-OWNER", "drill-A", "batch-A");
        service.takeover(Domain.REAL, "INC-REAL-OWNER", "alice", new TakeoverRequest(key()));
        service.takeover(Domain.DRILL, "INC-DRILL-OWNER", "alice", new TakeoverRequest(key()));

        // 真实事件任务依赖仅存在于演练域的事件 -> 422 CROSS_DOMAIN_REFERENCE
        assertThatThrownBy(() -> service.createTask(Domain.REAL, "INC-REAL-OWNER", "alice",
                new TaskRequest(key(), "T1", "真实任务", List.of("INC-DEP-DRILL"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("CROSS_DOMAIN_REFERENCE");
                });
        // 演练事件任务依赖仅存在于真实域的事件 -> 422
        assertThatThrownBy(() -> service.createTask(Domain.DRILL, "INC-DRILL-OWNER", "alice",
                new TaskRequest(key(), "T1", "演练任务", List.of("INC-DEP-REAL"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("CROSS_DOMAIN_REFERENCE");
                });
        // 不存在的事件同样 422（不能借依赖探测另一域）
        assertApiStatus(() -> service.createTask(Domain.DRILL, "INC-DRILL-OWNER", "alice",
                new TaskRequest(key(), "T2", "引用不存在", List.of("INC-GHOST"))),
                HttpStatus.UNPROCESSABLE_ENTITY);

        // 同域依赖成功
        TaskView realTask = service.createTask(Domain.REAL, "INC-REAL-OWNER", "alice",
                new TaskRequest(key(), "T3", "真实同域依赖", List.of("INC-DEP-REAL")));
        assertThat(realTask.domain()).isEqualTo("REAL");
        assertThat(realTask.blockerIncidentKeys()).containsExactly("INC-DEP-REAL");
        TaskView drillTask = service.createTask(Domain.DRILL, "INC-DRILL-OWNER", "alice",
                new TaskRequest(key(), "T3", "演练同域依赖", List.of("INC-DEP-DRILL")));
        assertThat(drillTask.domain()).isEqualTo("DRILL");
        assertThat(drillTask.blockerIncidentKeys()).containsExactly("INC-DEP-DRILL");
    }

    @Test
    void drillEscalation_hasNoRealSideEffect() {
        reportDrill("INC-DRILL-ESC", "drill-A", "batch-A");
        service.takeover(Domain.DRILL, "INC-DRILL-ESC", "alice", new TakeoverRequest(key()));
        service.escalate(Domain.DRILL, "INC-DRILL-ESC", "alice",
                new EscalateRequest(key(), "sre-oncall", "演练升级"));
        // 演练升级只落升级历史，不产生任何真实通知
        assertThat(incidentNotifications()).isZero();
        assertThat(service.history(Domain.DRILL, "INC-DRILL-ESC").escalations()).hasSize(1);

        // 真实升级产生通知
        reportReal("INC-REAL-ESC");
        service.takeover(Domain.REAL, "INC-REAL-ESC", "alice", new TakeoverRequest(key()));
        service.escalate(Domain.REAL, "INC-REAL-ESC", "alice",
                new EscalateRequest(key(), "sre-oncall", "真实升级"));
        assertThat(incidentNotifications()).isEqualTo(1);
    }

    @Test
    void stats_areIndependentPerDomain() {
        reportReal("INC-R1");
        reportDrill("INC-D1", "drill-A", "batch-A");
        service.takeover(Domain.DRILL, "INC-D1", "alice", new TakeoverRequest(key()));
        assertThat(service.stats(Domain.REAL).get("REPORTED")).isEqualTo(1);
        assertThat(service.stats(Domain.REAL).get("COMMANDING")).isZero();
        assertThat(service.stats(Domain.DRILL).get("REPORTED")).isZero();
        assertThat(service.stats(Domain.DRILL).get("COMMANDING")).isEqualTo(1);
    }

    @Test
    void commandKey_spaceIsIndependentPerDomain_andReplayDoesNotCrossDomain() {
        reportReal("INC-CK");
        reportDrill("INC-CK", "drill-A", "batch-A");
        String shared = key();
        // 同一 commandKey 同参在两域各生效一次
        IncidentView real = service.takeover(Domain.REAL, "INC-CK", "alice",
                new TakeoverRequest(shared));
        IncidentView drill = service.takeover(Domain.DRILL, "INC-CK", "alice",
                new TakeoverRequest(shared));
        assertThat(real.domain()).isEqualTo("REAL");
        assertThat(drill.domain()).isEqualTo("DRILL");
        // 各域重放返回本域首次结果
        assertThat(service.takeover(Domain.REAL, "INC-CK", "alice", new TakeoverRequest(shared)))
                .isEqualTo(real);
        assertThat(service.takeover(Domain.DRILL, "INC-CK", "alice", new TakeoverRequest(shared)))
                .isEqualTo(drill);
        Integer realKeys = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE domain = 'REAL' AND command_key = ?",
                Integer.class, shared);
        Integer drillKeys = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE domain = 'DRILL' AND command_key = ?",
                Integer.class, shared);
        assertThat(realKeys).isEqualTo(1);
        assertThat(drillKeys).isEqualTo(1);
    }

    @Test
    void failedCommandDoesNotOccupyKey() {
        reportReal("INC-FAIL-KEY");
        service.takeover(Domain.REAL, "INC-FAIL-KEY", "alice", new TakeoverRequest(key()));
        String ck = key();
        // COMMANDING 直接 RESOLVED 属非法跳级（422），同键同参首次失败
        assertApiStatus(() -> service.changeStatus(Domain.REAL, "INC-FAIL-KEY", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(ck, "RESOLVED")),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 推进到 CONTAINED 后，同键同参重试应成功，证明失败不占键
        service.changeStatus(Domain.REAL, "INC-FAIL-KEY", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "CONTAINED"));
        IncidentView resolved = service.changeStatus(Domain.REAL, "INC-FAIL-KEY", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(ck, "RESOLVED"));
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        // 再重放返回首次成功结果，且只占用一条键记录
        assertThat(service.changeStatus(Domain.REAL, "INC-FAIL-KEY", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(ck, "RESOLVED")))
                .isEqualTo(resolved);
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, ck);
        assertThat(n).isEqualTo(1);
    }

    private int incidentNotifications() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox", Integer.class);
        return n == null ? 0 : n;
    }
}
