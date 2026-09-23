package com.example.starter.blind.web;

import com.example.starter.blind.Actor;
import com.example.starter.blind.ActorContext;
import com.example.starter.blind.ApiException;
import com.example.starter.blind.RequestTokens;
import com.example.starter.blind.dto.AllocationView;
import com.example.starter.blind.dto.ContaminationVersionView;
import com.example.starter.blind.dto.ContaminationView;
import com.example.starter.blind.dto.CreateExperimentRequest;
import com.example.starter.blind.dto.DownstreamExposureRequest;
import com.example.starter.blind.dto.ExperimentView;
import com.example.starter.blind.dto.ExposureRecordRequest;
import com.example.starter.blind.dto.ExposureRecordView;
import com.example.starter.blind.dto.QuarantineOrderView;
import com.example.starter.blind.dto.UnblindApplyRequest;
import com.example.starter.blind.dto.UnblindRequestView;
import com.example.starter.blind.dto.UnblindResultView;
import com.example.starter.blind.service.ContaminationService;
import com.example.starter.blind.service.ExperimentService;
import com.example.starter.blind.service.IdempotencyService;
import com.example.starter.blind.service.UnblindService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 实验盲法分配与受控揭盲接口。
 * 所有写操作必须携带 X-Request-Id；权限校验先于幂等回放。
 */
@RestController
@RequestMapping("/api")
public class BlindExperimentController {

    static final String OP_EXPERIMENT_CREATE = "experiment.create";
    static final String OP_EXPERIMENT_CLOSE = "experiment.close";
    static final String OP_ALLOCATION_CREATE = "allocation.create";
    static final String OP_ALLOCATION_WITHDRAW = "allocation.withdraw";
    static final String OP_UNBLIND_APPLY = "unblind.apply";
    static final String OP_UNBLIND_APPROVE = "unblind.approve";
    static final String OP_EXPOSURE_RECORD = "exposure.record";
    static final String OP_EXPOSURE_RECORD_DOWNSTREAM = "exposure.record.downstream";
    static final String OP_QUARANTINE_INITIATE = "quarantine.initiate";
    static final String OP_QUARANTINE_CONFIRM = "quarantine.confirm";

    private final ExperimentService experimentService;
    private final UnblindService unblindService;
    private final ContaminationService contaminationService;
    private final IdempotencyService idempotencyService;
    private final ActorContext actorContext;

    public BlindExperimentController(ExperimentService experimentService,
                                     UnblindService unblindService,
                                     ContaminationService contaminationService,
                                     IdempotencyService idempotencyService,
                                     ActorContext actorContext) {
        this.experimentService = experimentService;
        this.unblindService = unblindService;
        this.contaminationService = contaminationService;
        this.idempotencyService = idempotencyService;
        this.actorContext = actorContext;
    }

    // ---------------- 实验 ----------------

