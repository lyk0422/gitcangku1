package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.CleanupRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskRequest;
import com.example.starter.incident.dto.Responses.CleanupHistoryItem;
import com.example.starter.incident.dto.Responses.CleanupView;
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
 * 演练批量清理测试：终态校验、整批 422 并列未终结事件、原子删除关联数据、
 * 清理墓碑禁止批次复用、批次清单与清理历史、cleanupKey 幂等语义。
 */
@SpringBootTest
class DrillCleanupTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private DrillCleanupService cleanupService;

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

    private IncidentView drill(String incidentKey, String batch) {
        return service.report(new ReportRequest(incidentKey, "S2", "演练", "r", "drill-" + batch, batch));
    }

    private void takeAndClose(String incidentKey) {
        service.takeover(Domain.DRILL, incidentKey, "alice", new TakeoverRequest(key()));
        advanceToClose(incidentKey);
    }

    private void advanceToClose(String incidentKey) {
        service.changeStatus(Domain.DRILL, incidentKey, "alice", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus(Domain.DRILL, incidentKey, "alice", new StatusRequest(key(), "RESOLVED"));
        service.changeStatus(Domain.DRILL, incidentKey, "alice", new StatusRequest(key(), "CLOSED"));
    }

    @Test
    void cleanup_rejectsBatchWithUnfinishedIncident_andListsIt() {
        drill("INC-B1", "batch-B");
        drill("INC-B2", "batch-B");
        takeAndClose("INC-B1");
        // INC-B2 仍 REPORTED
        assertThatThrownBy(() -> cleanupService.cleanup("alice",
                new CleanupRequest(key(), "batch-B")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("BATCH_NOT_TERMINAL");
                    assertThat(e.getMessage()).contains("INC-B2").contains("REPORTED");
                    assertThat(e.getMessage()).doesNotContain("INC-B1");
                });
        // 整批未删除：事件与批次仍在
        assertThat(service.get(Domain.DRILL, "INC-B1").status()).isEqualTo("CLOSED");
        assertThat(service.get(Domain.DRILL, "INC-B2").status()).isEqualTo("REPORTED");
        Integer cleanups = jdbc.queryForObject(
                "SELECT COUNT(*) FROM drill_cleanups", Integer.class);
        assertThat(cleanups).isZero();
    }

    @Test
    void cleanup_terminalStatesAccepted_includingCancelled() {
        drill("INC-C1", "batch-C");
        drill("INC-C2", "batch-C");
        drill("INC-C3", "batch-C");
        takeAndClose("INC-C1");
        // C2 -> RESOLVED（终态之一）
        service.takeover(Domain.DRILL, "INC-C2", "alice", new TakeoverRequest(key()));
        service.changeStatus(Domain.DRILL, "INC-C2", "alice", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus(Domain.DRILL, "INC-C2", "alice", new StatusRequest(key(), "RESOLVED"));
        // C3 -> CANCELLED
        service.takeover(Domain.DRILL, "INC-C3", "alice", new TakeoverRequest(key()));
        service.cancel(Domain.DRILL, "INC-C3", "alice",
                new com.example.starter.incident.dto.Requests.CancelRequest(key()));

        CleanupView view = cleanupService.cleanup("alice", new CleanupRequest(key(), "batch-C"));
        assertThat(view.deletedIncidents()).isEqualTo(3);
        assertThat(view.status()).isEqualTo("CLEANED");
        for (String ik : List.of("INC-C1", "INC-C2", "INC-C3")) {
            assertApiStatus(() -> service.get(Domain.DRILL, ik), HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void cleanup_atomicallyDeletesTasksEdgesTransfersEscalationsAndHistory() {
        drill("INC-A1", "batch-A");
        drill("INC-A2", "batch-A");
        service.takeover(Domain.DRILL, "INC-A1", "alice", new TakeoverRequest(key()));
        service.escalate(Domain.DRILL, "INC-A1", "alice",
                new com.example.starter.incident.dto.Requests.EscalateRequest(key(), "g1", "why"));
        service.initiateTransfer(Domain.DRILL, "INC-A1", "alice",
                new com.example.starter.incident.dto.Requests.TransferRequest(key(), "bob"));
        TaskView task = service.createTask(Domain.DRILL, "INC-A1", "alice",
                new TaskRequest(key(), "T1", "依赖同批另一事件", List.of("INC-A2")));
        advanceToClose("INC-A1");
        takeAndClose("INC-A2");

        cleanupService.cleanup("alice", new CleanupRequest(key(), "batch-A"));

        // 关联数据全部删除
        Integer tasks = jdbc.queryForObject("SELECT COUNT(*) FROM incident_tasks", Integer.class);
        Integer blockers = jdbc.queryForObject("SELECT COUNT(*) FROM incident_task_blockers", Integer.class);
        Integer transfers = jdbc.queryForObject("SELECT COUNT(*) FROM incident_transfers", Integer.class);
        Integer escalations = jdbc.queryForObject("SELECT COUNT(*) FROM incident_escalations", Integer.class);
        Integer history = jdbc.queryForObject("SELECT COUNT(*) FROM incident_status_history", Integer.class);
        Integer incidents = jdbc.queryForObject("SELECT COUNT(*) FROM incidents", Integer.class);
        assertThat(tasks).isZero();
        assertThat(blockers).isZero();
        assertThat(transfers).isZero();
        assertThat(escalations).isZero();
        assertThat(history).isZero();
        assertThat(incidents).isZero();
        // 任务 id 不应再悬空引用（表已空即可）
        assertThat(task.taskKey()).isEqualTo("T1");
    }

    @Test
    void cleanup_doesNotAffectOtherBatchesOrRealIncidents() {
        drill("INC-KEEP-DRILL", "batch-KEEP");
        service.report(new ReportRequest("INC-KEEP-REAL", "S1", "真实", "r"));
        drill("INC-GO", "batch-GO");
        takeAndClose("INC-GO");
        cleanupService.cleanup("alice", new CleanupRequest(key(), "batch-GO"));

        assertThat(service.get(Domain.DRILL, "INC-KEEP-DRILL").status()).isEqualTo("REPORTED");
        assertThat(service.get(Domain.REAL, "INC-KEEP-REAL").status()).isEqualTo("REPORTED");
        assertApiStatus(() -> service.get(Domain.DRILL, "INC-GO"), HttpStatus.NOT_FOUND);
    }

    @Test
    void cleanedBatchKey_cannotBeReusedForNewDrill() {
        drill("INC-R1", "batch-REUSE");
        takeAndClose("INC-R1");
        cleanupService.cleanup("alice", new CleanupRequest(key(), "batch-REUSE"));
        // 同批次标识再建演练事件 -> 404
        assertApiStatus(() -> drill("INC-R2", "batch-REUSE"), HttpStatus.NOT_FOUND);
    }

    @Test
    void writeToCleanedBatchIncident_returns404() {
        drill("INC-W1", "batch-W");
        takeAndClose("INC-W1");
        cleanupService.cleanup("alice", new CleanupRequest(key(), "batch-W"));
        assertApiStatus(() -> service.takeover(Domain.DRILL, "INC-W1", "alice",
                new TakeoverRequest(key())), HttpStatus.NOT_FOUND);
    }

    @Test
    void batchListing_andCleanupHistory() {
        drill("INC-L1", "batch-L");
        drill("INC-L2", "batch-L");
        List<IncidentView> before = cleanupService.listBatchIncidents("batch-L");
        assertThat(before).extracting(IncidentView::incidentKey)
                .containsExactly("INC-L1", "INC-L2");
        assertThat(before).allSatisfy(v -> assertThat(v.domain()).isEqualTo("DRILL"));

        takeAndClose("INC-L1");
        takeAndClose("INC-L2");
        String cleanupKey = key();
        CleanupView view = cleanupService.cleanup("alice",
                new CleanupRequest(cleanupKey, "batch-L"));

        // 清理后清单为空但批次墓碑仍在（不报 404）
        assertThat(cleanupService.listBatchIncidents("batch-L")).isEmpty();
        // 历史可查，按批次与全局
        List<CleanupHistoryItem> byBatch = cleanupService.listHistory("batch-L");
        assertThat(byBatch).hasSize(1);
        assertThat(byBatch.get(0).cleanupKey()).isEqualTo(cleanupKey);
        assertThat(byBatch.get(0).deletedIncidents()).isEqualTo(2);
        assertThat(byBatch.get(0).actor()).isEqualTo("alice");
        assertThat(cleanupService.listHistory(null)).extracting(CleanupHistoryItem::batchKey)
                .contains("batch-L");
        assertThat(view.cleanupKey()).isEqualTo(cleanupKey);
        // 未知批次 404
        assertApiStatus(() -> cleanupService.listBatchIncidents("nope"), HttpStatus.NOT_FOUND);
    }

    @Test
    void cleanupKey_idempotent_sameKeyReplaysDifferentKeyConflicts() {
        drill("INC-I1", "batch-I");
        drill("INC-I2", "batch-OTHER");
        takeAndClose("INC-I1");
        takeAndClose("INC-I2");
        String cleanupKey = key();
        CleanupView first = cleanupService.cleanup("alice",
                new CleanupRequest(cleanupKey, "batch-I"));
        // 同键同参重放首次结果
        CleanupView replay = cleanupService.cleanup("alice",
                new CleanupRequest(cleanupKey, "batch-I"));
        assertThat(replay).isEqualTo(first);
        // 不产生第二条历史，也不重复删除计数
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM drill_cleanups WHERE cleanup_key = ?", Integer.class, cleanupKey);
        assertThat(n).isEqualTo(1);
        // 同键异参（不同批次）-> 409
        assertApiStatus(() -> cleanupService.cleanup("alice",
                new CleanupRequest(cleanupKey, "batch-OTHER")), HttpStatus.CONFLICT);
    }

    @Test
    void cleanup_unknownBatch_404() {
        assertApiStatus(() -> cleanupService.cleanup("alice",
                new CleanupRequest(key(), "batch-UNKNOWN")), HttpStatus.NOT_FOUND);
    }
}
