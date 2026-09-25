package com.example.starter.blind.web;

import com.example.starter.blind.Actor;
import com.example.starter.blind.ActorContext;
import com.example.starter.blind.ApiException;
import com.example.starter.blind.RequestTokens;
import com.example.starter.blind.dto.ActivationConfirmRequest;
import com.example.starter.blind.dto.CreateSiteRequest;
import com.example.starter.blind.dto.SiteActivationPendingView;
import com.example.starter.blind.dto.SiteActivationRecordView;
import com.example.starter.blind.dto.SiteView;
import com.example.starter.blind.service.ExperimentService;
import com.example.starter.blind.service.IdempotencyService;
import com.example.starter.blind.service.SiteService;
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
 * 试验中心激活门禁接口。
 * 中心管理写操作（创建/暂停/关闭）仅 UNBLINDED_MANAGER；激活/恢复确认须两名不同
 * UNBLINDED_MANAGER 以同一 activationKey 先后提交；中心门禁登记仅 COORDINATOR。
 * 所有写操作必须携带 X-Request-Id；权限校验先于幂等回放。
 */
@RestController
@RequestMapping("/api/experiments/{experimentId}/sites")
public class SiteController {

    static final String OP_SITE_CREATE = "site.create";
    static final String OP_SITE_ACTIVATE = "site.activate";
    static final String OP_SITE_SUSPEND = "site.suspend";
    static final String OP_SITE_CLOSE = "site.close";
    static final String OP_SITE_ALLOCATION_CREATE = "site.allocation.create";

    private final SiteService siteService;
    private final ExperimentService experimentService;
    private final IdempotencyService idempotencyService;
    private final ActorContext actorContext;

    public SiteController(SiteService siteService,
                          ExperimentService experimentService,
                          IdempotencyService idempotencyService,
                          ActorContext actorContext) {
        this.siteService = siteService;
        this.experimentService = experimentService;
        this.idempotencyService = idempotencyService;
        this.actorContext = actorContext;
    }

    /** 创建中心（仅 UNBLINDED_MANAGER）：初始 INACTIVE、代次 0。 */
    @PostMapping("/{siteCode}")
    public ResponseEntity<String> createSite(
            @PathVariable String experimentId,
            @PathVariable String siteCode,
            @Valid @RequestBody CreateSiteRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireManager();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String code = RequestTokens.requireId("siteCode", siteCode);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SITE_CREATE,
                Map.of("experimentId", expId, "siteCode", code,
                        "targetEnrollmentLimit", request.targetEnrollmentLimit()));
        return idempotencyService.runWrite(reqId, OP_SITE_CREATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        siteService.createSite(expId, code, request.targetEnrollmentLimit())));
    }

    /** 查询中心详情：代次、累计分配、剩余容量与门禁原因（任意已认证角色）。 */
    @GetMapping("/{siteCode}")
    public SiteView getSite(@PathVariable String experimentId,
                            @PathVariable String siteCode) {
        requireActor();
        return siteService.getSite(RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("siteCode", siteCode));
    }

    /** 查询中心全部不可变双人激活记录（任意已认证角色）。 */
    @GetMapping("/{siteCode}/activation-records")
    public List<SiteActivationRecordView> getActivationRecords(
            @PathVariable String experimentId,
            @PathVariable String siteCode) {
        requireActor();
        return siteService.getActivationRecords(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("siteCode", siteCode));
    }

    /**
     * 双人激活/恢复确认（仅 UNBLINDED_MANAGER）：
     * 首确认返回 202 暂存视图；第二名不同人员以相同 activationKey 确认返回 200 激活记录。
     */
    @PostMapping("/{siteCode}/activation-confirmations")
    public ResponseEntity<String> confirmActivation(
            @PathVariable String experimentId,
            @PathVariable String siteCode,
            @Valid @RequestBody ActivationConfirmRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireManager();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String code = RequestTokens.requireId("siteCode", siteCode);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SITE_ACTIVATE,
                Map.of("experimentId", expId, "siteCode", code,
                        "activationKey", request.activationKey()));
        return idempotencyService.runWrite(reqId, OP_SITE_ACTIVATE, fingerprint, actor, () -> {
            Object result = siteService.confirmActivation(expId, code,
                    request.activationKey(), actor.actorId());
            // 首确认暂存 202；第二人确认完成激活 200。
            int status = result instanceof SiteActivationPendingView
                    ? HttpStatus.ACCEPTED.value() : HttpStatus.OK.value();
            return IdempotencyService.WriteOutcome.of(status, result);
        });
    }

    /** 暂停中心（仅 UNBLINDED_MANAGER）：拒绝新分配，既有盲态与揭盲权限不变。 */
    @PostMapping("/{siteCode}/suspension")
    public ResponseEntity<String> suspend(
            @PathVariable String experimentId,
            @PathVariable String siteCode,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireManager();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String code = RequestTokens.requireId("siteCode", siteCode);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SITE_SUSPEND,
                Map.of("experimentId", expId, "siteCode", code));
        return idempotencyService.runWrite(reqId, OP_SITE_SUSPEND, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        siteService.suspend(expId, code)));
    }

    /** 关闭中心（仅 UNBLINDED_MANAGER）：存在待审揭盲申请时 422；关闭后不可恢复。 */
    @PostMapping("/{siteCode}/closure")
    public ResponseEntity<String> close(
            @PathVariable String experimentId,
            @PathVariable String siteCode,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireManager();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String code = RequestTokens.requireId("siteCode", siteCode);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SITE_CLOSE,
                Map.of("experimentId", expId, "siteCode", code));
        return idempotencyService.runWrite(reqId, OP_SITE_CLOSE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        siteService.close(expId, code)));
    }

    /** 中心门禁登记（仅 COORDINATOR）：中心未激活 409，累计达上限 422。 */
    @PostMapping("/{siteCode}/participants/{participantId}/allocations")
    public ResponseEntity<String> registerAtSite(
            @PathVariable String experimentId,
            @PathVariable String siteCode,
            @PathVariable String participantId,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String code = RequestTokens.requireId("siteCode", siteCode);
        String pid = RequestTokens.requireId("participantId", participantId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SITE_ALLOCATION_CREATE,
                Map.of("experimentId", expId, "siteCode", code, "participantId", pid));
        return idempotencyService.runWrite(reqId, OP_SITE_ALLOCATION_CREATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        experimentService.registerAtSite(expId, code, pid, actor.actorId())));
    }

    // ---------------- 权限辅助（先于幂等回放执行） ----------------

    private Actor requireActor() {
        Actor actor = actorContext.get();
        if (actor == null) {
            throw ApiException.unauthorized("缺少操作者身份头");
        }
        return actor;
    }

    private Actor requireManager() {
        Actor actor = requireActor();
        if (!actor.isUnblindedManager()) {
            throw ApiException.forbidden("仅 UNBLINDED_MANAGER 可执行该操作");
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
