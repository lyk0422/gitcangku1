package com.example.starter.blind.web;

import com.example.starter.blind.Actor;
import com.example.starter.blind.ActorContext;
import com.example.starter.blind.ApiException;
import com.example.starter.blind.RequestTokens;
import com.example.starter.blind.dto.KitRegisterRequest;
import com.example.starter.blind.dto.KitView;
import com.example.starter.blind.service.IdempotencyService;
import com.example.starter.blind.service.KitService;
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
 * 处理包供应与领取接口。写操作必须携带 X-Request-Id；权限校验先于幂等回放。
 * 任何响应与日志均不含处理代码、匹配数量或可解码编号。
 */
@RestController
@RequestMapping("/api")
public class KitController {

    static final String OP_KIT_REGISTER = "kit.register";
    static final String OP_KIT_PICKUP = "kit.pickup";

    private final KitService kitService;
    private final IdempotencyService idempotencyService;
    private final ActorContext actorContext;

    public KitController(KitService kitService,
                         IdempotencyService idempotencyService,
                         ActorContext actorContext) {
        this.kitService = kitService;
        this.idempotencyService = idempotencyService;
        this.actorContext = actorContext;
    }

    /** 登记处理包（仅 SUPPLY）；处理代码只落库，响应不回显。 */
    @PostMapping("/experiments/{experimentId}/kits")
    public ResponseEntity<String> registerKit(
            @PathVariable String experimentId,
            @Valid @RequestBody KitRegisterRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireSupply();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_KIT_REGISTER,
                Map.of("experimentId", expId,
                        "packageNo", request.packageNo(),
                        "treatment", request.treatment()));
        return idempotencyService.runWrite(reqId, OP_KIT_REGISTER, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        kitService.registerKit(expId, request.packageNo(), request.treatment(),
                                actor.actorId())));
    }

    /** 查询处理包发放状态（仅 SUPPLY）；只返回包编号与发放状态。 */
    @GetMapping("/experiments/{experimentId}/kits/{packageNo}")
    public KitView getKit(@PathVariable String experimentId,
                          @PathVariable String packageNo) {
        requireSupply();
        return kitService.getKit(RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("packageNo", packageNo));
    }

    /** 为参与者领取处理包（仅 COORDINATOR）；响应只含随机包装码与领取编号。 */
    @PostMapping("/experiments/{experimentId}/participants/{participantId}/kit-pickups")
    public ResponseEntity<String> pickup(
            @PathVariable String experimentId,
            @PathVariable String participantId,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String pid = RequestTokens.requireId("participantId", participantId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_KIT_PICKUP,
                Map.of("experimentId", expId, "participantId", pid));
        return idempotencyService.runWrite(reqId, OP_KIT_PICKUP, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        kitService.pickup(expId, pid, actor.actorId())));
    }

    // ---------------- 权限辅助（先于幂等回放执行） ----------------

    private Actor requireActor() {
        Actor actor = actorContext.get();
        if (actor == null) {
            throw ApiException.unauthorized("缺少操作者身份头");
        }
        return actor;
    }

    private Actor requireSupply() {
        Actor actor = requireActor();
        if (!actor.isSupply()) {
            throw ApiException.forbidden("仅 SUPPLY 可执行该操作");
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