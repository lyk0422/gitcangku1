package com.example.starter.evidence;

import com.example.starter.evidence.dto.RegulatedCheckinRequest;
import com.example.starter.evidence.dto.RegulatedDecisionRequest;
import com.example.starter.evidence.dto.RegulatedTransferInitiateRequest;
import com.example.starter.evidence.dto.RegulatedTransferView;
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

import java.util.List;

/**
 * 受监管交接 API。操作人通过 X-Actor-Id 请求头提供；写操作携带 requestId 保证幂等：
 * 同键同参重放返回首次完整响应，同键改参返回 409，失败不占键；越权 403 且先于重放。
 */
@RestController
@RequestMapping("/api/evidence")
@Validated
public class RegulatedTransferController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final RegulatedTransferService regulatedTransferService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public RegulatedTransferController(RegulatedTransferService regulatedTransferService,
                                       IdempotencyAdvisor idempotencyAdvisor) {
        this.regulatedTransferService = regulatedTransferService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 发起受监管交接：仅当前保管人，声明 2-8 个有序检查点及每段 UTC 截止时刻。
     */
    @PostMapping("/{evidenceKey}/regulated-transfers")
    public ResponseEntity<String> initiate(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                           @PathVariable String evidenceKey,
                                           @Valid @RequestBody RegulatedTransferInitiateRequest request) {
        String hash = idempotencyAdvisor.hashStructured(RegulatedTransferService.OP_INITIATE,
                actorId, evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> regulatedTransferService.initiate(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 检查点签到：仅指定运送人，按点号顺序提交点号、封条编号与到达时刻。
     */
    @PostMapping("/{evidenceKey}/regulated-transfers/checkins")
    public ResponseEntity<String> checkin(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                          @PathVariable String evidenceKey,
                                          @Valid @RequestBody RegulatedCheckinRequest request) {
        String hash = idempotencyAdvisor.hashStructured(RegulatedTransferService.OP_CHECKIN,
                actorId, evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> regulatedTransferService.checkin(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 接收受监管交接：仅指定收件人；全部检查点正常签到完成后保管人原子切换。
     */
    @PostMapping("/{evidenceKey}/regulated-transfers/accept")
    public ResponseEntity<String> accept(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String evidenceKey,
                                         @Valid @RequestBody RegulatedDecisionRequest request) {
        String hash = idempotencyAdvisor.hashStructured(RegulatedTransferService.OP_ACCEPT,
                actorId, evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> regulatedTransferService.accept(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 取消受监管交接：仅原保管人；已记录的签到与异常保留，可重新发起新交接。
     */
    @PostMapping("/{evidenceKey}/regulated-transfers/cancel")
    public ResponseEntity<String> cancel(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String evidenceKey,
                                         @Valid @RequestBody RegulatedDecisionRequest request) {
        String hash = idempotencyAdvisor.hashStructured(RegulatedTransferService.OP_CANCEL,
                actorId, evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> regulatedTransferService.cancel(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询证物全部受监管交接（含冻结的检查点名单、签到与异常记录），按发生顺序。
     */
    @GetMapping("/{evidenceKey}/regulated-transfers")
    public List<RegulatedTransferView> listByEvidence(@PathVariable String evidenceKey) {
        return regulatedTransferService.listByEvidence(evidenceKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}