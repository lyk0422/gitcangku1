package com.example.starter.blind.web;

import com.example.starter.blind.Actor;
import com.example.starter.blind.ActorContext;
import com.example.starter.blind.ApiException;
import com.example.starter.blind.RequestTokens;
import com.example.starter.blind.dto.AccessTokenView;
import com.example.starter.blind.dto.DataSubmissionRequest;
import com.example.starter.blind.dto.DataSubmissionView;
import com.example.starter.blind.dto.GenerationView;
import com.example.starter.blind.dto.RotationActivateRequest;
import com.example.starter.blind.dto.RotationOrderView;
import com.example.starter.blind.dto.RotationPreviewRequest;
import com.example.starter.blind.dto.RotationPreviewView;
import com.example.starter.blind.dto.RotationRosterRequest;
import com.example.starter.blind.service.AccessControlService;
import com.example.starter.blind.service.IdempotencyService;
import com.example.starter.blind.service.RotationService;
import com.example.starter.blind.service.RotationService.CanonicalRoster;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 盲态职责轮换与最小知情授权代次接口。
 *
 * <p>轮换激活为幂等写操作（X-Request-Id）：同参（名册换序视为同参）重放首次快照，
 * 异参 409，失败不占键。预览与查询只读。令牌签发与数据提交由代次令牌强制定位当前活动代次。</p>
 */
@RestController
@RequestMapping("/api")
public class RotationController {

    static final String OP_ROTATION_ACTIVATE = "rotation.activate";
    static final String HEADER_ACCESS_TOKEN = "X-Access-Token";

    private final RotationService rotationService;
    private final AccessControlService accessControlService;
    private final IdempotencyService idempotencyService;
    private final ActorContext actorContext;

    public RotationController(RotationService rotationService,
                              AccessControlService accessControlService,
                              IdempotencyService idempotencyService,
                              ActorContext actorContext) {
        this.rotationService = rotationService;
        this.accessControlService = accessControlService;
        this.idempotencyService = idempotencyService;
        this.actorContext = actorContext;
    }

    /** 只读预览：基于不可删除的知情历史计算目标名册可见范围与冲突，不写数据。 */
    @PostMapping("/experiments/{experimentId}/rotation-preview")
    public RotationPreviewView preview(
            @PathVariable String experimentId,
            @Valid @RequestBody RotationPreviewRequest request) {
        requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        return rotationService.preview(expId, request.effectiveAt(), request.roster());
    }

    /** 激活职责轮换单（仅 COORDINATOR）；整单事务，版本/名册冲突分别 409/422。 */
    @PostMapping("/experiments/{experimentId}/rotations/{rotationKey}/activate")
    public ResponseEntity<String> activate(
            @PathVariable String experimentId,
            @PathVariable String rotationKey,
            @Valid @RequestBody RotationActivateRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String key = RequestTokens.requireId("rotationKey", rotationKey);
        String reqId = RequestTokens.requireRequestId(requestId);
        // 规范化名册后再算指纹：名册换序、同类重复列举均视为同参。
        CanonicalRoster canonical = rotationService.canonicalize(request.roster());
        String fingerprint = idempotencyService.fingerprint(OP_ROTATION_ACTIVATE,
                Map.of("experimentId", expId,
                        "rotationKey", key,
                        "expectedExperimentVersion", request.expectedExperimentVersion(),
                        "effectiveAt", request.effectiveAt(),
                        "roster", rosterFingerprint(canonical)));
        return idempotencyService.runWrite(reqId, OP_ROTATION_ACTIVATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        rotationService.activate(expId, key, request, actor.actorId(), reqId)));
    }

    /** 查询轮换单（只读）：前后名册、授权代次、知情冲突依据。 */
    @GetMapping("/experiments/{experimentId}/rotations/{rotationKey}")
    public RotationOrderView getOrder(@PathVariable String experimentId,
                                      @PathVariable String rotationKey) {
        requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String key = RequestTokens.requireId("rotationKey", rotationKey);
        RotationOrderView view = rotationService.getOrder(key);
        if (!view.experimentId().equals(expId)) {
            throw ApiException.notFound("轮换单不属于该实验: " + key);
        }
        return view;
    }

    /** 查询实验当前活动授权代次（只读）。 */
    @GetMapping("/experiments/{experimentId}/generations/current")
    public GenerationView currentGeneration(@PathVariable String experimentId) {
        requireActor();
        return rotationService.getCurrentGeneration(
                RequestTokens.requireId("experimentId", experimentId));
    }

    /** 当前活动代次在册人员凭同名角色签发不透明代次令牌。 */
    @PostMapping("/experiments/{experimentId}/access-tokens")
    public AccessTokenView issueToken(@PathVariable String experimentId) {
        Actor actor = requireDutyActor();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        return accessControlService.issueToken(expId, actor.actorId(), actor.role().name());
    }

    /** 数据提交：令牌代次必须仍是当前活动代次，且受试者在该采集者最小范围内。 */
    @PostMapping("/experiments/{experimentId}/participants/{participantId}/data")
    public DataSubmissionView submitData(
            @PathVariable String experimentId,
            @PathVariable String participantId,
            @Valid @RequestBody DataSubmissionRequest request,
            @RequestHeader(value = HEADER_ACCESS_TOKEN, required = false) String accessToken) {
        Actor actor = requireDutyActor();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String pid = RequestTokens.requireId("participantId", participantId);
        return accessControlService.submit(expId, pid, accessToken, actor.actorId(),
                actor.role().name(), request.payload());
    }

    // ---------------- 权限辅助 ----------------

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
            throw ApiException.forbidden("仅实验负责人（COORDINATOR）可执行该操作");
        }
        return actor;
    }

    private Actor requireDutyActor() {
        Actor actor = requireActor();
        if (!actor.role().isDutyRole()) {
            throw ApiException.forbidden("该接口仅面向 DATA_COLLECTOR/RANDOMIZATION_CUSTODIAN/"
                    + "SAFETY_REVIEWER 职责角色");
        }
        return actor;
    }

    private Map<String, Object> rosterFingerprint(CanonicalRoster roster) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dataCollectors", roster.dataCollectors());
        map.put("randomizationCustodians", roster.randomizationCustodians());
        map.put("safetyReviewers", roster.safetyReviewers());
        return map;
    }
}
