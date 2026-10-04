package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.EvidenceActionRequest;
import com.example.starter.incident.dto.Requests.EvidenceRequirementRequest;
import com.example.starter.incident.dto.Requests.EvidenceUploadRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.EvidenceView;
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
 * 任务验收证据链服务测试：覆盖必需类别设置与版本校验、上传/确认/作废规则、
 * 完成证据门禁（422）、完成后冻结、指挥权变更与 requestId 幂等语义。
 */
@SpringBootTest
class IncidentEvidenceServiceTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_evidences");
        jdbc.update("DELETE FROM incident_task_evidence_requirements");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private IncidentView commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "证据链场景", "reporter-1"));
        return service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private TaskView createTask(String incidentKey, String actor, String taskKey,
                                List<String> blockers) {
        return service.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "任务" + taskKey, blockers));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private EvidenceView upload(String incidentKey, String taskKey, String actor,
                                String evidenceKey, String category) {
        return service.uploadEvidence(incidentKey, taskKey, actor,
                new EvidenceUploadRequest(key(), evidenceKey, category, "说明" + evidenceKey));
    }

    private EvidenceView confirm(String incidentKey, String taskKey, String evidenceKey,
                                 String actor) {
        return service.confirmEvidence(incidentKey, taskKey, evidenceKey, actor,
                new EvidenceActionRequest(key()));
    }

    @Test
    void evidenceChain_photoAndReading_thenDownstreamUnblocked() {
        commanding("INC-E1", "alice");
        commanding("INC-E2", "bob");
        createTask("INC-E1", "alice", "T-1", List.of());
        createTask("INC-E2", "bob", "T-9", List.of("INC-E1"));

        // 指挥人设置必需类别 PHOTO 与 READING，提交期望版本 1
        TaskView configured = service.setEvidenceRequirements("INC-E1", "T-1", "alice",
                new EvidenceRequirementRequest(key(), List.of("PHOTO", "READING"), 1L));
        assertThat(configured.version()).isEqualTo(2);
        assertThat(configured.requiredEvidenceCategories())
                .containsExactly("PHOTO", "READING");

        // 执行者上传、另一名验收人确认 PHOTO
        upload("INC-E1", "T-1", "exec-1", "EV-P1", "PHOTO");
        EvidenceView confirmed = confirm("INC-E1", "T-1", "EV-P1", "acceptor-1");
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.confirmedBy()).isEqualTo("acceptor-1");

        // 仅确认 PHOTO 不能完成：422，details 为缺失类别，任务仍 OPEN
        assertThatThrownBy(() -> service.completeTask("INC-E1", "T-1", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("EVIDENCE_INSUFFICIENT");
                    assertThat(e.details()).isEqualTo(List.of("READING"));
                });
        assertThat(service.getTask("INC-E1", "T-1").status()).isEqualTo("OPEN");
        // 下游任务仍被阻塞
        assertApiStatus(() -> service.completeTask("INC-E2", "T-9", "bob",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);

        // 补齐 READING 后完成，事件遏制下游任务才解除依赖
        upload("INC-E1", "T-1", "exec-2", "EV-R1", "READING");
        confirm("INC-E1", "T-1", "EV-R1", "acceptor-2");
        TaskView done = service.completeTask("INC-E1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        service.changeStatus("INC-E1", "alice", new StatusRequest(key(), "CONTAINED"));
        TaskView downstream = service.completeTask("INC-E2", "T-9", "bob",
                new TaskActionRequest(key()));
        assertThat(downstream.status()).isEqualTo("DONE");
    }

    @Test
    void setRequirements_validation() {
        commanding("INC-V1", "alice");
        createTask("INC-V1", "alice", "T-1", List.of());
        // 0 个类别 → 400
        assertApiStatus(() -> service.setEvidenceRequirements("INC-V1", "T-1", "alice",
                new EvidenceRequirementRequest(key(), List.of(), 1L)), HttpStatus.BAD_REQUEST);
        // 6 个类别 → 400
        assertApiStatus(() -> service.setEvidenceRequirements("INC-V1", "T-1", "alice",
                new EvidenceRequirementRequest(key(),
                        List.of("A", "B", "C", "D", "E", "F"), 1L)), HttpStatus.BAD_REQUEST);
        // 空类别 → 400
        assertApiStatus(() -> service.setEvidenceRequirements("INC-V1", "T-1", "alice",
                new EvidenceRequirementRequest(key(), List.of(" "), 1L)), HttpStatus.BAD_REQUEST);
        // 缺少 expectedTaskVersion → 400
        assertApiStatus(() -> service.setEvidenceRequirements("INC-V1", "T-1", "alice",
                new EvidenceRequirementRequest(key(), List.of("PHOTO"), null)),
                HttpStatus.BAD_REQUEST);
        // 重复类别去重后生效
        TaskView view = service.setEvidenceRequirements("INC-V1", "T-1", "alice",
                new EvidenceRequirementRequest(key(), List.of("PHOTO", "PHOTO", "READING"), 1L));
        assertThat(view.requiredEvidenceCategories()).containsExactly("PHOTO", "READING");
    }

    @Test
    void setRequirements_versionConflict() {
        commanding("INC-V2", "alice");
        createTask("INC-V2", "alice", "T-1", List.of());
        // 期望版本不匹配 → 409
        assertApiStatus(() -> service.setEvidenceRequirements("INC-V2", "T-1", "alice",
                new EvidenceRequirementRequest(key(), List.of("PHOTO"), 9L)),
                HttpStatus.CONFLICT);
        TaskView v2 = service.setEvidenceRequirements("INC-V2", "T-1", "alice",
                new EvidenceRequirementRequest(key(), List.of("PHOTO"), 1L));
        assertThat(v2.version()).isEqualTo(2);
        // 版本已推进，旧期望版本 → 409；新期望版本成功
        assertApiStatus(() -> service.setEvidenceRequirements("INC-V2", "T-1", "alice",
                new EvidenceRequirementRequest(key(), List.of("PHOTO"), 1L)),
                HttpStatus.CONFLICT);
        TaskView v3 = service.setEvidenceRequirements("INC-V2", "T-1", "alice",
                new EvidenceRequirementRequest(key(), List.of("READING"), 2L));
        assertThat(v3.version()).isEqualTo(3);
        assertThat(v3.requiredEvidenceCategories()).containsExactly("READING");
    }
    @Test
    void setRequirements_commanderPermissionAcrossTransfer() {
        commanding("INC-P1", "alice");
        createTask("INC-P1", "alice", "T-1", List.of());
        // 非指挥人 → 409
        assertApiStatus(() -> service.setEvidenceRequirements("INC-P1", "T-1", "bob",
                new EvidenceRequirementRequest(key(), List.of("PHOTO"), 1L)),
                HttpStatus.CONFLICT);
        // 待交接期间仍按原指挥权限
        service.initiateTransfer("INC-P1", "alice", new TransferRequest(key(), "bob"));
        TaskView pending = service.setEvidenceRequirements("INC-P1", "T-1", "alice",
                new EvidenceRequirementRequest(key(), List.of("PHOTO"), 1L));
        assertThat(pending.version()).isEqualTo(2);
        // 交接后旧指挥不能配置，新指挥可以
        service.acceptTransfer("INC-P1", "bob", new TransferAcceptRequest(key()));
        assertApiStatus(() -> service.setEvidenceRequirements("INC-P1", "T-1", "alice",
                new EvidenceRequirementRequest(key(), List.of("PHOTO"), 2L)),
                HttpStatus.CONFLICT);
        TaskView byNew = service.setEvidenceRequirements("INC-P1", "T-1", "bob",
                new EvidenceRequirementRequest(key(), List.of("READING"), 2L));
        assertThat(byNew.requiredEvidenceCategories()).containsExactly("READING");
    }

    @Test
    void permissionCheck_precedesReplay() {
        commanding("INC-P2", "alice");
        createTask("INC-P2", "alice", "T-1", List.of());
        String requestId = key();
        List<String> categories = List.of("PHOTO");
        TaskView first = service.setEvidenceRequirements("INC-P2", "T-1", "alice",
                new EvidenceRequirementRequest(requestId, categories, 1L));
        // 同 requestId 同参重放返回首次完整响应
        TaskView replay = service.setEvidenceRequirements("INC-P2", "T-1", "alice",
                new EvidenceRequirementRequest(requestId, categories, 1L));
        assertThat(replay).isEqualTo(first);
        // 交接后旧指挥重放同键同参：权限校验先于重放 → 409
        service.initiateTransfer("INC-P2", "alice", new TransferRequest(key(), "bob"));
        service.acceptTransfer("INC-P2", "bob", new TransferAcceptRequest(key()));
        assertApiStatus(() -> service.setEvidenceRequirements("INC-P2", "T-1", "alice",
                new EvidenceRequirementRequest(requestId, categories, 1L)),
                HttpStatus.CONFLICT);
    }

    @Test
    void confirmRules_acceptorMustDifferFromUploader() {
        commanding("INC-C1", "alice");
        createTask("INC-C1", "alice", "T-1", List.of());
        upload("INC-C1", "T-1", "exec-1", "EV-1", "PHOTO");
        // 验收人与上传人相同 → 409
        assertApiStatus(() -> confirm("INC-C1", "T-1", "EV-1", "exec-1"), HttpStatus.CONFLICT);
        // 不同验收人确认成功
        EvidenceView confirmed = confirm("INC-C1", "T-1", "EV-1", "acceptor-1");
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.confirmedAt()).isNotNull();
        // 重复确认 → 409
        assertApiStatus(() -> confirm("INC-C1", "T-1", "EV-1", "acceptor-2"),
                HttpStatus.CONFLICT);
        // 作废后不能确认
        upload("INC-C1", "T-1", "exec-1", "EV-2", "READING");
        service.voidEvidence("INC-C1", "T-1", "EV-2", "exec-1", new EvidenceActionRequest(key()));
        assertApiStatus(() -> confirm("INC-C1", "T-1", "EV-2", "acceptor-1"),
                HttpStatus.CONFLICT);
        // 证据不存在 → 404
        assertApiStatus(() -> confirm("INC-C1", "T-1", "EV-9", "acceptor-1"),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void void_invalidatesConfirmation_andBlocksCompletion() {
        commanding("INC-D1", "alice");
        createTask("INC-D1", "alice", "T-1", List.of());
        service.setEvidenceRequirements("INC-D1", "T-1", "alice",
                new EvidenceRequirementRequest(key(), List.of("PHOTO"), 1L));
        upload("INC-D1", "T-1", "exec-1", "EV-1", "PHOTO");
        confirm("INC-D1", "T-1", "EV-1", "acceptor-1");
        // 完成前作废：确认失效，完成 → 422
        EvidenceView voided = service.voidEvidence("INC-D1", "T-1", "EV-1", "exec-1",
                new EvidenceActionRequest(key()));
        assertThat(voided.status()).isEqualTo("VOID");
        assertThat(voided.voidedBy()).isEqualTo("exec-1");
        assertApiStatus(() -> service.completeTask("INC-D1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);
        // 重复作废 → 409；无关人不能作废 → 409；当前指挥人可作废
        assertApiStatus(() -> service.voidEvidence("INC-D1", "T-1", "EV-1", "exec-1",
                new EvidenceActionRequest(key())), HttpStatus.CONFLICT);
        upload("INC-D1", "T-1", "exec-2", "EV-2", "PHOTO");
        assertApiStatus(() -> service.voidEvidence("INC-D1", "T-1", "EV-2", "stranger",
                new EvidenceActionRequest(key())), HttpStatus.CONFLICT);
        service.voidEvidence("INC-D1", "T-1", "EV-2", "alice", new EvidenceActionRequest(key()));
        // 重新上传并确认后完成成功
        upload("INC-D1", "T-1", "exec-1", "EV-3", "PHOTO");
        confirm("INC-D1", "T-1", "EV-3", "acceptor-1");
        assertThat(service.completeTask("INC-D1", "T-1", "alice",
                new TaskActionRequest(key())).status()).isEqualTo("DONE");
    }
    @Test
    void frozenAfterDone_noVoidNoOverwriteNoConfig() {
        commanding("INC-Z1", "alice");
        createTask("INC-Z1", "alice", "T-1", List.of());
        service.setEvidenceRequirements("INC-Z1", "T-1", "alice",
                new EvidenceRequirementRequest(key(), List.of("PHOTO"), 1L));
        upload("INC-Z1", "T-1", "exec-1", "EV-1", "PHOTO");
        confirm("INC-Z1", "T-1", "EV-1", "acceptor-1");
        // 完成前遗留一条未确认证据，用于验证完成后确认被拒
        upload("INC-Z1", "T-1", "exec-1", "EV-2", "READING");
        service.completeTask("INC-Z1", "T-1", "alice", new TaskActionRequest(key()));
        // 完成后禁止作废、确认、上传与必需类别变更（不能倒改已完成任务）
        assertApiStatus(() -> service.voidEvidence("INC-Z1", "T-1", "EV-1", "alice",
                new EvidenceActionRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> confirm("INC-Z1", "T-1", "EV-2", "acceptor-1"),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> upload("INC-Z1", "T-1", "exec-1", "EV-3", "PHOTO"),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> service.setEvidenceRequirements("INC-Z1", "T-1", "alice",
                new EvidenceRequirementRequest(key(), List.of("READING"), 3L)),
                HttpStatus.CONFLICT);
        // 冻结当时证据内容与确认人
        EvidenceView frozen = service.listEvidences("INC-Z1", "T-1").evidences().stream()
                .filter(e -> e.evidenceKey().equals("EV-1")).findFirst().orElseThrow();
        assertThat(frozen.status()).isEqualTo("CONFIRMED");
        assertThat(frozen.confirmedBy()).isEqualTo("acceptor-1");
        assertThat(frozen.voidedBy()).isNull();
    }

    @Test
    void upload_evidenceKeyIdempotency() {
        commanding("INC-K1", "alice");
        createTask("INC-K1", "alice", "T-1", List.of());
        EvidenceView first = upload("INC-K1", "T-1", "exec-1", "EV-1", "PHOTO");
        // 同号同内容（不同 requestId）幂等返回首次证据
        EvidenceView again = upload("INC-K1", "T-1", "exec-1", "EV-1", "PHOTO");
        assertThat(again).isEqualTo(first);
        assertThat(service.listEvidences("INC-K1", "T-1").evidences()).hasSize(1);
        // 同号不同内容 → 409
        assertApiStatus(() -> service.uploadEvidence("INC-K1", "T-1", "exec-1",
                new EvidenceUploadRequest(key(), "EV-1", "READING", "说明EV-1")),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> service.uploadEvidence("INC-K1", "T-1", "exec-2",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "说明EV-1")),
                HttpStatus.CONFLICT);
    }

    @Test
    void requestId_idempotencyAndFailureNotConsuming() {
        commanding("INC-I1", "alice");
        createTask("INC-I1", "alice", "T-1", List.of());
        // 失败不占键：版本冲突 409 后同 requestId 可正确重试
        String requestId = key();
        assertApiStatus(() -> service.setEvidenceRequirements("INC-I1", "T-1", "alice",
                new EvidenceRequirementRequest(requestId, List.of("PHOTO"), 9L)),
                HttpStatus.CONFLICT);
        TaskView ok = service.setEvidenceRequirements("INC-I1", "T-1", "alice",
                new EvidenceRequirementRequest(requestId, List.of("PHOTO"), 1L));
        assertThat(ok.version()).isEqualTo(2);
        // 同键异参 → 409
        assertApiStatus(() -> service.setEvidenceRequirements("INC-I1", "T-1", "alice",
                new EvidenceRequirementRequest(requestId, List.of("READING"), 1L)),
                HttpStatus.CONFLICT);
        // 上传：同键同参重放首次完整响应
        String uploadId = key();
        EvidenceView uploaded = service.uploadEvidence("INC-I1", "T-1", "exec-1",
                new EvidenceUploadRequest(uploadId, "EV-1", "PHOTO", "现场照片"));
        EvidenceView replayed = service.uploadEvidence("INC-I1", "T-1", "exec-1",
                new EvidenceUploadRequest(uploadId, "EV-1", "PHOTO", "现场照片"));
        assertThat(replayed).isEqualTo(uploaded);
        assertThat(service.listEvidences("INC-I1", "T-1").evidences()).hasSize(1);
        // 确认：同键重放首次响应
        String confirmId = key();
        EvidenceView confirmed = service.confirmEvidence("INC-I1", "T-1", "EV-1",
                "acceptor-1", new EvidenceActionRequest(confirmId));
        EvidenceView confirmedReplay = service.confirmEvidence("INC-I1", "T-1", "EV-1",
                "acceptor-1", new EvidenceActionRequest(confirmId));
        assertThat(confirmedReplay).isEqualTo(confirmed);
        // 同键跨操作 → 409
        assertApiStatus(() -> service.voidEvidence("INC-I1", "T-1", "EV-1", "exec-1",
                new EvidenceActionRequest(confirmId)), HttpStatus.CONFLICT);
    }

    @Test
    void evidenceQueries_notFound() {
        commanding("INC-Q1", "alice");
        createTask("INC-Q1", "alice", "T-1", List.of());
        assertApiStatus(() -> service.listEvidences("INC-404", "T-1"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.listEvidences("INC-Q1", "T-9"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.uploadEvidence("INC-Q1", "T-9", "exec-1",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "n")), HttpStatus.NOT_FOUND);
        assertThat(service.listEvidences("INC-Q1", "T-1").evidences()).isEmpty();
    }
}