    /** 创建实验（仅 COORDINATOR）；区组数量 2~8，创建后不可改。 */
    @PostMapping("/experiments/{experimentId}")
    public ResponseEntity<String> createExperiment(
            @PathVariable String experimentId,
            @Valid @RequestBody CreateExperimentRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_EXPERIMENT_CREATE,
                Map.of("experimentId", expId, "blockCount", request.blockCount()));
        return idempotencyService.runWrite(reqId, OP_EXPERIMENT_CREATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        experimentService.createExperiment(expId, request.blockCount())));
    }

    /** 查询实验信息（COORDINATOR / REVIEWER 均可）。 */
    @GetMapping("/experiments/{experimentId}")
    public ExperimentView getExperiment(@PathVariable String experimentId) {
        requireActor();
        return experimentService.getExperiment(RequestTokens.requireId("experimentId", experimentId));
    }

    /** 关闭实验（仅 COORDINATOR）；关闭后拒绝新增分配。 */
    @PostMapping("/experiments/{experimentId}/close")
    public ResponseEntity<String> closeExperiment(
            @PathVariable String experimentId,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_EXPERIMENT_CLOSE,
                Map.of("experimentId", expId));
        return idempotencyService.runWrite(reqId, OP_EXPERIMENT_CLOSE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        experimentService.close(expId)));
    }

    // ---------------- 分配 / 退组 / 查询 ----------------

    /** 登记参与者（仅 COORDINATOR）：原子领取第一个空位。 */
    @PostMapping("/experiments/{experimentId}/participants/{participantId}/allocations")
    public ResponseEntity<String> register(
            @PathVariable String experimentId,
            @PathVariable String participantId,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String pid = RequestTokens.requireId("participantId", participantId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_ALLOCATION_CREATE,
                Map.of("experimentId", expId, "participantId", pid));
        return idempotencyService.runWrite(reqId, OP_ALLOCATION_CREATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        experimentService.register(expId, pid, actor.actorId())));
    }

    /** 退组（仅 COORDINATOR）：不释放席位、不重排。 */
    @PostMapping("/experiments/{experimentId}/participants/{participantId}/withdrawal")
    public ResponseEntity<String> withdraw(
            @PathVariable String experimentId,
            @PathVariable String participantId,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String pid = RequestTokens.requireId("participantId", participantId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_ALLOCATION_WITHDRAW,
                Map.of("experimentId", expId, "participantId", pid));
        return idempotencyService.runWrite(reqId, OP_ALLOCATION_WITHDRAW, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        experimentService.withdraw(expId, pid)));
    }

    /** 普通查询（两种角色均可）：只返回盲码、区组号、参与者编号与退组状态。 */
    @GetMapping("/experiments/{experimentId}/participants/{participantId}")
    public AllocationView getAllocation(@PathVariable String experimentId,
                                        @PathVariable String participantId) {
        requireActor();
        return experimentService.getAllocation(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("participantId", participantId));
    }

    // ---------------- 揭盲 ----------------

    /** 协调员为已分配参与者提出带原因的揭盲申请。 */
    @PostMapping("/experiments/{experimentId}/participants/{participantId}/unblind-requests")
    public ResponseEntity<String> applyUnblind(
            @PathVariable String experimentId,
            @PathVariable String participantId,
            @Valid @RequestBody UnblindApplyRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String pid = RequestTokens.requireId("participantId", participantId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_UNBLIND_APPLY,
                Map.of("experimentId", expId,
                        "participantId", pid,
                        "reason", request.reason()));
        return idempotencyService.runWrite(reqId, OP_UNBLIND_APPLY, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        unblindService.apply(expId, pid, request.reason(), actor.actorId())));
    }

    /** 另一名 REVIEWER 批准揭盲申请。 */
    @PostMapping("/unblind-requests/{unblindRequestId}/approval")
    public ResponseEntity<String> approveUnblind(
            @PathVariable String unblindRequestId,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireReviewer();
        String ubId = RequestTokens.requireId("unblindRequestId", unblindRequestId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_UNBLIND_APPROVE,
                Map.of("unblindRequestId", ubId));
        return idempotencyService.runWrite(reqId, OP_UNBLIND_APPROVE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        unblindService.approve(ubId, actor.actorId())));
    }

    /** 查询揭盲申请状态（不含处理代码）；申请人或批准人可查。 */
    @GetMapping("/unblind-requests/{unblindRequestId}")
    public UnblindRequestView getUnblindRequest(@PathVariable String unblindRequestId) {
        Actor actor = requireActor();
        return unblindService.getRequest(
                RequestTokens.requireId("unblindRequestId", unblindRequestId), actor.actorId());
    }

    /** 查询揭盲结果：仅申请人本人、已批准后可获得处理代码；其他人 403，未批准 409。 */
    @GetMapping("/unblind-requests/{unblindRequestId}/result")
    public UnblindResultView getUnblindResult(@PathVariable String unblindRequestId) {
        Actor actor = requireActor();
        return unblindService.getResult(
                RequestTokens.requireId("unblindRequestId", unblindRequestId), actor.actorId());
    }

    // ---------------- 泄露传播登记 ----------------

    /**
     * 持 exposureKey 登记申请人本人向 1～20 名操作者的直接披露；
     * 披露源强制为当前操作者，接收人集合顺序不影响幂等判定。
     */
    @PostMapping("/exposures")
    public ResponseEntity<String> recordExposure(
            @Valid @RequestBody ExposureRecordRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireActor();
        String exposureKey = request.exposureKey().trim();
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_EXPOSURE_RECORD,
                Map.of("exposureKey", exposureKey,
                        "recipients", normalizedRecipients(request.recipients())));
        return idempotencyService.runWrite(reqId, OP_EXPOSURE_RECORD, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        contaminationService.record(exposureKey, request.recipients(),
                                actor.actorId())));
    }

    /**
     * 接收人继续登记自己向更下游操作者的直接披露；资格由其是否已在闭包内强制判定。
     */
    @PostMapping("/experiments/{experimentId}/participants/{participantId}/exposures")
    public ResponseEntity<String> recordDownstreamExposure(
            @PathVariable String experimentId,
            @PathVariable String participantId,
            @Valid @RequestBody DownstreamExposureRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireActor();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String pid = RequestTokens.requireId("participantId", participantId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_EXPOSURE_RECORD_DOWNSTREAM,
                Map.of("experimentId", expId,
                        "participantId", pid,
                        "recipients", normalizedRecipients(request.recipients())));
        return idempotencyService.runWrite(reqId, OP_EXPOSURE_RECORD_DOWNSTREAM, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        contaminationService.recordDownstream(expId, pid, request.recipients(),
                                actor.actorId())));
    }

    /** 查询当前开放污染闭包与版本（不含处理代码）。 */
    @GetMapping("/experiments/{experimentId}/participants/{participantId}/contamination")
    public ContaminationView getContamination(@PathVariable String experimentId,
                                              @PathVariable String participantId) {
        requireActor();
        return contaminationService.getContamination(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("participantId", participantId));
    }

    /** 查询某参与者全部闭包版本（含冻结审计快照，不含处理代码）。 */
    @GetMapping("/experiments/{experimentId}/participants/{participantId}/contamination/versions")
    public List<ContaminationVersionView> getContaminationVersions(
            @PathVariable String experimentId,
            @PathVariable String participantId) {
        requireActor();
        return contaminationService.listVersions(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("participantId", participantId));
    }

    // ---------------- 隔离单 ----------------

    /** 合规负责人发起隔离：提交当前完整闭包与版本。 */
    @PostMapping("/experiments/{experimentId}/participants/{participantId}/quarantine-orders")
    public ResponseEntity<String> initiateQuarantine(
            @PathVariable String experimentId,
            @PathVariable String participantId,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCompliance();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String pid = RequestTokens.requireId("participantId", participantId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_QUARANTINE_INITIATE,
                Map.of("experimentId", expId, "participantId", pid));
        return idempotencyService.runWrite(reqId, OP_QUARANTINE_INITIATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        contaminationService.initiateQuarantine(expId, pid, actor.actorId())));
    }

    /** 另一名不在闭包内的合规负责人确认隔离并冻结版本快照。 */
    @PostMapping("/quarantine-orders/{orderId}/confirmation")
    public ResponseEntity<String> confirmQuarantine(
            @PathVariable String orderId,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCompliance();
        String qoId = RequestTokens.requireId("orderId", orderId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_QUARANTINE_CONFIRM,
                Map.of("orderId", qoId));
        return idempotencyService.runWrite(reqId, OP_QUARANTINE_CONFIRM, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        contaminationService.confirmQuarantine(qoId, actor.actorId())));
    }

    /** 查询某参与者隔离历史（不含处理代码）。 */
    @GetMapping("/experiments/{experimentId}/participants/{participantId}/quarantine-orders")
    public List<QuarantineOrderView> getQuarantineOrders(@PathVariable String experimentId,
                                                         @PathVariable String participantId) {
        requireActor();
        return contaminationService.listQuarantineOrders(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("participantId", participantId));
    }

    // ---------------- 权限辅助（先于幂等回放执行） ----------------

    private Actor requireActor() {
        Actor actor = actorContext.get();
        if (actor == null) {
            throw ApiException.unauthorized("缺少操作者身份头");
        }
        return actor;
    }

    private Actor requireCoordinator() {
        Actor actor = requireActor();
        if (!actor.isCoordinator()) {
            throw ApiException.forbidden("仅 COORDINATOR 可执行该操作");
        }
        return actor;
    }

    private Actor requireReviewer() {
        Actor actor = requireActor();
        if (!actor.isReviewer()) {
            throw ApiException.forbidden("仅 REVIEWER 可执行该操作");
        }
        return actor;
    }

    private Actor requireCompliance() {
        Actor actor = requireActor();
        if (!actor.isCompliance()) {
            throw ApiException.forbidden("仅 COMPLIANCE 合规负责人可执行该操作");
        }
        return actor;
    }

    /**
     * 幂等指纹中的接收人集合规范化：去空白、去重并升序，
     * 使「同参集合换序重放」视为同参，真正增减成员才判异参 409。
     */
    private List<String> normalizedRecipients(List<String> recipients) {
        return recipients.stream()
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }
}
