package com.example.starter.blind.web;

import com.example.starter.blind.Actor;
import com.example.starter.blind.ActorContext;
import com.example.starter.blind.ApiException;
import com.example.starter.blind.RequestTokens;
import com.example.starter.blind.dto.ClosureVersionView;
import com.example.starter.blind.dto.ClosureView;
import com.example.starter.blind.dto.DisclosureRequest;
import com.example.starter.blind.dto.DisclosureView;
import com.example.starter.blind.dto.QuarantineInitiateRequest;
import com.example.starter.blind.dto.QuarantineOrderView;
import com.example.starter.blind.service.ContaminationService;
import com.example.starter.blind.service.IdempotencyService;
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
 * 泄露传播图、污染闭包版本与合规隔离接口。
 * 所有写操作必须携带 X-Request-Id；权限校验先于幂等回放。
 * 任何响应都不返回处理代码。
 */
@RestController
@RequestMapping("/api")
public class ContaminationController {

    static final String OP_DISCLOSURE_REGISTER = "disclosure.register";
    static final String OP_QUARANTINE_INITIATE = "quarantine.initiate";
    static final String OP_QUARANTINE_CONFIRM = "quarantine.confirm";

    private final ContaminationService contaminationService;
    private final IdempotencyService idempotencyService;
    private final ActorContext actorContext;

    public ContaminationController(ContaminationService contaminationService,
                                   IdempotencyService idempotencyService,
                                   ActorContext actorContext) {
        this.contaminationService = contaminationService;
        this.idempotencyService = idempotencyService;
        this.actorContext = actorContext;
    }

    // ---------------- 泄露披露 ----------------

    /**
     * 登记本人向 1~20 名操作者直接披露了某参与者处理代码。
     * 任何角色均可调用，但服务层强制要求调用者本人已获知该参与者代码
     * （已批准揭盲申请人，或闭包内下游接收人）；未获知者 403。
     */
    @PostMapping("/experiments/{experimentId}/participants/{participantId}/disclosures")
    public ResponseEntity<String> registerDisclosure(
            @PathVariable String experimentId,
            @PathVariable String participantId,
            @Valid @RequestBody DisclosureRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireActor();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String pid = RequestTokens.requireId("participantId", participantId);
        String reqId = RequestTokens.requireRequestId(requestId);
        // 接收人为集合语义：同参集合换序重放视为同参，指纹按 trim 后排序去重列表计算。
        List<String> orderedTargets = sortedDistinct(request.targetActorIds());
        String fingerprint = idempotencyService.fingerprint(OP_DISCLOSURE_REGISTER,
                Map.of("experimentId", expId,
                        "participantId", pid,
                        "exposureKey", request.exposureKey() == null ? "" : request.exposureKey(),
                        "targetActorIds", orderedTargets));
        return idempotencyService.runWrite(reqId, OP_DISCLOSURE_REGISTER, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        contaminationService.disclose(expId, pid, request.exposureKey(),
                                request.targetActorIds(), actor.actorId(), reqId)));
    }

    // ---------------- 闭包与版本查询 ----------------

    /** 查询参与者当前污染闭包；不返回处理代码。 */
    @GetMapping("/experiments/{experimentId}/participants/{participantId}/contamination/closure")
    public ClosureView getClosure(@PathVariable String experimentId,
                                  @PathVariable String participantId) {
        requireActor();
        return contaminationService.getClosure(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("participantId", participantId));
    }

    /** 查询某参与者全部闭包版本；不返回处理代码。 */
    @GetMapping("/experiments/{experimentId}/participants/{participantId}/contamination/versions")
    public List<ClosureVersionView> listVersions(@PathVariable String experimentId,
                                                 @PathVariable String participantId) {
        requireActor();
        return contaminationService.listVersions(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("participantId", participantId));
    }

    /** 查询单个闭包版本；不返回处理代码。 */
    @GetMapping("/experiments/{experimentId}/participants/{participantId}/contamination/versions/{versionNo}")
    public ClosureVersionView getVersion(@PathVariable String experimentId,
                                         @PathVariable String participantId,
                                         @PathVariable int versionNo) {
        requireActor();
        if (versionNo < 1) {
            throw ApiException.badRequest("versionNo 必须为正整数");
        }
        return contaminationService.getVersion(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("participantId", participantId), versionNo);
    }

    // ---------------- 合规隔离 ----------------

    /** 合规负责人发起隔离单，提交当前闭包版本与完整操作者集合。 */
    @PostMapping("/experiments/{experimentId}/participants/{participantId}/quarantine-orders")
    public ResponseEntity<String> initiateQuarantine(
            @PathVariable String experimentId,
            @PathVariable String participantId,
            @Valid @RequestBody QuarantineInitiateRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCompliance();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String pid = RequestTokens.requireId("participantId", participantId);
        String reqId = RequestTokens.requireRequestId(requestId);
        // 提交闭包为集合语义：换序重放视为同参。
        List<String> orderedActors = sortedDistinct(request.actors());
        String fingerprint = idempotencyService.fingerprint(OP_QUARANTINE_INITIATE,
                Map.of("experimentId", expId,
                        "participantId", pid,
                        "versionNo", request.versionNo() == null ? -1 : request.versionNo(),
                        "actors", orderedActors));
        return idempotencyService.runWrite(reqId, OP_QUARANTINE_INITIATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        contaminationService.initiateQuarantine(expId, pid,
                                request.versionNo(), request.actors(), actor.actorId())));
    }

    /** 另一名不在闭包内的合规负责人确认隔离单并冻结该版本。 */
    @PostMapping("/quarantine-orders/{orderId}/confirmation")
    public ResponseEntity<String> confirmQuarantine(
            @PathVariable String orderId,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCompliance();
        String qId = RequestTokens.requireId("orderId", orderId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_QUARANTINE_CONFIRM,
                Map.of("orderId", qId));
        return idempotencyService.runWrite(reqId, OP_QUARANTINE_CONFIRM, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        contaminationService.confirmQuarantine(qId, actor.actorId())));
    }

    /** 查询单个隔离单；不返回处理代码。 */
    @GetMapping("/quarantine-orders/{orderId}")
    public QuarantineOrderView getQuarantine(@PathVariable String orderId) {
        requireActor();
        return contaminationService.getQuarantine(
                RequestTokens.requireId("orderId", orderId));
    }

    /** 查询某参与者隔离历史；不返回处理代码。 */
    @GetMapping("/experiments/{experimentId}/participants/{participantId}/quarantine-orders")
    public List<QuarantineOrderView> listQuarantineHistory(@PathVariable String experimentId,
                                                           @PathVariable String participantId) {
        requireActor();
        return contaminationService.listQuarantineHistory(
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

    private Actor requireCompliance() {
        Actor actor = requireActor();
        if (!actor.isCompliance()) {
            throw ApiException.forbidden("仅 COMPLIANCE 可执行该操作");
        }
        return actor;
    }

    /** 集合参数规范化：trim、忽略空白项、排序去重，使换序/重复/空白差异不产生不同指纹。 */
    private static List<String> sortedDistinct(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }
}
