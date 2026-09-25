package com.example.starter.blind.web;

import com.example.starter.blind.Actor;
import com.example.starter.blind.ActorContext;
import com.example.starter.blind.ApiException;
import com.example.starter.blind.RequestTokens;
import com.example.starter.blind.dto.AllocationView;
import com.example.starter.blind.dto.CenterSequenceView;
import com.example.starter.blind.dto.CenterView;
import com.example.starter.blind.dto.CreateAmendmentRequest;
import com.example.starter.blind.dto.CreateCenterRequest;
import com.example.starter.blind.dto.ProtocolVersionView;
import com.example.starter.blind.service.CenterService;
import com.example.starter.blind.service.ExperimentService;
import com.example.starter.blind.service.IdempotencyService;
import com.example.starter.blind.service.ProtocolAmendmentService;
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
 * 中心激活/暂停/恢复、协议修订创建/生效/撤销/查询与中心登记接口。
 * 所有写操作必须携带 X-Request-Id；权限校验先于幂等回放。
 */
@RestController
@RequestMapping("/api")
public class ProtocolCenterController {

    static final String OP_CENTER_ACTIVATE = "center.activate";
    static final String OP_CENTER_SUSPEND = "center.suspend";
    static final String OP_CENTER_RESUME = "center.resume";
    static final String OP_CENTER_ALLOCATION_CREATE = "center.allocation.create";
    static final String OP_AMENDMENT_CREATE = "amendment.create";
    static final String OP_AMENDMENT_EFFECT = "amendment.effect";
    static final String OP_AMENDMENT_REVOKE = "amendment.revoke";

    private final CenterService centerService;
    private final ProtocolAmendmentService amendmentService;
    private final ExperimentService experimentService;
    private final IdempotencyService idempotencyService;
    private final ActorContext actorContext;

    public ProtocolCenterController(CenterService centerService,
                                    ProtocolAmendmentService amendmentService,
                                    ExperimentService experimentService,
                                    IdempotencyService idempotencyService,
                                    ActorContext actorContext) {
        this.centerService = centerService;
        this.amendmentService = amendmentService;
        this.experimentService = experimentService;
        this.idempotencyService = idempotencyService;
        this.actorContext = actorContext;
    }

    // ---------------- 中心 ----------------

