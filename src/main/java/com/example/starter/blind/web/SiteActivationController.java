package com.example.starter.blind.web;

import com.example.starter.blind.Actor;
import com.example.starter.blind.ActorContext;
import com.example.starter.blind.ApiException;
import com.example.starter.blind.RequestTokens;
import com.example.starter.blind.dto.CreateSiteRequest;
import com.example.starter.blind.dto.RemainingQuotaView;
import com.example.starter.blind.dto.SiteActivationView;
import com.example.starter.blind.dto.SiteAllocationView;
import com.example.starter.blind.dto.SiteView;
import com.example.starter.blind.dto.SubmitSiteConfigRequest;
import com.example.starter.blind.service.IdempotencyService;
import com.example.starter.blind.service.SiteActivationService;
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

import java.util.Map;

/**
 * 研究中心启用门禁接口（骨架）。
 * 写操作均须携带 X-Request-Id 并走幂等编排；所有响应不含处理代码与席位序号。
 */
@RestController
@RequestMapping("/api/sites")
public class SiteActivationController {

    static final String OP_SITE_CREATE = "site.create";
    static final String OP_SITE_CONFIG_SUBMIT = "site.activation.submit";
    static final String OP_SITE_LEAD_CONFIRM = "site.activation.leadConfirm";
    static final String OP_SITE_REVIEWER_CONFIRM = "site.activation.reviewerConfirm";
    static final String OP_SITE_ALLOCATION_CREATE = "site.allocation.create";

    private final SiteActivationService siteActivationService;
    private final IdempotencyService idempotencyService;
    private final ActorContext actorContext;

    public SiteActivationController(SiteActivationService siteActivationService,
                                    IdempotencyService idempotencyService,
                                    ActorContext actorContext) {
        this.siteActivationService = siteActivationService;
        this.idempotencyService = idempotencyService;
        this.actorContext = actorContext;
    }

    /** 创建研究中心（仅 COORDINATOR）。 */
    @PostMapping("/{siteId}")
    public ResponseEntity<String> createSite(
            @PathVariable String siteId,
            @Valid @RequestBody CreateSiteRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String sid = RequestTokens.requireId("siteId", siteId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SITE_CREATE,
                Map.of("siteId", sid, "leadActorId", request.leadActorId()));
        return idempotencyService.runWrite(reqId, OP_SITE_CREATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        siteActivationService.createSite(sid, request.leadActorId().trim())));
    }

    /** 查询中心状态（两种角色均可，不含名额明细）。 */
    @GetMapping("/{siteId}")
    public SiteView getSite(@PathVariable String siteId) {
        requireActor();
        return siteActivationService.getSite(RequestTokens.requireId("siteId", siteId));
    }

    /** 中心负责人提交启用配置版本（仅 COORDINATOR 且为该中心负责人）。 */
    @PostMapping("/{siteId}/activation-requests")
    public ResponseEntity<String> submitConfig(
            @PathVariable String siteId,
            @Valid @RequestBody SubmitSiteConfigRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String sid = RequestTokens.requireId("siteId", siteId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SITE_CONFIG_SUBMIT,
                Map.of("siteId", sid, "blocks", request.blocks()));
        return idempotencyService.runWrite(reqId, OP_SITE_CONFIG_SUBMIT, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        siteActivationService.submitConfig(sid, request, actor.actorId(), reqId)));
    }

    /** 负责人确认配置版本。 */
    @PostMapping("/activation-requests/{activationRequestId}/lead-confirmation")
    public ResponseEntity<String> leadConfirm(
            @PathVariable String activationRequestId,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String arId = RequestTokens.requireId("activationRequestId", activationRequestId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SITE_LEAD_CONFIRM,
                Map.of("activationRequestId", arId));
        return idempotencyService.runWrite(reqId, OP_SITE_LEAD_CONFIRM, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        siteActivationService.confirmByLead(arId, actor.actorId())));
    }

    /** 独立复核人确认配置版本；双人齐备即一次性启用。 */
    @PostMapping("/activation-requests/{activationRequestId}/reviewer-confirmation")
    public ResponseEntity<String> reviewerConfirm(
            @PathVariable String activationRequestId,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireReviewer();
        String arId = RequestTokens.requireId("activationRequestId", activationRequestId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SITE_REVIEWER_CONFIRM,
                Map.of("activationRequestId", arId));
        return idempotencyService.runWrite(reqId, OP_SITE_REVIEWER_CONFIRM, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        siteActivationService.confirmByReviewer(arId, actor.actorId())));
    }

    /** 负责人查看版本与名额明细（配额、冻结量、已用、剩余）。 */
    @GetMapping("/activation-requests/{activationRequestId}")
    public SiteActivationView getActivation(@PathVariable String activationRequestId) {
        Actor actor = requireActor();
        return siteActivationService.getActivationForLead(
                RequestTokens.requireId("activationRequestId", activationRequestId), actor.actorId());
    }

    /** 分配方查看当前已启用快照的各区组剩余额度（只看剩余）。 */
    @GetMapping("/{siteId}/remaining-quota")
    public RemainingQuotaView getRemainingQuota(@PathVariable String siteId) {
        requireActor();
        return siteActivationService.getRemainingQuota(
                RequestTokens.requireId("siteId", siteId));
    }

    /** 中心分配（仅 COORDINATOR）：未启用拒绝；不超冻结量；响应不含处理组。 */
    @PostMapping("/{siteId}/participants/{participantId}/allocations")
    public ResponseEntity<String> allocate(
            @PathVariable String siteId,
            @PathVariable String participantId,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String sid = RequestTokens.requireId("siteId", siteId);
        String pid = RequestTokens.requireId("participantId", participantId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SITE_ALLOCATION_CREATE,
                Map.of("siteId", sid, "participantId", pid));
        return idempotencyService.runWrite(reqId, OP_SITE_ALLOCATION_CREATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        siteActivationService.allocate(sid, pid, actor.actorId())));
    }

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
