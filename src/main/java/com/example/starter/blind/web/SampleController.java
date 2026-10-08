package com.example.starter.blind.web;

import com.example.starter.blind.Actor;
import com.example.starter.blind.ActorContext;
import com.example.starter.blind.ApiException;
import com.example.starter.blind.RequestTokens;
import com.example.starter.blind.dto.AnalystSampleView;
import com.example.starter.blind.dto.RecordResultRequest;
import com.example.starter.blind.dto.SampleCoordinatorView;
import com.example.starter.blind.dto.SampleSummaryView;
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
import java.util.Map;

/**
 * 二级盲码交接接口：样本创建（协调员）、接收与结果录入（分析员）、闭组汇总（审阅员）。
 * 所有写操作必须携带 X-Request-Id；权限校验先于幂等回放。
 * 协调员视图不含二级码与结果值，分析员视图不含参与者与一级码。
 */
@RestController
@RequestMapping("/api")
public class SampleController {

    static final String OP_SAMPLE_CREATE = "sample.create";
    static final String OP_SAMPLE_RECEIVE = "sample.receive";
    static final String OP_SAMPLE_RESULT = "sample.result";

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

    /** 创建样本（仅 COORDINATOR）：为未退组参与者生成唯一一级码。 */
    @PostMapping("/experiments/{experimentId}/participants/{participantId}/sample")
    public ResponseEntity<String> createSample(
            @PathVariable String experimentId,
            @PathVariable String participantId,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireCoordinator();
        String expId = RequestTokens.requireId("experimentId", experimentId);
        String pid = RequestTokens.requireId("participantId", participantId);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SAMPLE_CREATE,
                Map.of("experimentId", expId, "participantId", pid));
        return idempotencyService.runWrite(reqId, OP_SAMPLE_CREATE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.CREATED.value(),
                        sampleService.createSample(expId, pid, actor.actorId())));
    }

    /** 协调员样本视图：仅一级码、接收与有结果标识。 */
    @GetMapping("/experiments/{experimentId}/participants/{participantId}/sample")
    public SampleCoordinatorView coordinatorView(@PathVariable String experimentId,
                                                 @PathVariable String participantId) {
        requireCoordinator();
        return sampleService.coordinatorView(
                RequestTokens.requireId("experimentId", experimentId),
                RequestTokens.requireId("participantId", participantId));
    }

    /** 接收样本（仅 ANALYST）：凭一级码获得本人专属二级码。 */
    @PostMapping("/samples/{primaryCode}/receipt")
    public ResponseEntity<String> receive(
            @PathVariable String primaryCode,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireAnalyst();
        String code = RequestTokens.requireId("primaryCode", primaryCode);
        String reqId = RequestTokens.requireRequestId(requestId);
        String fingerprint = idempotencyService.fingerprint(OP_SAMPLE_RECEIVE,
                Map.of("primaryCode", code));
        return idempotencyService.runWrite(reqId, OP_SAMPLE_RECEIVE, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        sampleService.receive(code, actor.actorId())));
    }

    /** 录入结果（仅 ANALYST）：仅接收本人可用二级码录入一个整数结果。 */
    @PostMapping("/samples/{secondaryCode}/result")
    public ResponseEntity<String> recordResult(
            @PathVariable String secondaryCode,
            @Valid @RequestBody RecordResultRequest request,
            @RequestHeader(IdempotencyService.HEADER_REQUEST_ID) String requestId) {
        Actor actor = requireAnalyst();
        String code = RequestTokens.requireId("secondaryCode", secondaryCode);
        String reqId = RequestTokens.requireRequestId(requestId);
        // 结构化参数：操作、资源、全参数与期望版本，字段名互不碰撞；
        // value 经 Integer 反序列化，同值十进制（10 与 10.0）指纹一致，文本原样。
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("secondaryCode", code);
        params.put("value", request.value());
        if (request.expectedVersion() != null) {
            params.put("expectedVersion", request.expectedVersion());
        }
        String fingerprint = idempotencyService.fingerprint(OP_SAMPLE_RESULT, params);
        return idempotencyService.runWrite(reqId, OP_SAMPLE_RESULT, fingerprint, actor,
                () -> IdempotencyService.WriteOutcome.of(HttpStatus.OK.value(),
                        sampleService.recordResult(code, request.value(),
                                request.expectedVersion(), actor.actorId())));
    }

    /** 分析员本人样本视图：仅二级码、状态与结果。 */
    @GetMapping("/samples/{secondaryCode}")
    public AnalystSampleView analystView(@PathVariable String secondaryCode) {
        Actor actor = requireAnalyst();
        return sampleService.analystView(
                RequestTokens.requireId("secondaryCode", secondaryCode), actor.actorId());
    }

    /** 闭组结果汇总（仅 REVIEWER）：仅份数与总和，不足 3 份 422。 */
    @GetMapping("/experiments/{experimentId}/sample-summary")
    public SampleSummaryView summary(@PathVariable String experimentId) {
        requireReviewer();
        return sampleService.summary(RequestTokens.requireId("experimentId", experimentId));
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

    private Actor requireAnalyst() {
        Actor actor = requireActor();
        if (!actor.isAnalyst()) {
            throw ApiException.forbidden("仅 ANALYST 可执行该操作");
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
