package com.example.starter.blind.web;

import com.example.starter.blind.Actor;
import com.example.starter.blind.ActorContext;
import com.example.starter.blind.RequestTokens;
import com.example.starter.blind.dto.ContaminationView;
import com.example.starter.blind.dto.DisclosureRequest;
import com.example.starter.blind.dto.DisclosureView;
import com.example.starter.blind.dto.QuarantineCreateRequest;
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
 * 揭盲泄露传播、污染闭包查询与隔离门禁接口。
 * 写操作必须携带 X-Request-Id；权限校验先于幂等回放；所有视图均不含处理代码。
 */
@RestController
@RequestMapping("/api")
public class ContaminationController {

    static final String OP_DISCLOSURE_REGISTER = "disclosure.register";
    static final String OP_QUARANTINE_CREATE = "quarantine.create";
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

    /** 登记本人向 1~20 名操作者直接披露已获知参与者处理代码（任意已认证操作者）。 */
    @PostMapping("/experiments/{experimentId}/disclosures")
    public ResponseEntity<String> registerDisclosure(
            @PathVariable String experimentId,
            @Valid @RequestBody DisclosureRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireActor();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String reqId = RequestTokens.requireRequestId(requestId);
        // 参数按集合去重排序后参与指纹，保证“同参集合换序重放”判定为同参。
        String fingerprint = idempotencyService.fingerprint(OP_DISCLOSURE_REGISTER,
                Map.of("experimentId", expId,
                        "exposureKey", request.exposureKey(),
                        "receiverActors", canonical(request.receiverActors()),
                        "participantIds", canonical(request.participantIds())));
        return idempotencyService.runWrite(reqId, OP_DISCLOSURE_REGISTER, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        contaminationService.registerDisclosure(expId, request.exposureKey(),
                                request.receiverActors(), request.participantIds(),
                                actor.actorId())));
    }

    /** 查询某参与者当前污染闭包（不含处理代码）。 */
    @GetMapping("/experiments/{experimentId}/participants/{participantId}/contamination")
    public ContaminationView getClosure(@PathVariable String experimentId,
                                        @PathVariable String participantId) {
        requireActor();
        return contaminationService.getClosure(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("participantId", participantId));
    }

    /** 查询某参与者闭包版本历史。 */
    @GetMapping("/experiments/{experimentId}/participants/{participantId}/contamination/versions")
    public List<ContaminationView> getVersions(@PathVariable String experimentId,
                                               @PathVariable String participantId) {
        requireActor();
        return contaminationService.getVersions(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("participantId", participantId));
    }

    /** 合规负责人发起隔离单。 */
    @PostMapping("/experiments/{experimentId}/participants/{participantId}/quarantine-orders")
    public ResponseEntity<String> createQuarantine(
            @PathVariable String experimentId,
            @PathVariable String participantId,
            @Valid @RequestBody QuarantineCreateRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCompliance();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String pid = RequestTokens.requireId("participantId", participantId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_QUARANTINE_CREATE,
                Map.of("experimentId", expId,
                        "participantId", pid,
                        "version", request.version(),
                        "actors", canonical(request.actors())));
        return idempotencyService.runWrite(reqId, OP_QUARANTINE_CREATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        contaminationService.createQuarantine(expId, pid, request.version(),
                                request.actors(), actor.actorId())));
    }

    /** 另一名不在闭包内的合规负责人确认隔离单。 */
    @PostMapping("/quarantine-orders/{orderId}/confirmation")
    public ResponseEntity<String> confirmQuarantine(
            @PathVariable String orderId,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCompliance();
        String oId = RequestTokens.requireId("orderId", orderId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_QUARANTINE_CONFIRM,
                Map.of("orderId", oId));
        return idempotencyService.runWrite(reqId, OP_QUARANTINE_CONFIRM, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        contaminationService.confirmQuarantine(oId, actor.actorId())));
    }

    /** 查询某参与者隔离历史（不含处理代码）。 */
    @GetMapping("/experiments/{experimentId}/participants/{participantId}/quarantine-orders")
    public List<QuarantineOrderView> getQuarantineHistory(@PathVariable String experimentId,
                                                          @PathVariable String participantId) {
        requireActor();
        return contaminationService.getQuarantineHistory(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("participantId", participantId));
    }

    // ---------------- 权限辅助（先于幂等回放执行） ----------------

    private Actor requireActor() {
        Actor actor = actorContext.get();
        if (actor == null) {
            throw com.example.starter.blind.ApiException.unauthorized("缺少操作者身份头");
        }
        return actor;
    }

    private Actor requireCompliance() {
        Actor actor = requireActor();
        if (actor.role() != com.example.starter.blind.Role.COMPLIANCE) {
            throw com.example.starter.blind.ApiException.forbidden("仅 COMPLIANCE 合规负责人可执行该操作");
        }
        return actor;
    }

    /** 集合参数规范化：去空、去重并按字典序排序，使换序提交得到相同幂等指纹。 */
    private static java.util.List<String> canonical(java.util.List<String> values) {
        return new java.util.ArrayList<>(new java.util.TreeSet<>(values));
    }
}
