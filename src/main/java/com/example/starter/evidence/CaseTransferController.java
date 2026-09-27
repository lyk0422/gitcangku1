package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CaseTransferDiagnosticsView;
import com.example.starter.evidence.dto.CaseTransferExecuteRequest;
import com.example.starter.evidence.dto.CaseTransferRevokeRequest;
import com.example.starter.evidence.dto.CaseTransferView;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.TransferOrderRegisterRequest;
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
import java.util.TreeSet;

/**
 * 证物跨案移交 API。操作人通过 X-Actor-Id 请求头提供；
 * 写操作携带幂等键（requestId/commandKey）：同键同参重放返回首次快照，同键改参返回 409，失败不占键。
 * 移交请求的证物键列表在服务端规范排序（升序去重校验）后参与指纹计算与事务处理。
 */
@RestController
@RequestMapping("/api/case-transfers")
@Validated
public class CaseTransferController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final CaseTransferService caseTransferService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public CaseTransferController(CaseTransferService caseTransferService,
                                  IdempotencyAdvisor idempotencyAdvisor) {
        this.caseTransferService = caseTransferService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 登记移交令：令版本全局唯一，初始 VALID。
     */
    @PostMapping("/orders")
    public ResponseEntity<String> registerOrder(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                @Valid @RequestBody TransferOrderRegisterRequest request) {
        String hash = idempotencyAdvisor.hash(CaseTransferService.OP_ORDER_REGISTER, actorId,
                request.orderVersion(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> caseTransferService.registerOrder(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 撤销移交令：版本失效，不可再用于新移交。
     */
    @PostMapping("/orders/{orderVersion}/revoke")
    public ResponseEntity<String> revokeOrder(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                              @PathVariable String orderVersion,
                                              @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(CaseTransferService.OP_ORDER_REVOKE, actorId,
                orderVersion, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> caseTransferService.revokeOrder(actorId, orderVersion, request, hash));
        return toEntity(response);
    }

    /**
     * 执行跨案移交：证物键规范排序后，同一事务写入双案链与同一 transferId 的封存快照。
     */
    @PostMapping
    public ResponseEntity<String> transfer(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                           @Valid @RequestBody CaseTransferExecuteRequest request) {
        CaseTransferExecuteRequest normalized = normalize(request);
        String hash = idempotencyAdvisor.hash(CaseTransferService.OP_CASE_TRANSFER, actorId,
                null, normalized);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> caseTransferService.transfer(actorId, normalized, hash));
        return toEntity(response);
    }

    /**
     * 撤销跨案移交：双方不同保管人确认，追加反向链，不删除原移交。
     */
    @PostMapping("/{transferId}/revoke")
    public ResponseEntity<String> revokeTransfer(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                 @PathVariable String transferId,
                                                 @Valid @RequestBody CaseTransferRevokeRequest request) {
        String hash = idempotencyAdvisor.hash(CaseTransferService.OP_CASE_TRANSFER_REVOKE, actorId,
                transferId, request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> caseTransferService.revokeTransfer(actorId, transferId, request, hash));
        return toEntity(response);
    }

    /**
     * 移交明细：批次头 + 全部证物快照 + 全部保管链条目。只读。
     */
    @GetMapping("/{transferId}")
    public CaseTransferView getTransfer(@PathVariable String transferId) {
        return caseTransferService.getTransfer(transferId);
    }

    /**
     * 按证物查询跨案移交历史。只读。
     */
    @GetMapping("/by-evidence/{evidenceKey}")
    public List<CaseTransferView> listByEvidence(@PathVariable String evidenceKey) {
        return caseTransferService.listByEvidence(evidenceKey);
    }

    /**
     * 按案件查询跨案移交历史（作为来源或目标）。只读。
     */
    @GetMapping("/by-case/{caseKey}")
    public List<CaseTransferView> listByCase(@PathVariable String caseKey) {
        return caseTransferService.listByCase(caseKey);
    }

    /**
     * 移交诊断：快照与双案链数量一致性。只读。
     */
    @GetMapping("/{transferId}/diagnostics")
    public CaseTransferDiagnosticsView diagnostics(@PathVariable String transferId) {
        return caseTransferService.diagnostics(transferId);
    }

    /**
     * 规范化移交请求：证物键升序排序；重复键拒绝（400）。
     */
    private CaseTransferExecuteRequest normalize(CaseTransferExecuteRequest request) {
        List<String> sorted = request.evidenceKeys().stream().sorted().toList();
        if (new TreeSet<>(sorted).size() != sorted.size()) {
            throw ApiException.badRequest("证物键列表包含重复项");
        }
        return new CaseTransferExecuteRequest(request.requestId(), request.sourceCaseKey(),
                request.targetCaseKey(), request.orderVersion(), request.sourceCustodianId(),
                request.targetCustodianId(), sorted);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
