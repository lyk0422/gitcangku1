package com.example.starter.blind.web;

import com.example.starter.blind.Actor;
import com.example.starter.blind.ActorContext;
import com.example.starter.blind.ApiException;
import com.example.starter.blind.RequestTokens;
import com.example.starter.blind.dto.AllocationView;
import com.example.starter.blind.dto.CenterSequenceSummaryView;
import com.example.starter.blind.dto.CenterView;
import com.example.starter.blind.dto.CreateAmendmentRequest;
import com.example.starter.blind.dto.CreateCenterRequest;
import com.example.starter.blind.dto.CreateExperimentRequest;
import com.example.starter.blind.dto.ExperimentView;
import com.example.starter.blind.dto.ProtocolVersionView;
import com.example.starter.blind.dto.UnblindApplyRequest;
import com.example.starter.blind.dto.UnblindRequestView;
import com.example.starter.blind.dto.UnblindResultView;
import com.example.starter.blind.service.ExperimentService;
import com.example.starter.blind.service.IdempotencyService;
import com.example.starter.blind.service.ProtocolAmendmentService;
import com.example.starter.blind.service.SequenceBlockGenerator;
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
    static final String OP_CENTER_CREATE = "center.create";
    static final String OP_CENTER_SUSPEND = "center.suspend";
    static final String OP_CENTER_RESUME = "center.resume";
    static final String OP_CENTER_ALLOCATION_CREATE = "center.allocation.create";
    static final String OP_AMENDMENT_CREATE = "protocol.amendment.create";
    static final String OP_AMENDMENT_REVOKE = "protocol.amendment.revoke";

    private final ExperimentService experimentService;
    private final UnblindService unblindService;
    private final ProtocolAmendmentService protocolAmendmentService;
    private final IdempotencyService idempotencyService;
    private final ActorContext actorContext;

    public BlindExperimentController(ExperimentService experimentService,
                                     UnblindService unblindService,
                                     ProtocolAmendmentService protocolAmendmentService,
                                     IdempotencyService idempotencyService,
                                     ActorContext actorContext) {
        this.experimentService = experimentService;
        this.unblindService = unblindService;
        this.protocolAmendmentService = protocolAmendmentService;
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

    // ---------------- 中心 ----------------

    /** 激活（创建）研究中心并按当前有效协议预留覆盖目标上限的独立盲码序列（仅 COORDINATOR）。 */
    @PostMapping("/experiments/{experimentId}/centers/{centerId}")
    public ResponseEntity<String> createCenter(
            @PathVariable String experimentId,
            @PathVariable String centerId,
            @Valid @RequestBody CreateCenterRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String ctrId = RequestTokens.requireId("centerId", centerId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_CENTER_CREATE,
                Map.of("experimentId", expId, "centerId", ctrId, "targetCap", request.targetCap()));
        return idempotencyService.runWrite(reqId, OP_CENTER_CREATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        protocolAmendmentService.createCenter(
                                expId, ctrId, request.targetCap())));
    }

    /** 查询单个中心（两种角色均可）：状态、目标上限、累计分配与剩余容量。 */
    @GetMapping("/experiments/{experimentId}/centers/{centerId}")
    public CenterView getCenter(@PathVariable String experimentId,
                                @PathVariable String centerId) {
        requireActor();
        return protocolAmendmentService.getCenter(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("centerId", centerId));
    }

    /** 查询实验下全部中心。 */
    @GetMapping("/experiments/{experimentId}/centers")
    public List<CenterView> listCenters(@PathVariable String experimentId) {
        requireActor();
        return protocolAmendmentService.listCenters(
                RequestTokens.requireId("experimentId", experimentId));
    }

    /** 暂停中心（仅 COORDINATOR）：暂停期间拒绝登记且不参与修订生效预留。 */
    @PostMapping("/experiments/{experimentId}/centers/{centerId}/suspension")
    public ResponseEntity<String> suspendCenter(
            @PathVariable String experimentId,
            @PathVariable String centerId,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String ctrId = RequestTokens.requireId("centerId", centerId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_CENTER_SUSPEND,
                Map.of("experimentId", expId, "centerId", ctrId));
        return idempotencyService.runWrite(reqId, OP_CENTER_SUSPEND, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        protocolAmendmentService.suspendCenter(expId, ctrId)));
    }

    /** 恢复中心（仅 COORDINATOR）：恢复后使用当时有效版本并补足剩余容量序列。 */
    @PostMapping("/experiments/{experimentId}/centers/{centerId}/resumption")
    public ResponseEntity<String> resumeCenter(
            @PathVariable String experimentId,
            @PathVariable String centerId,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String ctrId = RequestTokens.requireId("centerId", centerId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_CENTER_RESUME,
                Map.of("experimentId", expId, "centerId", ctrId));
        return idempotencyService.runWrite(reqId, OP_CENTER_RESUME, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        protocolAmendmentService.resumeCenter(expId, ctrId)));
    }

    /** 中心登记（仅 COORDINATOR）：领取当前有效版本下该中心的下一条独立盲码。 */
    @PostMapping("/experiments/{experimentId}/centers/{centerId}/participants/{participantId}/allocations")
    public ResponseEntity<String> registerAtCenter(
            @PathVariable String experimentId,
            @PathVariable String centerId,
            @PathVariable String participantId,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String ctrId = RequestTokens.requireId("centerId", centerId);
        String pid = RequestTokens.requireId("participantId", participantId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_CENTER_ALLOCATION_CREATE,
                Map.of("experimentId", expId, "centerId", ctrId, "participantId", pid));
        return idempotencyService.runWrite(reqId, OP_CENTER_ALLOCATION_CREATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        protocolAmendmentService.registerAtCenter(
                                expId, ctrId, pid, actor.actorId())));
    }

    // ---------------- 协议修订 ----------------

    /** 创建盲法协议修订（仅 COORDINATOR）：比例正整数和为 100，生效时刻不早于当前。 */
    @PostMapping("/experiments/{experimentId}/protocol-versions")
    public ResponseEntity<String> createAmendment(
            @PathVariable String experimentId,
            @Valid @RequestBody CreateAmendmentRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String reqId = RequestTokens.requireRequestId(requestId);
        // protocolKey：试验、规范化比例、生效时刻与操作者共同构成幂等指纹。
        int[] normalized = SequenceBlockGenerator.normalizedRatio(request.ratioA(), request.ratioB());
        String fingerprint = idempotencyService.fingerprint(OP_AMENDMENT_CREATE,
                Map.of("experimentId", expId,
                        "ratioA", normalized[0],
                        "ratioB", normalized[1],
                        "effectiveAt", request.effectiveAt(),
                        "actorId", actor.actorId()));
        return idempotencyService.runWrite(reqId, OP_AMENDMENT_CREATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        protocolAmendmentService.createAmendment(expId, request.ratioA(),
                                request.ratioB(), request.effectiveAt(), actor.actorId())));
    }

    /** 查询实验全部协议版本（两种角色均可）。 */
    @GetMapping("/experiments/{experimentId}/protocol-versions")
    public List<ProtocolVersionView> listVersions(@PathVariable String experimentId) {
        requireActor();
        return protocolAmendmentService.listVersions(
                RequestTokens.requireId("experimentId", experimentId));
    }

    /** 查询中心盲码序列计数（不含盲码与处理映射）。 */
    @GetMapping("/experiments/{experimentId}/center-sequences")
    public List<CenterSequenceSummaryView> listSequences(@PathVariable String experimentId) {
        requireActor();
        return protocolAmendmentService.listSequences(
                RequestTokens.requireId("experimentId", experimentId));
    }

    /** 撤销未生效协议修订（仅 COORDINATOR）；已生效不可撤销，记录保留。 */
    @PostMapping("/experiments/{experimentId}/protocol-versions/{versionNo}/revocation")
    public ResponseEntity<String> revokeAmendment(
            @PathVariable String experimentId,
            @PathVariable int versionNo,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_AMENDMENT_REVOKE,
                Map.of("experimentId", expId, "versionNo", versionNo));
        return idempotencyService.runWrite(reqId, OP_AMENDMENT_REVOKE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        protocolAmendmentService.revokeAmendment(expId, versionNo)));
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
}
