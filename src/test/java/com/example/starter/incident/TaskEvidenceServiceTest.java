package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.EvidenceActionRequest;
import com.example.starter.incident.dto.Requests.EvidenceConfigRequest;
import com.example.starter.incident.dto.Requests.EvidenceUploadRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.EvidenceChainView;
import com.example.starter.incident.dto.Responses.EvidenceRequirementsView;
import com.example.starter.incident.dto.Responses.EvidenceView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 任务验收证据链服务测试：覆盖必需类别配置与版本校验、上传/确认/作废规则、
 * 完成证据门禁（422）、完成冻结、指挥权交接下的权限与 requestId 幂等语义。
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
        jdbc.update("DELETE FROM task_evidence");
        jdbc.update("DELETE FROM task_required_categories");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "REQ-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "证据链场景", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void openTask(String incidentKey, String taskKey) {
        service.createTask(incidentKey, currentCommander(incidentKey),
                new TaskCreateRequest(key(), taskKey, "G", "处置任务", List.of()));
    }

    private String currentCommander(String incidentKey) {
        return service.get(incidentKey).commander();
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> details(ApiException e) {
        return (List<Object>) e.details();
    }

    private EvidenceView upload(String incidentKey, String taskKey, String evidenceNo,
                                String category, String uploader) {
        return service.uploadEvidence(incidentKey, taskKey, uploader,
                new EvidenceUploadRequest(key(), evidenceNo, category, "合成证据-" + evidenceNo));
    }

    @Test
    void evidenceChain_mainFlow() {
        commanding("INC-E1", "alice");
        openTask("INC-E1", "T-1");
        EvidenceRequirementsView config = service.configureEvidenceRequirements("INC-E1", "T-1",
                "alice", new EvidenceConfigRequest(key(), 1L, List.of("READING", "PHOTO")));
        assertThat(config.taskKey()).isEqualTo("T-1");
        assertThat(config.version()).isEqualTo(2);
        assertThat(config.categories()).containsExactly("PHOTO", "READING");

        EvidenceView uploaded = upload("INC-E1", "T-1", "EV-1", "PHOTO", "exec-1");
        assertThat(uploaded.status()).isEqualTo("PENDING");
        assertThat(uploaded.uploadedBy()).isEqualTo("exec-1");
        assertThat(uploaded.confirmedBy()).isNull();
        assertThat(uploaded.frozenAt()).isNull();

        EvidenceView confirmed = service.confirmEvidence("INC-E1", "T-1", "EV-1", "rev-1",
                new EvidenceActionRequest(key()));
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(confirmed.confirmedBy()).isEqualTo("rev-1");
        assertThat(confirmed.confirmedAt()).isNotNull();

        EvidenceChainView chain = service.evidenceChain("INC-E1", "T-1");
        assertThat(chain.taskVersion()).isEqualTo(2);
        assertThat(chain.requiredCategories()).containsExactly("PHOTO", "READING");
        assertThat(chain.evidence()).hasSize(1);
        assertThat(chain.evidence().get(0).evidenceNo()).isEqualTo("EV-1");
    }

    @Test
    void completeGate_missingAndInvalidatedEvidence_422() {
        commanding("INC-G1", "alice");
        openTask("INC-G1", "T-1");
        service.configureEvidenceRequirements("INC-G1", "T-1", "alice",
                new EvidenceConfigRequest(key(), 1L, List.of("PHOTO", "READING")));

        // 无任何证据：422，details 列出全部缺失类别，任务保持 OPEN
        assertThatThrownBy(() -> service.completeTask("INC-G1", "T-1", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(details(e)).containsExactly("PHOTO", "READING");
                });
        assertThat(service.getTask("INC-G1", "T-1").status()).isEqualTo("OPEN");

        // 仅确认 PHOTO：422 只缺 READING
        upload("INC-G1", "T-1", "EV-1", "PHOTO", "exec-1");
        service.confirmEvidence("INC-G1", "T-1", "EV-1", "rev-1", new EvidenceActionRequest(key()));
        assertThatThrownBy(() -> service.completeTask("INC-G1", "T-1", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(details(e)).containsExactly("READING");
                });

        // 作废 PHOTO 证据：确认失效，422 重新缺两类
        service.voidEvidence("INC-G1", "T-1", "EV-1", "exec-1", new EvidenceActionRequest(key()));
        assertThatThrownBy(() -> service.completeTask("INC-G1", "T-1", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(details(e)).containsExactly("PHOTO", "READING");
                });

        // 仅上传未确认不计入：READING 未确认仍 422
        upload("INC-G1", "T-1", "EV-2", "READING", "exec-1");
        assertApiStatus(() -> service.completeTask("INC-G1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);

        // 补齐两类已确认证据后完成
        service.confirmEvidence("INC-G1", "T-1", "EV-2", "rev-1", new EvidenceActionRequest(key()));
        upload("INC-G1", "T-1", "EV-3", "PHOTO", "exec-2");
        service.confirmEvidence("INC-G1", "T-1", "EV-3", "rev-2", new EvidenceActionRequest(key()));
        TaskView done = service.completeTask("INC-G1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
    }
    @Test
    void confirm_requiresDifferentReviewer() {
        commanding("INC-C1", "alice");
        openTask("INC-C1", "T-1");
        upload("INC-C1", "T-1", "EV-1", "PHOTO", "exec-1");
        // 验收人与上传人必须不同
        assertApiStatus(() -> service.confirmEvidence("INC-C1", "T-1", "EV-1", "exec-1",
                new EvidenceActionRequest(key())), HttpStatus.CONFLICT);
        EvidenceView confirmed = service.confirmEvidence("INC-C1", "T-1", "EV-1", "rev-1",
                new EvidenceActionRequest(key()));
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        // 已确认证据换 requestId 重复确认：409
        assertApiStatus(() -> service.confirmEvidence("INC-C1", "T-1", "EV-1", "rev-2",
                new EvidenceActionRequest(key())), HttpStatus.CONFLICT);
        // 证据不存在：404
        assertApiStatus(() -> service.confirmEvidence("INC-C1", "T-1", "EV-9", "rev-1",
                new EvidenceActionRequest(key())), HttpStatus.NOT_FOUND);
    }

    @Test
    void void_rules() {
        commanding("INC-V1", "alice");
        openTask("INC-V1", "T-1");
        upload("INC-V1", "T-1", "EV-1", "PHOTO", "exec-1");
        // 既非上传人也非指挥人：409
        assertApiStatus(() -> service.voidEvidence("INC-V1", "T-1", "EV-1", "stranger",
                new EvidenceActionRequest(key())), HttpStatus.CONFLICT);
        // 上传人可作废 PENDING
        EvidenceView voided = service.voidEvidence("INC-V1", "T-1", "EV-1", "exec-1",
                new EvidenceActionRequest(key()));
        assertThat(voided.status()).isEqualTo("VOIDED");
        assertThat(voided.voidedBy()).isEqualTo("exec-1");
        assertThat(voided.voidedAt()).isNotNull();
        // 已作废（异键重复）：409
        assertApiStatus(() -> service.voidEvidence("INC-V1", "T-1", "EV-1", "exec-1",
                new EvidenceActionRequest(key())), HttpStatus.CONFLICT);
        // 作废已确认证据：确认失效；当前指挥人也可作废
        upload("INC-V1", "T-1", "EV-2", "READING", "exec-1");
        service.confirmEvidence("INC-V1", "T-1", "EV-2", "rev-1", new EvidenceActionRequest(key()));
        EvidenceView voidedConfirmed = service.voidEvidence("INC-V1", "T-1", "EV-2", "alice",
                new EvidenceActionRequest(key()));
        assertThat(voidedConfirmed.status()).isEqualTo("VOIDED");
        assertThat(voidedConfirmed.voidedBy()).isEqualTo("alice");
        // 作废证据不能再确认
        assertApiStatus(() -> service.confirmEvidence("INC-V1", "T-1", "EV-2", "rev-2",
                new EvidenceActionRequest(key())), HttpStatus.CONFLICT);
    }

    @Test
    void config_validation() {
        commanding("INC-CV", "alice");
        openTask("INC-CV", "T-1");
        // 类别数量与内容校验
        assertApiStatus(() -> service.configureEvidenceRequirements("INC-CV", "T-1", "alice",
                new EvidenceConfigRequest(key(), 1L, null)), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.configureEvidenceRequirements("INC-CV", "T-1", "alice",
                new EvidenceConfigRequest(key(), 1L, List.of())), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.configureEvidenceRequirements("INC-CV", "T-1", "alice",
                new EvidenceConfigRequest(key(), 1L,
                        List.of("A", "B", "C", "D", "E", "F"))), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.configureEvidenceRequirements("INC-CV", "T-1", "alice",
                new EvidenceConfigRequest(key(), 1L, List.of(" "))), HttpStatus.BAD_REQUEST);
        // expectedTaskVersion 必填且必须匹配当前版本
        assertApiStatus(() -> service.configureEvidenceRequirements("INC-CV", "T-1", "alice",
                new EvidenceConfigRequest(key(), null, List.of("PHOTO"))), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.configureEvidenceRequirements("INC-CV", "T-1", "alice",
                new EvidenceConfigRequest(key(), 9L, List.of("PHOTO"))), HttpStatus.CONFLICT);
        // requestId 必填
        assertApiStatus(() -> service.configureEvidenceRequirements("INC-CV", "T-1", "alice",
                new EvidenceConfigRequest(null, 1L, List.of("PHOTO"))), HttpStatus.BAD_REQUEST);
        // 重复类别去重后生效
        EvidenceRequirementsView config = service.configureEvidenceRequirements("INC-CV", "T-1",
                "alice", new EvidenceConfigRequest(key(), 1L, List.of("PHOTO", "PHOTO", "READING")));
        assertThat(config.categories()).containsExactly("PHOTO", "READING");
    }

    @Test
    void config_versioning() {
        commanding("INC-VG", "alice");
        openTask("INC-VG", "T-1");
        EvidenceRequirementsView v2 = service.configureEvidenceRequirements("INC-VG", "T-1",
                "alice", new EvidenceConfigRequest(key(), 1L, List.of("PHOTO")));
        assertThat(v2.version()).isEqualTo(2);
        EvidenceRequirementsView v3 = service.configureEvidenceRequirements("INC-VG", "T-1",
                "alice", new EvidenceConfigRequest(key(), 2L, List.of("PHOTO", "READING")));
        assertThat(v3.version()).isEqualTo(3);
        assertThat(service.evidenceChain("INC-VG", "T-1").requiredCategories())
                .containsExactly("PHOTO", "READING");
        // 过期版本：409
        assertApiStatus(() -> service.configureEvidenceRequirements("INC-VG", "T-1", "alice",
                new EvidenceConfigRequest(key(), 1L, List.of("PHOTO"))), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.configureEvidenceRequirements("INC-VG", "T-1", "alice",
                new EvidenceConfigRequest(key(), 2L, List.of("PHOTO"))), HttpStatus.CONFLICT);
    }

    @Test
    void config_terminalTaskRejected() {
        commanding("INC-CT", "alice");
        openTask("INC-CT", "T-1");
        openTask("INC-CT", "T-2");
        service.cancelTask("INC-CT", "T-2", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> service.configureEvidenceRequirements("INC-CT", "T-2", "alice",
                new EvidenceConfigRequest(key(), 1L, List.of("PHOTO"))), HttpStatus.CONFLICT);
        service.completeTask("INC-CT", "T-1", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> service.configureEvidenceRequirements("INC-CT", "T-1", "alice",
                new EvidenceConfigRequest(key(), 1L, List.of("PHOTO"))), HttpStatus.CONFLICT);
    }
    @Test
    void config_commanderPermissionAcrossTransfer() {
        commanding("INC-CP", "alice");
        openTask("INC-CP", "T-1");
        // 非指挥人不能配置
        assertApiStatus(() -> service.configureEvidenceRequirements("INC-CP", "T-1", "bob",
                new EvidenceConfigRequest(key(), 1L, List.of("PHOTO"))), HttpStatus.CONFLICT);
        // 待交接期间仍按原指挥权限
        service.initiateTransfer("INC-CP", "alice", new TransferRequest(key(), "bob"));
        EvidenceRequirementsView pending = service.configureEvidenceRequirements("INC-CP", "T-1",
                "alice", new EvidenceConfigRequest(key(), 1L, List.of("PHOTO")));
        assertThat(pending.version()).isEqualTo(2);
        // 交接后旧指挥不能配置，新指挥可以
        service.acceptTransfer("INC-CP", "bob", new TransferAcceptRequest(key()));
        assertApiStatus(() -> service.configureEvidenceRequirements("INC-CP", "T-1", "alice",
                new EvidenceConfigRequest(key(), 2L, List.of("PHOTO"))), HttpStatus.CONFLICT);
        EvidenceRequirementsView byNew = service.configureEvidenceRequirements("INC-CP", "T-1",
                "bob", new EvidenceConfigRequest(key(), 2L, List.of("READING")));
        assertThat(byNew.version()).isEqualTo(3);
    }

    @Test
    void permissionCheck_precedesReplay() {
        commanding("INC-PR", "alice");
        openTask("INC-PR", "T-1");
        String requestId = key();
        EvidenceRequirementsView first = service.configureEvidenceRequirements("INC-PR", "T-1",
                "alice", new EvidenceConfigRequest(requestId, 1L, List.of("PHOTO")));
        assertThat(first.version()).isEqualTo(2);
        // 交接后旧指挥重放同 requestId 同参数：权限先于重放，直接 409 而非返回首次响应
        service.initiateTransfer("INC-PR", "alice", new TransferRequest(key(), "bob"));
        service.acceptTransfer("INC-PR", "bob", new TransferAcceptRequest(key()));
        assertApiStatus(() -> service.configureEvidenceRequirements("INC-PR", "T-1", "alice",
                new EvidenceConfigRequest(requestId, 1L, List.of("PHOTO"))), HttpStatus.CONFLICT);
        // 新指挥用同一 requestId：身份含操作者，异参 409
        assertApiStatus(() -> service.configureEvidenceRequirements("INC-PR", "T-1", "bob",
                new EvidenceConfigRequest(requestId, 1L, List.of("PHOTO"))), HttpStatus.CONFLICT);
    }

    @Test
    void requestId_idempotency() {
        commanding("INC-RI", "alice");
        openTask("INC-RI", "T-1");
        // 配置：同键同参重放首次响应，任务版本不被重复推进
        String configId = key();
        EvidenceRequirementsView first = service.configureEvidenceRequirements("INC-RI", "T-1",
                "alice", new EvidenceConfigRequest(configId, 1L, List.of("PHOTO")));
        EvidenceRequirementsView replay = service.configureEvidenceRequirements("INC-RI", "T-1",
                "alice", new EvidenceConfigRequest(configId, 1L, List.of("PHOTO")));
        assertThat(replay).isEqualTo(first);
        assertThat(service.evidenceChain("INC-RI", "T-1").taskVersion()).isEqualTo(2);
        // 同键异参 409
        assertApiStatus(() -> service.configureEvidenceRequirements("INC-RI", "T-1", "alice",
                new EvidenceConfigRequest(configId, 1L, List.of("READING"))), HttpStatus.CONFLICT);
        // 失败不占键：版本不匹配失败后，同键换正确参数可成功
        String failId = key();
        assertApiStatus(() -> service.configureEvidenceRequirements("INC-RI", "T-1", "alice",
                new EvidenceConfigRequest(failId, 9L, List.of("READING"))), HttpStatus.CONFLICT);
        EvidenceRequirementsView recovered = service.configureEvidenceRequirements("INC-RI", "T-1",
                "alice", new EvidenceConfigRequest(failId, 2L, List.of("READING")));
        assertThat(recovered.version()).isEqualTo(3);

        // 上传：同键同参重放首次响应；同键异参 409
        String uploadId = key();
        EvidenceView uploaded = service.uploadEvidence("INC-RI", "T-1", "exec-1",
                new EvidenceUploadRequest(uploadId, "EV-1", "READING", "读数截图"));
        EvidenceView uploadedReplay = service.uploadEvidence("INC-RI", "T-1", "exec-1",
                new EvidenceUploadRequest(uploadId, "EV-1", "READING", "读数截图"));
        assertThat(uploadedReplay).isEqualTo(uploaded);
        assertApiStatus(() -> service.uploadEvidence("INC-RI", "T-1", "exec-1",
                new EvidenceUploadRequest(uploadId, "EV-1", "READING", "另一说明")),
                HttpStatus.CONFLICT);
        // evidenceNo 自然幂等：异键同号同内容返回首次记录，异键同号不同内容 409
        EvidenceView sameContent = service.uploadEvidence("INC-RI", "T-1", "exec-1",
                new EvidenceUploadRequest(key(), "EV-1", "READING", "读数截图"));
        assertThat(sameContent).isEqualTo(uploaded);
        assertApiStatus(() -> service.uploadEvidence("INC-RI", "T-1", "exec-1",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "读数截图")),
                HttpStatus.CONFLICT);
        assertThat(service.evidenceChain("INC-RI", "T-1").evidence()).hasSize(1);

        // 确认：同键同参重放首次响应
        String confirmId = key();
        EvidenceView confirmed = service.confirmEvidence("INC-RI", "T-1", "EV-1", "rev-1",
                new EvidenceActionRequest(confirmId));
        EvidenceView confirmedReplay = service.confirmEvidence("INC-RI", "T-1", "EV-1", "rev-1",
                new EvidenceActionRequest(confirmId));
        assertThat(confirmedReplay).isEqualTo(confirmed);
        assertThat(service.evidenceChain("INC-RI", "T-1").evidence().get(0).status())
                .isEqualTo("CONFIRMED");
    }

    @Test
    void frozenAfterCompletion() {
        commanding("INC-FZ", "alice");
        openTask("INC-FZ", "T-1");
        service.configureEvidenceRequirements("INC-FZ", "T-1", "alice",
                new EvidenceConfigRequest(key(), 1L, List.of("PHOTO")));
        upload("INC-FZ", "T-1", "EV-1", "PHOTO", "exec-1");
        service.confirmEvidence("INC-FZ", "T-1", "EV-1", "rev-1", new EvidenceActionRequest(key()));
        upload("INC-FZ", "T-1", "EV-2", "READING", "exec-1");
        TaskView done = service.completeTask("INC-FZ", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        // 完成冻结当时证据内容和确认人
        EvidenceChainView chain = service.evidenceChain("INC-FZ", "T-1");
        assertThat(chain.evidence()).allSatisfy(e -> assertThat(e.frozenAt()).isNotNull());
        assertThat(chain.evidence().get(0).confirmedBy()).isEqualTo("rev-1");
        // 完成后禁止作废、覆盖（上传）、确认与类别变更
        assertApiStatus(() -> service.voidEvidence("INC-FZ", "T-1", "EV-1", "exec-1",
                new EvidenceActionRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.uploadEvidence("INC-FZ", "T-1", "exec-1",
                new EvidenceUploadRequest(key(), "EV-3", "PHOTO", "新发现")), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.confirmEvidence("INC-FZ", "T-1", "EV-2", "rev-1",
                new EvidenceActionRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.configureEvidenceRequirements("INC-FZ", "T-1", "alice",
                new EvidenceConfigRequest(key(), 2L, List.of("READING"))), HttpStatus.CONFLICT);
        // 冻结内容未被倒改
        EvidenceChainView after = service.evidenceChain("INC-FZ", "T-1");
        assertThat(after.evidence().get(0).status()).isEqualTo("CONFIRMED");
        assertThat(after.evidence().get(1).status()).isEqualTo("PENDING");
    }

    @Test
    void syntheticChain_photoReading_downstreamUnblocked() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        openTask("INC-A", "T-1");
        service.createTask("INC-B", "bob",
                new TaskCreateRequest(key(), "T-2", "G", "下游任务", List.of("INC-A")));
        // 合成任务需 PHOTO 与 READING
        service.configureEvidenceRequirements("INC-A", "T-1", "alice",
                new EvidenceConfigRequest(key(), 1L, List.of("PHOTO", "READING")));
        upload("INC-A", "T-1", "EV-P", "PHOTO", "exec-1");
        service.confirmEvidence("INC-A", "T-1", "EV-P", "rev-1", new EvidenceActionRequest(key()));
        // 仅确认 PHOTO 不能完成
        assertApiStatus(() -> service.completeTask("INC-A", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(service.getTask("INC-A", "T-1").status()).isEqualTo("OPEN");
        // 下游任务仍被阻塞
        assertThatThrownBy(() -> service.completeTask("INC-B", "T-2", "bob",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(details(e)).containsExactly("INC-A");
                });
        // 补齐 READING 后完成
        upload("INC-A", "T-1", "EV-R", "READING", "exec-2");
        service.confirmEvidence("INC-A", "T-1", "EV-R", "rev-2", new EvidenceActionRequest(key()));
        TaskView done = service.completeTask("INC-A", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        // 事件推进到 CONTAINED 后，下游任务解除该依赖
        service.changeStatus("INC-A", "alice", new StatusRequest(key(), "CONTAINED"));
        assertThat(service.getTask("INC-B", "T-2").blockers().get(0).resolved()).isTrue();
        TaskView downstream = service.completeTask("INC-B", "T-2", "bob",
                new TaskActionRequest(key()));
        assertThat(downstream.status()).isEqualTo("DONE");
        // 全部任务终态后事件可解决
        service.changeStatus("INC-A", "alice", new StatusRequest(key(), "RESOLVED"));
        assertThat(service.get("INC-A").status()).isEqualTo("RESOLVED");
    }
}