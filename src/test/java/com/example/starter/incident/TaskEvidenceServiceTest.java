package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.EvidenceActionRequest;
import com.example.starter.incident.dto.Requests.EvidenceRequirementsRequest;
import com.example.starter.incident.dto.Requests.EvidenceUploadRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.EvidenceChainView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.TaskEvidenceView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 任务验收证据链服务测试：覆盖必需类别配置（指挥权限、版本乐观控制）、上传/确认/作废
 * 生命周期、完成证据门禁（422）、完成后冻结、requestId 幂等与权限先于重放。
 */
@SpringBootTest
class TaskEvidenceServiceTest {

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
        jdbc.update("DELETE FROM incident_task_required_categories");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "REQ-" + UUID.randomUUID();
    }

    private IncidentView commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "合成场景", "reporter-1"));
        return service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private TaskView openTask(String incidentKey, String commander, String taskKey) {
        return service.createTask(incidentKey, commander,
                new TaskCreateRequest(key(), taskKey, "G", "合成任务", List.of()));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> details(ApiException e) {
        return (List<Object>) e.details();
    }

    @Test
    void evidenceChain_mainFlow_photoAndReading() {
        commanding("INC-UP", "alice");
        commanding("INC-DOWN", "carol");
        openTask("INC-UP", "alice", "T-1");
        // 下游任务依赖上游事件
        service.createTask("INC-DOWN", "carol",
                new TaskCreateRequest(key(), "T-9", "G", "下游任务", List.of("INC-UP")));

        // 指挥人设置必需类别 PHOTO 与 READING（版本 0 → 1）
        TaskView configured = service.setEvidenceRequirements("INC-UP", "T-1", "alice",
                new EvidenceRequirementsRequest(key(), 0, List.of("PHOTO", "READING")));
        assertThat(configured.requirementVersion()).isEqualTo(1);
        assertThat(configured.requiredCategories()).containsExactly("PHOTO", "READING");

        // 执行者上传 PHOTO，另一名验收人确认
        TaskEvidenceView photo = service.uploadEvidence("INC-UP", "T-1", "executor-1",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "现场照片"));
        assertThat(photo.status()).isEqualTo("PENDING");
        assertThat(photo.uploadedBy()).isEqualTo("executor-1");
        TaskEvidenceView photoConfirmed = service.confirmEvidence("INC-UP", "T-1", "EV-1",
                "verifier-1", new EvidenceActionRequest(key()));
        assertThat(photoConfirmed.status()).isEqualTo("CONFIRMED");
        assertThat(photoConfirmed.confirmedBy()).isEqualTo("verifier-1");

        // 仅确认 PHOTO 不能完成：422，任务仍 OPEN，下游依赖不解除
        assertThatThrownBy(() -> service.completeTask("INC-UP", "T-1", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("EVIDENCE_INSUFFICIENT");
                    assertThat(details(e)).containsExactly("READING");
                });
        assertThat(service.getTask("INC-UP", "T-1").status()).isEqualTo("OPEN");

        // 补齐 READING 后完成，完成时证据冻结
        service.uploadEvidence("INC-UP", "T-1", "executor-2",
                new EvidenceUploadRequest(key(), "EV-2", "READING", "仪表读数"));
        service.confirmEvidence("INC-UP", "T-1", "EV-2", "verifier-1",
                new EvidenceActionRequest(key()));
        TaskView done = service.completeTask("INC-UP", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        EvidenceChainView chain = service.evidenceChain("INC-UP", "T-1");
        assertThat(chain.evidences()).hasSize(2);
        assertThat(chain.evidences()).allSatisfy(ev -> {
            assertThat(ev.status()).isEqualTo("CONFIRMED");
            assertThat(ev.frozen()).isTrue();
        });

        // 上游事件推进到 RESOLVED（要求无 OPEN 任务）后，下游任务解除依赖
        service.changeStatus("INC-UP", "alice", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-UP", "alice", new StatusRequest(key(), "RESOLVED"));
        TaskView downstream = service.completeTask("INC-DOWN", "T-9", "carol",
                new TaskActionRequest(key()));
        assertThat(downstream.status()).isEqualTo("DONE");
    }

    @Test
    void setRequirements_validationAndVersion() {
        commanding("INC-V", "alice");
        openTask("INC-V", "alice", "T-1");
        // 类别数量边界：0 个与 6 个均 400
        assertApiStatus(() -> service.setEvidenceRequirements("INC-V", "T-1", "alice",
                new EvidenceRequirementsRequest(key(), 0, List.of())), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.setEvidenceRequirements("INC-V", "T-1", "alice",
                new EvidenceRequirementsRequest(key(), 0,
                        List.of("A", "B", "C", "D", "E", "F"))), HttpStatus.BAD_REQUEST);
        // 空类别、空 requestId、空/负版本均 400
        assertApiStatus(() -> service.setEvidenceRequirements("INC-V", "T-1", "alice",
                new EvidenceRequirementsRequest(key(), 0, List.of(" "))), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.setEvidenceRequirements("INC-V", "T-1", "alice",
                new EvidenceRequirementsRequest(null, 0, List.of("A"))), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.setEvidenceRequirements("INC-V", "T-1", "alice",
                new EvidenceRequirementsRequest(key(), null, List.of("A"))),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.setEvidenceRequirements("INC-V", "T-1", "alice",
                new EvidenceRequirementsRequest(key(), -1, List.of("A"))), HttpStatus.BAD_REQUEST);
        // 重复类别去重后设置成功
        TaskView v1 = service.setEvidenceRequirements("INC-V", "T-1", "alice",
                new EvidenceRequirementsRequest(key(), 0, List.of("PHOTO", "PHOTO", "READING")));
        assertThat(v1.requiredCategories()).containsExactly("PHOTO", "READING");
        // 版本不一致 409；失败不占键，修正版本后同 requestId 可成功
        String rid = key();
        assertApiStatus(() -> service.setEvidenceRequirements("INC-V", "T-1", "alice",
                new EvidenceRequirementsRequest(rid, 0, List.of("PHOTO"))), HttpStatus.CONFLICT);
        TaskView v2 = service.setEvidenceRequirements("INC-V", "T-1", "alice",
                new EvidenceRequirementsRequest(rid, 1, List.of("PHOTO")));
        assertThat(v2.requirementVersion()).isEqualTo(2);
        assertThat(v2.requiredCategories()).containsExactly("PHOTO");
        // 任务不存在 404
        assertApiStatus(() -> service.setEvidenceRequirements("INC-V", "T-9", "alice",
                new EvidenceRequirementsRequest(key(), 0, List.of("A"))), HttpStatus.NOT_FOUND);
    }

    @Test
    void setRequirements_commanderOnlyAndHandover() {
        commanding("INC-H", "alice");
        openTask("INC-H", "alice", "T-1");
        // 非指挥人不能配置
        assertApiStatus(() -> service.setEvidenceRequirements("INC-H", "T-1", "bob",
                new EvidenceRequirementsRequest(key(), 0, List.of("PHOTO"))), HttpStatus.CONFLICT);
        // 待交接期间仍按原指挥权限
        service.initiateTransfer("INC-H", "alice", new TransferRequest(key(), "bob"));
        TaskView pending = service.setEvidenceRequirements("INC-H", "T-1", "alice",
                new EvidenceRequirementsRequest(key(), 0, List.of("PHOTO")));
        assertThat(pending.requirementVersion()).isEqualTo(1);
        // 交接后旧指挥不能配置，新指挥可以
        service.acceptTransfer("INC-H", "bob", new TransferAcceptRequest(key()));
        assertApiStatus(() -> service.setEvidenceRequirements("INC-H", "T-1", "alice",
                new EvidenceRequirementsRequest(key(), 1, List.of("PHOTO", "READING"))),
                HttpStatus.CONFLICT);
        TaskView byNew = service.setEvidenceRequirements("INC-H", "T-1", "bob",
                new EvidenceRequirementsRequest(key(), 1, List.of("PHOTO", "READING")));
        assertThat(byNew.requirementVersion()).isEqualTo(2);
    }

    @Test
    void setRequirements_permissionCheckedBeforeReplay() {
        commanding("INC-P", "alice");
        openTask("INC-P", "alice", "T-1");
        String rid = key();
        TaskView first = service.setEvidenceRequirements("INC-P", "T-1", "alice",
                new EvidenceRequirementsRequest(rid, 0, List.of("PHOTO")));
        // 同键同参重放首次完整响应
        TaskView replay = service.setEvidenceRequirements("INC-P", "T-1", "alice",
                new EvidenceRequirementsRequest(rid, 0, List.of("PHOTO")));
        assertThat(replay).isEqualTo(first);
        // 交接后旧指挥用同键同参重放：权限校验先于重放，返回 409 而非首次响应
        service.initiateTransfer("INC-P", "alice", new TransferRequest(key(), "bob"));
        service.acceptTransfer("INC-P", "bob", new TransferAcceptRequest(key()));
        assertApiStatus(() -> service.setEvidenceRequirements("INC-P", "T-1", "alice",
                new EvidenceRequirementsRequest(rid, 0, List.of("PHOTO"))), HttpStatus.CONFLICT);
        // 同键异参（不同类别/版本/操作者）均 409
        assertApiStatus(() -> service.setEvidenceRequirements("INC-P", "T-1", "bob",
                new EvidenceRequirementsRequest(rid, 1, List.of("PHOTO"))), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.setEvidenceRequirements("INC-P", "T-1", "bob",
                new EvidenceRequirementsRequest(rid, 0, List.of("READING"))),
                HttpStatus.CONFLICT);
    }
    @Test
    void uploadEvidence_idempotencyAndFrozen() {
        commanding("INC-U", "alice");
        openTask("INC-U", "alice", "T-1");
        // 同 evidenceKey 同内容：幂等返回首次证据
        TaskEvidenceView first = service.uploadEvidence("INC-U", "T-1", "executor-1",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "照片"));
        TaskEvidenceView again = service.uploadEvidence("INC-U", "T-1", "executor-1",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "照片"));
        assertThat(again).isEqualTo(first);
        assertThat(service.evidenceChain("INC-U", "T-1").evidences()).hasSize(1);
        // 同键不同内容（说明或类别或上传人不同）409
        assertApiStatus(() -> service.uploadEvidence("INC-U", "T-1", "executor-1",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "另一说明")),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> service.uploadEvidence("INC-U", "T-1", "executor-1",
                new EvidenceUploadRequest(key(), "EV-1", "READING", "照片")),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> service.uploadEvidence("INC-U", "T-1", "executor-2",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "照片")),
                HttpStatus.CONFLICT);
        // 参数校验 400
        assertApiStatus(() -> service.uploadEvidence("INC-U", "T-1", "executor-1",
                new EvidenceUploadRequest(key(), " ", "PHOTO", "n")), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.uploadEvidence("INC-U", "T-1", "executor-1",
                new EvidenceUploadRequest(key(), "EV-2", " ", "n")), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.uploadEvidence("INC-U", "T-1", "executor-1",
                new EvidenceUploadRequest(key(), "EV-2", "PHOTO", " ")), HttpStatus.BAD_REQUEST);
        // 任务完成后禁止上传（覆盖已冻结证据）
        service.uploadEvidence("INC-U", "T-1", "executor-1",
                new EvidenceUploadRequest(key(), "EV-2", "READING", "读数"));
        service.completeTask("INC-U", "T-1", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> service.uploadEvidence("INC-U", "T-1", "executor-1",
                new EvidenceUploadRequest(key(), "EV-3", "PHOTO", "新发现")),
                HttpStatus.CONFLICT);
    }

    @Test
    void confirmEvidence_verifierMustDifferFromUploader() {
        commanding("INC-C", "alice");
        openTask("INC-C", "alice", "T-1");
        service.uploadEvidence("INC-C", "T-1", "executor-1",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "照片"));
        // 上传人不能自验，409 且不占 requestId
        String rid = key();
        assertApiStatus(() -> service.confirmEvidence("INC-C", "T-1", "EV-1", "executor-1",
                new EvidenceActionRequest(rid)), HttpStatus.CONFLICT);
        // 另一名验收人确认成功；失败未占键，此处使用新键
        TaskEvidenceView confirmed = service.confirmEvidence("INC-C", "T-1", "EV-1",
                "verifier-1", new EvidenceActionRequest(rid));
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.confirmedBy()).isEqualTo("verifier-1");
        assertThat(confirmed.confirmedAt()).isNotNull();
        // 重复确认（新 requestId）409；确认不存在证据 404
        assertApiStatus(() -> service.confirmEvidence("INC-C", "T-1", "EV-1", "verifier-2",
                new EvidenceActionRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.confirmEvidence("INC-C", "T-1", "EV-9", "verifier-1",
                new EvidenceActionRequest(key())), HttpStatus.NOT_FOUND);
    }

    @Test
    void confirmEvidence_requestIdReplay() {
        commanding("INC-R", "alice");
        openTask("INC-R", "alice", "T-1");
        service.uploadEvidence("INC-R", "T-1", "executor-1",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "照片"));
        String rid = key();
        TaskEvidenceView first = service.confirmEvidence("INC-R", "T-1", "EV-1", "verifier-1",
                new EvidenceActionRequest(rid));
        // 同键同参重放首次响应，不产生二次效果
        TaskEvidenceView replay = service.confirmEvidence("INC-R", "T-1", "EV-1", "verifier-1",
                new EvidenceActionRequest(rid));
        assertThat(replay).isEqualTo(first);
        // 同键不同操作者（异参）409
        assertApiStatus(() -> service.confirmEvidence("INC-R", "T-1", "EV-1", "verifier-2",
                new EvidenceActionRequest(rid)), HttpStatus.CONFLICT);
    }

    @Test
    void voidEvidence_invalidatesConfirmation() {
        commanding("INC-X", "alice");
        openTask("INC-X", "alice", "T-1");
        service.setEvidenceRequirements("INC-X", "T-1", "alice",
                new EvidenceRequirementsRequest(key(), 0, List.of("PHOTO")));
        service.uploadEvidence("INC-X", "T-1", "executor-1",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "照片"));
        service.confirmEvidence("INC-X", "T-1", "EV-1", "verifier-1",
                new EvidenceActionRequest(key()));
        // 非指挥人不能作废
        assertApiStatus(() -> service.voidEvidence("INC-X", "T-1", "EV-1", "bob",
                new EvidenceActionRequest(key())), HttpStatus.CONFLICT);
        // 指挥人作废已确认证据：确认失效，完成门禁重新 422
        TaskEvidenceView voided = service.voidEvidence("INC-X", "T-1", "EV-1", "alice",
                new EvidenceActionRequest(key()));
        assertThat(voided.status()).isEqualTo("VOIDED");
        assertThat(voided.voidedBy()).isEqualTo("alice");
        assertThat(voided.voidedAt()).isNotNull();
        assertApiStatus(() -> service.completeTask("INC-X", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);
        // 作废为终态：重复作废、作废后确认、作废键复用均 409
        assertApiStatus(() -> service.voidEvidence("INC-X", "T-1", "EV-1", "alice",
                new EvidenceActionRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.confirmEvidence("INC-X", "T-1", "EV-1", "verifier-1",
                new EvidenceActionRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.uploadEvidence("INC-X", "T-1", "executor-1",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "照片")),
                HttpStatus.CONFLICT);
        // 新证据补齐后可完成
        service.uploadEvidence("INC-X", "T-1", "executor-1",
                new EvidenceUploadRequest(key(), "EV-2", "PHOTO", "重拍照片"));
        service.confirmEvidence("INC-X", "T-1", "EV-2", "verifier-1",
                new EvidenceActionRequest(key()));
        TaskView done = service.completeTask("INC-X", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
    }

    @Test
    void frozenAfterCompletion_noMutationAllowed() {
        commanding("INC-F", "alice");
        openTask("INC-F", "alice", "T-1");
        service.setEvidenceRequirements("INC-F", "T-1", "alice",
                new EvidenceRequirementsRequest(key(), 0, List.of("PHOTO")));
        service.uploadEvidence("INC-F", "T-1", "executor-1",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "照片"));
        service.uploadEvidence("INC-F", "T-1", "executor-1",
                new EvidenceUploadRequest(key(), "EV-2", "PHOTO", "备选照片"));
        service.confirmEvidence("INC-F", "T-1", "EV-1", "verifier-1",
                new EvidenceActionRequest(key()));
        service.completeTask("INC-F", "T-1", "alice", new TaskActionRequest(key()));
        // 完成后禁止作废、确认、上传、改必需类别；冻结时的确认人保留
        assertApiStatus(() -> service.voidEvidence("INC-F", "T-1", "EV-1", "alice",
                new EvidenceActionRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.confirmEvidence("INC-F", "T-1", "EV-2", "verifier-1",
                new EvidenceActionRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.setEvidenceRequirements("INC-F", "T-1", "alice",
                new EvidenceRequirementsRequest(key(), 1, List.of("READING"))),
                HttpStatus.CONFLICT);
        EvidenceChainView chain = service.evidenceChain("INC-F", "T-1");
        assertThat(chain.requirementVersion()).isEqualTo(1);
        assertThat(chain.evidences()).allSatisfy(ev -> assertThat(ev.frozen()).isTrue());
        TaskEvidenceView ev1 = chain.evidences().stream()
                .filter(ev -> ev.evidenceKey().equals("EV-1")).findFirst().orElseThrow();
        assertThat(ev1.status()).isEqualTo("CONFIRMED");
        assertThat(ev1.confirmedBy()).isEqualTo("verifier-1");
        // 待确认证据在冻结后保持 PENDING 且不可再确认
        TaskEvidenceView ev2 = chain.evidences().stream()
                .filter(ev -> ev.evidenceKey().equals("EV-2")).findFirst().orElseThrow();
        assertThat(ev2.status()).isEqualTo("PENDING");
    }

    @Test
    void completeGate_pendingOrMissingEvidence_422() {
        commanding("INC-G", "alice");
        openTask("INC-G", "alice", "T-1");
        service.setEvidenceRequirements("INC-G", "T-1", "alice",
                new EvidenceRequirementsRequest(key(), 0, List.of("PHOTO", "READING")));
        // 完全无证据：422，details 列出全部缺失类别
        assertThatThrownBy(() -> service.completeTask("INC-G", "T-1", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(details(e)).containsExactlyInAnyOrder("PHOTO", "READING");
                });
        // 已上传未确认不计入：仍 422
        service.uploadEvidence("INC-G", "T-1", "executor-1",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "照片"));
        assertApiStatus(() -> service.completeTask("INC-G", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);
        // 未设置必需类别的任务不受证据门禁限制
        openTask("INC-G", "alice", "T-2");
        TaskView done = service.completeTask("INC-G", "T-2", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        // 422 不改变任务状态
        assertThat(service.getTask("INC-G", "T-1").status()).isEqualTo("OPEN");
    }

    @Test
    void terminalTask_rejectsEvidenceOperations() {
        commanding("INC-T", "alice");
        openTask("INC-T", "alice", "T-1");
        service.cancelTask("INC-T", "T-1", "alice", new TaskActionRequest(key()));
        // CANCELLED 终态：配置、上传均 409
        assertApiStatus(() -> service.setEvidenceRequirements("INC-T", "T-1", "alice",
                new EvidenceRequirementsRequest(key(), 0, List.of("PHOTO"))),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> service.uploadEvidence("INC-T", "T-1", "executor-1",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "照片")),
                HttpStatus.CONFLICT);
        // 查询证据链：事件/任务不存在 404
        assertApiStatus(() -> service.evidenceChain("INC-404", "T-1"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.evidenceChain("INC-T", "T-9"), HttpStatus.NOT_FOUND);
    }
}