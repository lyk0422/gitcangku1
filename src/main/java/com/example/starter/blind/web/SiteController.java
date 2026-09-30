package com.example.starter.blind.web;

import com.example.starter.blind.Actor;
import com.example.starter.blind.ActorContext;
import com.example.starter.blind.ApiException;
import com.example.starter.blind.RequestTokens;
import com.example.starter.blind.dto.ActivationView;
import com.example.starter.blind.dto.ApplyActivationRequest;
import com.example.starter.blind.dto.ConfirmActivationRequest;
import com.example.starter.blind.dto.RemainingQuotaView;
import com.example.starter.blind.dto.SiteAllocationView;
import com.example.starter.blind.dto.SiteConfigView;
import com.example.starter.blind.dto.SiteView;
import com.example.starter.blind.dto.SubmitSiteConfigRequest;
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
 * 研究中心启用门禁与名额分配接口：
 * LEAD 提交配置版本、启用申请并查看启用版本与名额；CONFIRMER 独立核对并确认启用；
 * COORDINATOR 仅在中心启用后分配，且只能查看剩余额度。所有写操作必须携带 X-Request-Id。
 */
@RestController
@RequestMapping("/api")
public class SiteController {

    static final String OP_SITE_CONFIG_SUBMIT = "site.config.submit";
    static final String OP_SITE_ACTIVATION_APPLY = "site.activation.apply";
    static final String OP_SITE_ACTIVATION_CONFIRM = "site.activation.confirm";
    static final String OP_SITE_ALLOCATION_CREATE = "site.allocation.create";

    private final SiteService siteService;
    private final IdempotencyService idempotencyService;
    private final ActorContext actorContext;

    public SiteController(SiteService siteService,
                          IdempotencyService idempotencyService,
                          ActorContext actorContext) {
        this.siteService = siteService;
        this.idempotencyService = idempotencyService;
        this.actorContext = actorContext;
    }

    // ---------------- 配置版本 ----------------

