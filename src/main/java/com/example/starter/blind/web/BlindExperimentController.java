package com.example.starter.blind.web;

import com.example.starter.blind.Actor;
import com.example.starter.blind.ActorContext;
import com.example.starter.blind.ApiException;
import com.example.starter.blind.RequestTokens;
import com.example.starter.blind.dto.AllocationDetailView;
import com.example.starter.blind.dto.AllocationView;
import com.example.starter.blind.dto.CreateExperimentRequest;
import com.example.starter.blind.dto.ExperimentDiagnosticsView;
import com.example.starter.blind.dto.ExperimentView;
import com.example.starter.blind.dto.ExpandTableRequest;
import com.example.starter.blind.dto.SealTableRequest;
import com.example.starter.blind.dto.TableHistoryEventView;
import com.example.starter.blind.dto.TableSealView;
import com.example.starter.blind.dto.TableVersionView;
import com.example.starter.blind.dto.UnblindApplyRequest;
import com.example.starter.blind.dto.UnblindRequestView;
import com.example.starter.blind.dto.UnblindResultView;
import com.example.starter.blind.service.ExperimentService;
import com.example.starter.blind.service.IdempotencyService;
import com.example.starter.blind.service.TableSealService;
import com.example.starter.blind.service.UnblindService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    static final String OP_TABLE_SEAL = "table.seal";
    static final String OP_TABLE_EXPAND = "table.expand";
    static final String OP_UNBLIND_APPLY = "unblind.apply";
    static final String OP_UNBLIND_APPROVE = "unblind.approve";

    private final ExperimentService experimentService;
    private final TableSealService tableSealService;
    private final UnblindService unblindService;
    private final IdempotencyService idempotencyService;
    private final ActorContext actorContext;

    public BlindExperimentController(ExperimentService experimentService,
                                     TableSealService tableSealService,
                                     UnblindService unblindService,
                                     IdempotencyService idempotencyService,
                                     ActorContext actorContext) {
        this.experimentService = experimentService;
        this.tableSealService = tableSealService;
        this.unblindService = unblindService;
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

    // ---------------- 随机表封存 / 扩容 / 查询 ----------------

    /** 封存区组随机表（仅 COORDINATOR）：首次分配前提交 sealKey 与表摘要。 */
    @PutMapping("/experiments/{experimentId}/blocks/{blockNo}/seals/{sealKey}")
    public ResponseEntity<String> sealTable(
            @PathVariable String experimentId,
            @PathVariable int blockNo,
            @PathVariable String sealKey,
            @Valid @RequestBody SealTableRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String key = RequestTokens.requireId("sealKey", sealKey);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_TABLE_SEAL,
                Map.of("experimentId", expId,
                        "blockNo", blockNo,
                        "sealKey", key,
                        "sequenceDigest", request.sequenceDigest()));
        return idempotencyService.runWrite(reqId, OP_TABLE_SEAL, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        tableSealService.seal(expId, blockNo, key, request.sequenceDigest())));
    }

    /** 区组扩容（仅 COORDINATOR）：显式引用已封存版本新建后继版本。 */
    @PostMapping("/experiments/{experimentId}/blocks/{blockNo}/table-versions")
    public ResponseEntity<String> expandTable(
            @PathVariable String experimentId,
            @PathVariable int blockNo,
            @Valid @RequestBody ExpandTableRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_TABLE_EXPAND,
                Map.of("experimentId", expId,
                        "blockNo", blockNo,
                        "sealKey", request.sealKey(),
                        "expectedRemaining", request.expectedRemaining(),
                        "newCapacity", request.newCapacity()));
        return idempotencyService.runWrite(reqId, OP_TABLE_EXPAND, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        tableSealService.expand(expId, blockNo, request.sealKey(),
                                request.expectedRemaining(), request.newCapacity())));
    }

    /** 区组版本链明细（只读，两种角色均可）。 */
    @GetMapping("/experiments/{experimentId}/blocks/{blockNo}/table-versions")
    public List<TableVersionView> getBlockTableVersions(@PathVariable String experimentId,
                                                        @PathVariable int blockNo) {
        requireActor();
        return tableSealService.getBlockVersions(
                RequestTokens.requireId("experimentId", experimentId), blockNo);
    }

    /** 区组随机表历史（只读，两种角色均可），按事件时刻升序。 */
    @GetMapping("/experiments/{experimentId}/blocks/{blockNo}/table-history")
    public List<TableHistoryEventView> getBlockTableHistory(@PathVariable String experimentId,
                                                            @PathVariable int blockNo) {
        requireActor();
        return tableSealService.getBlockHistory(
                RequestTokens.requireId("experimentId", experimentId), blockNo);
    }

    /** 实验随机表诊断（只读，两种角色均可），含容量、已分配与未分配名额实际值。 */
    @GetMapping("/experiments/{experimentId}/table-diagnostics")
    public ExperimentDiagnosticsView getTableDiagnostics(@PathVariable String experimentId) {
        requireActor();
        return tableSealService.getDiagnostics(
                RequestTokens.requireId("experimentId", experimentId));
    }

    /** 实验全部分配明细（只读，两种角色均可），含固化版本号，不含序号与处理代码。 */
    @GetMapping("/experiments/{experimentId}/allocations")
    public List<AllocationDetailView> listAllocations(@PathVariable String experimentId) {
        requireActor();
        return experimentService.listAllocationDetails(
                RequestTokens.requireId("experimentId", experimentId));
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
