package com.example.starter.blind.web;

import com.example.starter.blind.Actor;
import com.example.starter.blind.ActorContext;
import com.example.starter.blind.ApiException;
import com.example.starter.blind.RequestTokens;
import com.example.starter.blind.dto.KitStatusView;
import com.example.starter.blind.dto.RegisterKitRequest;
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
 * 处理包发放接口：SUPPLY 登记与供应查询，协调员领取。
 * 所有写操作必须携带 X-Request-Id；权限校验先于幂等回放。
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

    /** SUPPLY 登记处理包（含 A/B 代码）。 */
    @PostMapping("/experiments/{experimentId}/kits")
    public ResponseEntity<String> registerKit(
            @PathVariable String experimentId,
            @Valid @RequestBody RegisterKitRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireSupply();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_KIT_REGISTER,
                Map.of("experimentId", expId,
                        "kitNo", request.kitNo(),
                        "treatment", request.treatment()));
        return idempotencyService.runWrite(reqId, OP_KIT_REGISTER, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        kitService.registerKit(expId, request.kitNo(), request.treatment(),
                                actor.actorId())));
    }

    /** SUPPLY 供应查询：只返回包的发放状态。 */
    @GetMapping("/experiments/{experimentId}/kits/{kitNo}")
    public KitStatusView getKitStatus(@PathVariable String experimentId,
                                      @PathVariable String kitNo) {
        requireSupply();
        return kitService.getKitStatus(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("kitNo", kitNo));
    }

    /** 协调员为参与者领取处理包：只回随机包装码与领取ID。 */
    @PostMapping("/experiments/{experimentId}/participants/{participantId}/kit-pickup")
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