    /** 提交中心配置版本（仅 LEAD）；首版本创建中心，版本号自增、内容不可变。 */
    @PostMapping("/experiments/{experimentId}/sites/{siteId}/config-versions")
    public ResponseEntity<String> submitConfig(
            @PathVariable String experimentId,
            @PathVariable String siteId,
            @Valid @RequestBody SubmitSiteConfigRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireLead();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String stId = RequestTokens.requireId("siteId", siteId);
        String reqId = RequestTokens.requireRequestId(requestId);
        List<Map<String, Object>> blocks = request.blocks().stream()
                .map(b -> Map.<String, Object>of("blockCode", b.blockCode(), "quota", b.quota()))
                .toList();
        String fingerprint = idempotencyService.fingerprint(OP_SITE_CONFIG_SUBMIT,
                Map.of("experimentId", expId, "siteId", stId, "blocks", blocks));
        return idempotencyService.runWrite(reqId, OP_SITE_CONFIG_SUBMIT, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        siteService.submitConfig(expId, stId, request.blocks(), actor.actorId())));
    }

    /** 中心负责人查看中心状态、启用版本与名额。 */
    @GetMapping("/experiments/{experimentId}/sites/{siteId}")
    public SiteView getSite(@PathVariable String experimentId, @PathVariable String siteId) {
        requireLead();
        return siteService.getSite(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("siteId", siteId));
    }

    /** 查看指定配置版本（LEAD 申请前准备、CONFIRMER 核对区组及名额）。 */
    @GetMapping("/experiments/{experimentId}/sites/{siteId}/config-versions/{version}")
    public SiteConfigView getConfig(@PathVariable String experimentId,
                                    @PathVariable String siteId,
                                    @PathVariable int version) {
        requireLeadOrConfirmer();
        return siteService.getConfig(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("siteId", siteId),
                version);
    }

    // ---------------- 启用申请与双人确认 ----------------

    /** 中心负责人提交启用申请（即 LEAD 确认），冻结所引用版本的区组名额快照。 */
    @PostMapping("/experiments/{experimentId}/sites/{siteId}/activations")
    public ResponseEntity<String> applyActivation(
            @PathVariable String experimentId,
            @PathVariable String siteId,
            @Valid @RequestBody ApplyActivationRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireLead();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String stId = RequestTokens.requireId("siteId", siteId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SITE_ACTIVATION_APPLY,
                Map.of("experimentId", expId, "siteId", stId,
                        "configVersion", request.configVersion()));
        return idempotencyService.runWrite(reqId, OP_SITE_ACTIVATION_APPLY, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        siteService.applyActivation(expId, stId, request.configVersion(),
                                actor.actorId())));
    }

    /** 另一名 CONFIRMER 核对区组及名额，确认同一配置版本后中心一次性启用。 */
    @PostMapping("/activations/{activationId}/confirmation")
    public ResponseEntity<String> confirmActivation(
            @PathVariable String activationId,
            @Valid @RequestBody ConfirmActivationRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireConfirmer();
        String actId = RequestTokens.requireId("activationId", activationId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SITE_ACTIVATION_CONFIRM,
                Map.of("activationId", actId, "configVersion", request.configVersion()));
        return idempotencyService.runWrite(reqId, OP_SITE_ACTIVATION_CONFIRM, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        siteService.confirmActivation(actId, request.configVersion(),
                                actor.actorId())));
    }

    /** 查看启用申请与冻结快照（不含处理代码）；负责人与确认人可查。 */
    @GetMapping("/activations/{activationId}")
    public ActivationView getActivation(@PathVariable String activationId) {
        requireLeadOrConfirmer();
        return siteService.getActivation(RequestTokens.requireId("activationId", activationId));
    }

    // ---------------- 剩余额度与中心分配 ----------------

    /** 分配方只看各区组剩余额度，看不到配置版本与冻结总量。 */
    @GetMapping("/experiments/{experimentId}/sites/{siteId}/remaining-quota")
    public RemainingQuotaView getRemainingQuota(@PathVariable String experimentId,
                                                @PathVariable String siteId) {
        requireCoordinator();
        return siteService.getRemainingQuota(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("siteId", siteId));
    }

    /** 中心区组分配（仅 COORDINATOR）：未启用中心拒绝；额度耗尽 422。 */
    @PostMapping("/experiments/{experimentId}/sites/{siteId}/blocks/{blockCode}"
            + "/participants/{participantId}/allocations")
    public ResponseEntity<String> allocate(
            @PathVariable String experimentId,
            @PathVariable String siteId,
            @PathVariable String blockCode,
            @PathVariable String participantId,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String stId = RequestTokens.requireId("siteId", siteId);
        String blkCode = RequestTokens.requireId("blockCode", blockCode);
        String pid = RequestTokens.requireId("participantId", participantId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SITE_ALLOCATION_CREATE,
                Map.of("experimentId", expId, "siteId", stId, "blockCode", blkCode,
                        "participantId", pid));
        return idempotencyService.runWrite(reqId, OP_SITE_ALLOCATION_CREATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        siteService.allocate(expId, stId, blkCode, pid, actor.actorId())));
    }

    // ---------------- 权限辅助（先于幂等回放执行） ----------------

    private Actor requireActor() {
        Actor actor = actorContext.get();
        if (actor == null) {
            throw ApiException.unauthorized("缺少操作者身份头");
        }
        return actor;
    }

    private Actor requireLead() {
        Actor actor = requireActor();
        if (!actor.isLead()) {
            throw ApiException.forbidden("仅 LEAD（中心负责人）可执行该操作");
        }
        return actor;
    }

    private Actor requireConfirmer() {
        Actor actor = requireActor();
        if (!actor.isConfirmer()) {
            throw ApiException.forbidden("仅 CONFIRMER（独立确认角色）可执行该操作");
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

    private Actor requireLeadOrConfirmer() {
        Actor actor = requireActor();
        if (!actor.isLead() && !actor.isConfirmer()) {
            throw ApiException.forbidden("仅 LEAD 或 CONFIRMER 可查看该信息");
        }
        return actor;
    }
}
