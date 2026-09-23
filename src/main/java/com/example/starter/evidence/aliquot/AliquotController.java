package com.example.starter.evidence.aliquot;

import com.example.starter.evidence.IdempotencyAdvisor;
import com.example.starter.evidence.StoredResponse;
import com.example.starter.evidence.aliquot.dto.MotherRegisterRequest;
import com.example.starter.evidence.aliquot.dto.MotherSampleView;
import com.example.starter.evidence.aliquot.dto.SamplingApplyRequest;
import com.example.starter.evidence.aliquot.dto.SamplingCancelRequest;
import com.example.starter.evidence.aliquot.dto.SamplingOrderView;
import com.example.starter.evidence.aliquot.dto.SamplingReviewRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多母样联合取样 API。操作人通过 X-Actor-Id 提供；写操作携带 commandKey 幂等，
 * 申请另以 requestId 做同参集合换序重放与异参 409 判定。
 */
@RestController
@RequestMapping("/api")
@Validated
public class AliquotController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final AliquotService aliquotService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public AliquotController(AliquotService aliquotService, IdempotencyAdvisor idempotencyAdvisor) {
        this.aliquotService = aliquotService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 母样首次参与联合取样前登记不可修改的正整数总量与单位，仅当前保管人可登记。
     */
    @PostMapping("/evidence/{sampleKey}/mother-register")
    public ResponseEntity<String> registerMother(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                 @PathVariable String sampleKey,
                                                 @Valid @RequestBody MotherRegisterRequest request) {
        String hash = idempotencyAdvisor.hash(AliquotService.OP_MOTHER_REGISTER, actorId,
                sampleKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> aliquotService.registerMother(actorId, sampleKey, request, hash));
        return toEntity(response);
    }

    /**
     * 联合取样申请：从 2~20 件不同母样各取正整数数量，原子预留，整单成功或整单无预留。
     */
    @PostMapping("/sampling")
    public ResponseEntity<String> apply(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                        @Valid @RequestBody SamplingApplyRequest request) {
        String hash = idempotencyAdvisor.hash(AliquotService.OP_SAMPLING_APPLY, actorId,
                request.requestId(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> aliquotService.apply(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 审核确认：两名不同实验审核人按顺序各调用一次；第二次须携带申请版本与全部母样版本。
     */
    @PostMapping("/sampling/{requestId}/confirm")
    public ResponseEntity<String> confirm(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                          @PathVariable String requestId,
                                          @Valid @RequestBody SamplingReviewRequest request) {
        String hash = idempotencyAdvisor.hash(AliquotService.OP_SAMPLING_CONFIRM, actorId,
                requestId, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> aliquotService.confirm(actorId, requestId, request, hash));
        return toEntity(response);
    }

    /**
     * 审核拒绝：审核人（不得是任一母样当前保管人）终止申请，一次释放全部预留。
     */
    @PostMapping("/sampling/{requestId}/reject")
    public ResponseEntity<String> reject(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String requestId,
                                         @Valid @RequestBody SamplingReviewRequest request) {
        String hash = idempotencyAdvisor.hash(AliquotService.OP_SAMPLING_REJECT, actorId,
                requestId, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> aliquotService.reject(actorId, requestId, request, hash));
        return toEntity(response);
    }

    /**
     * 审核前取消：仅申请保管人可在首次确认前取消，一次释放全部预留。
     */
    @PostMapping("/sampling/{requestId}/cancel")
    public ResponseEntity<String> cancel(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String requestId,
                                         @Valid @RequestBody SamplingCancelRequest request) {
        String hash = idempotencyAdvisor.hash(AliquotService.OP_SAMPLING_CANCEL, actorId,
                requestId, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> aliquotService.cancel(actorId, requestId, request, hash));
        return toEntity(response);
    }

    /**
     * 查询母样余额、预留、耗用与当前版本（只读）。
     */
    @GetMapping("/evidence/{sampleKey}/mother")
    public MotherSampleView mother(@PathVariable String sampleKey) {
        return aliquotService.motherView(sampleKey);
    }

    /**
     * 查询联合取样单：明细、审核历史与成功后的不可变映射（只读）。
     */
    @GetMapping("/sampling/{requestId}")
    public SamplingOrderView order(@PathVariable String requestId) {
        return aliquotService.orderView(requestId);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
