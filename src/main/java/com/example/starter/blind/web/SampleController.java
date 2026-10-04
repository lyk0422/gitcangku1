package com.example.starter.blind.web;

import com.example.starter.blind.Actor;
import com.example.starter.blind.ActorContext;
import com.example.starter.blind.ApiException;
import com.example.starter.blind.RequestTokens;
import com.example.starter.blind.dto.AnonymousPairView;
import com.example.starter.blind.dto.CorrectSampleRequest;
import com.example.starter.blind.dto.LockSamplePairRequest;
import com.example.starter.blind.dto.RegisterSampleRequest;
import com.example.starter.blind.dto.SamplePairView;
import com.example.starter.blind.dto.SampleView;
import com.example.starter.blind.service.IdempotencyService;
import com.example.starter.blind.service.SampleService;
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
import java.util.List;
import java.util.Map;

/**
 * 盲态样本链接口：登记、更正、锁定与两类查询。
 * 所有写操作必须携带 X-Request-Id；权限校验先于幂等回放。
 */
@RestController
@RequestMapping("/api")
public class SampleController {

    static final String OP_SAMPLE_REGISTER = "sample.register";
    static final String OP_SAMPLE_CORRECT = "sample.correct";
    static final String OP_SAMPLE_LOCK = "sample.lock";

    private final SampleService sampleService;
    private final IdempotencyService idempotencyService;
    private final ActorContext actorContext;

    public SampleController(SampleService sampleService,
                            IdempotencyService idempotencyService,
                            ActorContext actorContext) {
        this.sampleService = sampleService;
        this.idempotencyService = idempotencyService;
        this.actorContext = actorContext;
    }

    /** 登记某阶段首份合成样本（仅 COORDINATOR）；允许先上传 POST 形成待配对状态。 */
    @PostMapping("/experiments/{experimentId}/participants/{participantId}/samples")
    public ResponseEntity<String> register(
            @PathVariable String experimentId,
            @PathVariable String participantId,
            @Valid @RequestBody RegisterSampleRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String pid = RequestTokens.requireId("participantId", participantId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SAMPLE_REGISTER,
                sampleParams(expId, pid, request.phase(), request.sampleCode(),
                        request.digest(), request.collectedAt()));
        return idempotencyService.runWrite(reqId, OP_SAMPLE_REGISTER, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        sampleService.register(expId, pid, request, actor.actorId())));
    }

    /** 更正样本（仅 COORDINATOR）：追加替代记录并引用最近有效样本；锁定后禁止。 */
    @PostMapping("/experiments/{experimentId}/participants/{participantId}/samples/corrections")
    public ResponseEntity<String> correct(
            @PathVariable String experimentId,
            @PathVariable String participantId,
            @Valid @RequestBody CorrectSampleRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String pid = RequestTokens.requireId("participantId", participantId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SAMPLE_CORRECT,
                sampleParams(expId, pid, request.phase(), request.sampleCode(),
                        request.digest(), request.collectedAt()));
        return idempotencyService.runWrite(reqId, OP_SAMPLE_CORRECT, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        sampleService.correct(expId, pid, request, actor.actorId())));
    }

    /** 锁定样本配对（仅 COORDINATOR）：两份齐全且 POST 晚于 PRE；退组不能新锁定。 */
    @PostMapping("/experiments/{experimentId}/participants/{participantId}/sample-pair/lock")
    public ResponseEntity<String> lock(
            @PathVariable String experimentId,
            @PathVariable String participantId,
            @RequestBody(required = false) LockSamplePairRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String pid = RequestTokens.requireId("participantId", participantId);
        String reqId = RequestTokens.requireRequestId(requestId);
        LockSamplePairRequest body = request != null ? request : new LockSamplePairRequest(null, null);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("experimentId", expId);
        params.put("participantId", pid);
        params.put("expectedPreVersion", body.expectedPreVersion());
        params.put("expectedPostVersion", body.expectedPostVersion());
        String fingerprint = idempotencyService.fingerprint(OP_SAMPLE_LOCK, params);
        return idempotencyService.runWrite(reqId, OP_SAMPLE_LOCK, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        sampleService.lock(expId, pid, body, actor.actorId())));
    }

    /** 协调员查询自己提交的样本记录；其他角色（含分析人）一律 403，不泄露参与者信息。 */
    @GetMapping("/experiments/{experimentId}/participants/{participantId}/samples")
    public List<SampleView> listOwnSamples(@PathVariable String experimentId,
                                           @PathVariable String participantId) {
        Actor actor = requireCoordinator();
        return sampleService.listOwnSamples(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("participantId", participantId),
                actor.actorId());
    }

    /** 分析查询（ANALYST / COORDINATOR）：只有配对码与两份冻结摘要。 */
    @GetMapping("/experiments/{experimentId}/sample-pairs")
    public List<AnonymousPairView> listAnonymousPairs(@PathVariable String experimentId) {
        Actor actor = requireActor();
        if (!actor.isAnalyst() && !actor.isCoordinator()) {
            throw ApiException.forbidden("仅 ANALYST 或 COORDINATOR 可查询匿名配对");
        }
        return sampleService.listAnonymousPairs(
                RequestTokens.requireId("experimentId", experimentId));
    }

    private Map<String, Object> sampleParams(String expId, String pid, String phase,
                                             String sampleCode, String digest, Long collectedAt) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("experimentId", expId);
        params.put("participantId", pid);
        params.put("phase", phase);
        params.put("sampleCode", sampleCode);
        params.put("digest", digest);
        params.put("collectedAt", collectedAt);
        return params;
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
}