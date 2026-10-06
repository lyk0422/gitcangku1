package com.example.starter.blind.web;

import com.example.starter.blind.Actor;
import com.example.starter.blind.ActorContext;
import com.example.starter.blind.ApiException;
import com.example.starter.blind.RequestTokens;
import com.example.starter.blind.dto.AllocationView;
import com.example.starter.blind.dto.CreateExperimentRequest;
import com.example.starter.blind.dto.ExperimentView;
import com.example.starter.blind.dto.PhaseOperationRequest;
import com.example.starter.blind.dto.PhaseView;
import com.example.starter.blind.dto.UnblindApplyRequest;
import com.example.starter.blind.dto.UnblindRequestView;
import com.example.starter.blind.dto.UnblindResultView;
import com.example.starter.blind.service.ExperimentService;
import com.example.starter.blind.service.IdempotencyService;
import com.example.starter.blind.service.PhaseService;
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

import java.util.HashMap;
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
    static final String OP_PHASE_FIRST_START = "phase.first.start";
    static final String OP_PHASE_FIRST_END = "phase.first.end";
    static final String OP_PHASE_SECOND_START = "phase.second.start";
    static final String OP_PHASE_SECOND_END = "phase.second.end";

    private final ExperimentService experimentService;
    private final UnblindService unblindService;
    private final PhaseService phaseService;
    private final IdempotencyService idempotencyService;
    private final ActorContext actorContext;

    public BlindExperimentController(ExperimentService experimentService,
                                     UnblindService unblindService,
                                     PhaseService phaseService,
                                     IdempotencyService idempotencyService,
                                     ActorContext actorContext) {
        this.experimentService = experimentService;
        this.unblindService = unblindService;
        this.phaseService = phaseService;
        this.idempotencyService = idempotencyService;
        this.actorContext = actorContext;
    }

    // ---------------- 实验 ----------------

    /** 创建实验（仅 COORDINATOR）；区组数量 2~8，创建后不可改；交叉模式须给定 1~120 分钟洗脱时长。 */
    @PostMapping("/experiments/{experimentId}")
    public ResponseEntity<String> createExperiment(
            @PathVariable String experimentId,
            @Valid @RequestBody CreateExperimentRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String reqId = RequestTokens.requireRequestId(requestId);
        Map<String, Object> params = new HashMap<>();
        params.put("experimentId", expId);
        params.put("blockCount", request.blockCount());
        params.put("mode", request.mode());
        params.put("washoutMinutes", request.washoutMinutes());
        String fingerprint = idempotencyService.fingerprint(OP_EXPERIMENT_CREATE, params);
        return idempotencyService.runWrite(reqId, OP_EXPERIMENT_CREATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        experimentService.createExperiment(expId, request.blockCount(),
                                request.mode(), request.washoutMinutes())));
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

    // ---------------- 交叉实验阶段 ----------------

    /** 第一阶段开始（仅 COORDINATOR）；响应只含盲码与阶段时刻，不含处理代码。 */
    @PostMapping("/experiments/{experimentId}/participants/{participantId}/phases/first/start")
    public ResponseEntity<String> startFirstPhase(
            @PathVariable String experimentId,
            @PathVariable String participantId,
            @RequestBody(required = false) PhaseOperationRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        return runPhaseWrite(OP_PHASE_FIRST_START, experimentId, participantId, request, requestId);
    }

    /** 第一阶段结束并进入洗脱（仅 COORDINATOR）。 */
    @PostMapping("/experiments/{experimentId}/participants/{participantId}/phases/first/end")
    public ResponseEntity<String> endFirstPhase(
            @PathVariable String experimentId,
            @PathVariable String participantId,
            @RequestBody(required = false) PhaseOperationRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        return runPhaseWrite(OP_PHASE_FIRST_END, experimentId, participantId, request, requestId);
    }

    /** 第二阶段开始（仅 COORDINATOR）；洗脱不足 422。 */
    @PostMapping("/experiments/{experimentId}/participants/{participantId}/phases/second/start")
    public ResponseEntity<String> startSecondPhase(
            @PathVariable String experimentId,
            @PathVariable String participantId,
            @RequestBody(required = false) PhaseOperationRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        return runPhaseWrite(OP_PHASE_SECOND_START, experimentId, participantId, request, requestId);
    }

    /** 第二阶段结束并完成（仅 COORDINATOR）。 */
    @PostMapping("/experiments/{experimentId}/participants/{participantId}/phases/second/end")
    public ResponseEntity<String> endSecondPhase(
            @PathVariable String experimentId,
            @PathVariable String participantId,
            @RequestBody(required = false) PhaseOperationRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        return runPhaseWrite(OP_PHASE_SECOND_END, experimentId, participantId, request, requestId);
    }

    /** 阶段状态与历史查询（两种角色均可）：只含盲码、状态与时刻，不含处理代码。 */
    @GetMapping("/experiments/{experimentId}/participants/{participantId}/phases")
    public PhaseView getPhases(@PathVariable String experimentId,
                               @PathVariable String participantId) {
        requireActor();
        return phaseService.getPhases(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("participantId", participantId));
    }

    /**
     * 阶段写操作公共编排：权限校验先于幂等回放；幂等身份含操作、操作者、资源与
     * 全部参数（含期望版本）；同键同参重放首次完整响应（最初阶段与时刻），异参 409。
     */
    private ResponseEntity<String> runPhaseWrite(String operation, String experimentId,
                                                 String participantId,
                                                 PhaseOperationRequest request, String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String pid = RequestTokens.requireId("participantId", participantId);
        String reqId = RequestTokens.requireRequestId(requestId);
        Long expectedVersion = request == null ? null : request.expectedVersion();
        Map<String, Object> params = new HashMap<>();
        params.put("experimentId", expId);
        params.put("participantId", pid);
        params.put("expectedVersion", expectedVersion);
        String fingerprint = idempotencyService.fingerprint(operation, params);
        return idempotencyService.runWrite(reqId, operation, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        switch (operation) {
                            case OP_PHASE_FIRST_START ->
                                    phaseService.startFirst(expId, pid, expectedVersion);
                            case OP_PHASE_FIRST_END ->
                                    phaseService.endFirst(expId, pid, expectedVersion);
                            case OP_PHASE_SECOND_START ->
                                    phaseService.startSecond(expId, pid, expectedVersion);
                            case OP_PHASE_SECOND_END ->
                                    phaseService.endSecond(expId, pid, expectedVersion);
                            default -> throw new IllegalStateException("未知阶段操作: " + operation);
                        }));
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