    /** 激活中心（仅 COORDINATOR）：按目标入组上限预留当前协议盲码序列。 */
    @PostMapping("/experiments/{experimentId}/centers/{centerId}")
    public ResponseEntity<String> activateCenter(
            @PathVariable String experimentId,
            @PathVariable String centerId,
            @Valid @RequestBody CreateCenterRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String ctrId = RequestTokens.requireId("centerId", centerId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_CENTER_ACTIVATE, Map.of(
                "experimentId", expId,
                "centerId", ctrId,
                "targetCap", request.targetCap()));
        return idempotencyService.runWrite(reqId, OP_CENTER_ACTIVATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        centerService.activate(expId, ctrId, request.targetCap())));
    }

    /** 查询中心信息（含剩余容量与当前协议版本）。 */
    @GetMapping("/experiments/{experimentId}/centers/{centerId}")
    public CenterView getCenter(@PathVariable String experimentId,
                                @PathVariable String centerId) {
        requireActor();
        return centerService.getCenter(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("centerId", centerId));
    }

    /** 暂停中心（仅 COORDINATOR）。 */
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
                        centerService.suspend(expId, ctrId)));
    }

    /** 恢复中心（仅 COORDINATOR）：恢复后使用当时有效协议版本。 */
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
                        centerService.resume(expId, ctrId)));
    }

    /** 查询中心在某协议版本下的盲码序列容量与消耗进度。 */
    @GetMapping("/experiments/{experimentId}/centers/{centerId}/sequences/{version}")
    public CenterSequenceView getSequence(@PathVariable String experimentId,
                                          @PathVariable String centerId,
                                          @PathVariable int version) {
        requireActor();
        return centerService.getSequence(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("centerId", centerId),
                version);
    }

    /** 中心登记参与者（仅 COORDINATOR）：消耗该中心当前协议版本的独立盲码序列。 */
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
                        experimentService.registerAtCenter(expId, ctrId, pid, actor.actorId())));
    }

    // ---------------- 协议修订 ----------------

    /** 创建单条协议修订（仅 COORDINATOR）：比例正整数且和为 100，生效时刻不早于当前。 */
    @PostMapping("/experiments/{experimentId}/protocol-amendments")
    public ResponseEntity<String> createAmendment(
            @PathVariable String experimentId,
            @Valid @RequestBody CreateAmendmentRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = amendmentFingerprint(OP_AMENDMENT_CREATE, expId,
                request.ratioA(), request.ratioB(), request.effectiveAt());
        return idempotencyService.runWrite(reqId, OP_AMENDMENT_CREATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        amendmentService.create(expId, request.ratioA(), request.ratioB(),
                                request.effectiveAt(), actor.actorId())));
    }

    /** 批量创建协议修订（仅 COORDINATOR）：只允许一条最终待生效版本。 */
    @PostMapping("/experiments/{experimentId}/protocol-amendments/batch")
    public ResponseEntity<String> createAmendmentsBatch(
            @PathVariable String experimentId,
            @Valid @RequestBody CreateAmendmentRequest.Batch batch,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_AMENDMENT_CREATE,
                Map.of("experimentId", expId, "batch", batch.amendments().stream()
                        .map(a -> Map.of("ratioA", a.ratioA(), "ratioB", a.ratioB(),
                                "effectiveAt", a.effectiveAt()))
                        .toList()));
        return idempotencyService.runWrite(reqId, OP_AMENDMENT_CREATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        amendmentService.createBatch(expId, batch.amendments(), actor.actorId())));
    }

    /** 查询实验全部协议版本（含初始 V1 与已撤销记录）。 */
    @GetMapping("/experiments/{experimentId}/protocol-versions")
    public List<ProtocolVersionView> listVersions(@PathVariable String experimentId) {
        requireActor();
        return amendmentService.listVersions(
                RequestTokens.requireId("experimentId", experimentId));
    }

    /** 手动生效协议修订（仅 COORDINATOR）：须已到生效时刻。 */
    @PostMapping("/experiments/{experimentId}/protocol-amendments/{version}/effect")
    public ResponseEntity<String> effectAmendment(
            @PathVariable String experimentId,
            @PathVariable int version,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_AMENDMENT_EFFECT,
                Map.of("experimentId", expId, "version", version));
        return idempotencyService.runWrite(reqId, OP_AMENDMENT_EFFECT, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        amendmentService.effect(expId, version)));
    }

    /** 撤销未生效协议修订（仅 COORDINATOR）：保留 REVOKED 记录。 */
    @PostMapping("/experiments/{experimentId}/protocol-amendments/{version}/revocation")
    public ResponseEntity<String> revokeAmendment(
            @PathVariable String experimentId,
            @PathVariable int version,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_AMENDMENT_REVOKE,
                Map.of("experimentId", expId, "version", version));
        return idempotencyService.runWrite(reqId, OP_AMENDMENT_REVOKE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        amendmentService.revoke(expId, version, actor.actorId())));
    }

    /**
     * 协议修订指纹：含实验、按 gcd 规范化的比例（如 30:70 → 3:7）、生效时刻；
     * 操作者编号/角色由幂等记录另存并比对。
     */
    private String amendmentFingerprint(String operation, String experimentId,
                                        int ratioA, int ratioB, long effectiveAt) {
        int gcd = gcd(ratioA, ratioB);
        return idempotencyService.fingerprint(operation, Map.of(
                "experimentId", experimentId,
                "ratio", (ratioA / gcd) + ":" + (ratioB / gcd),
                "effectiveAt", effectiveAt));
    }

    private int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
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
}
