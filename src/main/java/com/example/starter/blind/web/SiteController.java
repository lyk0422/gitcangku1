package com.example.starter.blind.web;

import com.example.starter.blind.Actor;
import com.example.starter.blind.ActorContext;
import com.example.starter.blind.ApiException;
import com.example.starter.blind.RequestTokens;
import com.example.starter.blind.dto.ActivationConfirmRequest;
import com.example.starter.blind.dto.CreateSiteRequest;
import com.example.starter.blind.dto.SiteAssignRequest;
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

import java.util.Map;

/**
 * 试验中心激活门禁接口：创建、双人确认激活、暂停、关闭、中心作用域分配与查询。
 * 所有写操作必须携带 X-Request-Id；权限校验先于幂等回放。
 * 中心写操作仅限 COORDINATOR（未盲法管理人员）。
 */
@RestController
@RequestMapping("/api/experiments/{experimentId}/sites")
public class SiteController {

    static final String OP_SITE_CREATE = "site.create";
    static final String OP_SITE_ACTIVATION_CONFIRM = "site.activation.confirm";
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

    /** 创建中心（仅 COORDINATOR）：初始 PENDING、代次 0。 */
    @PostMapping("/{siteCode}")
    public ResponseEntity<String> createSite(
            @PathVariable String experimentId,
            @PathVariable String siteCode,
            @Valid @RequestBody CreateSiteRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String site = RequestTokens.requireId("siteCode", siteCode);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SITE_CREATE,
                Map.of("experimentId", expId, "siteCode", site, "targetCap", request.targetCap()));
        return idempotencyService.runWrite(reqId, OP_SITE_CREATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        siteService.createSite(expId, site, request.targetCap())));
    }

    /** 查询中心（两种角色均可）：代次、双人激活记录、累计分配数与门禁原因。 */
    @GetMapping("/{siteCode}")
    public SiteView getSite(@PathVariable String experimentId,
                            @PathVariable String siteCode) {
        requireActor();
        return siteService.getSite(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("siteCode", siteCode));
    }

    /**
     * 双人确认激活（仅 COORDINATOR）：两名不同操作者提交同一 activationKey；
     * 第一人记录待确认，第二人确认时校验中心未关闭且上限大于零，
     * 同事务写入不可变激活记录并置 ACTIVE、产生新代次。
     */
    @PostMapping("/{siteCode}/activation-confirmations")
    public ResponseEntity<String> confirmActivation(
            @PathVariable String experimentId,
            @PathVariable String siteCode,
            @Valid @RequestBody ActivationConfirmRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String site = RequestTokens.requireId("siteCode", siteCode);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SITE_ACTIVATION_CONFIRM,
                Map.of("experimentId", expId, "siteCode", site,
                        "activationKey", request.activationKey()));
        return idempotencyService.runWrite(reqId, OP_SITE_ACTIVATION_CONFIRM, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        siteService.confirmActivation(expId, site,
                                request.activationKey(), actor.actorId())));
    }

    /** 暂停中心（仅 COORDINATOR）：不接受新分配，既有盲态、区组容量与揭盲权限不变。 */
    @PostMapping("/{siteCode}/suspension")
    public ResponseEntity<String> suspend(
            @PathVariable String experimentId,
            @PathVariable String siteCode,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String site = RequestTokens.requireId("siteCode", siteCode);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SITE_SUSPEND,
                Map.of("experimentId", expId, "siteCode", site));
        return idempotencyService.runWrite(reqId, OP_SITE_SUSPEND, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        siteService.suspend(expId, site)));
    }

    /** 关闭中心（仅 COORDINATOR）：存在待处理揭盲申请时 422；关闭为终态。 */
    @PostMapping("/{siteCode}/closure")
    public ResponseEntity<String> close(
            @PathVariable String experimentId,
            @PathVariable String siteCode,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String site = RequestTokens.requireId("siteCode", siteCode);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SITE_CLOSE,
                Map.of("experimentId", expId, "siteCode", site));
        return idempotencyService.runWrite(reqId, OP_SITE_CLOSE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        siteService.closeSite(expId, site)));
    }

    /**
     * 中心作用域分配（仅 COORDINATOR）：中心未激活/暂停/关闭返回 409；
     * 中心累计分配达上限返回 422，退组不回收容量。
     */
    @PostMapping("/{siteCode}/participants/{participantId}/allocations")
    public ResponseEntity<String> registerAtSite(
            @PathVariable String experimentId,
            @PathVariable String siteCode,
            @PathVariable String participantId,
            @Valid @RequestBody SiteAssignRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String site = RequestTokens.requireId("siteCode", siteCode);
        String pid = RequestTokens.requireId("participantId", participantId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SITE_ALLOCATION_CREATE,
                Map.of("experimentId", expId, "siteCode", site,
                        "participantId", pid, "assignmentKey", request.assignmentKey()));
        return idempotencyService.runWrite(reqId, OP_SITE_ALLOCATION_CREATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        experimentService.registerAtSite(expId, site, pid,
                                request.assignmentKey(), actor.actorId())));
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
