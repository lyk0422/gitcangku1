package com.example.starter.evidence;

import com.example.starter.evidence.dto.CaseTransferChainView;
import com.example.starter.evidence.dto.CaseTransferDetailView;
import com.example.starter.evidence.dto.CaseTransferItemView;
import com.example.starter.evidence.dto.CaseTransferRequest;
import com.example.starter.evidence.dto.CaseTransferRevokeRequest;
import com.example.starter.evidence.dto.TransferOrderRequest;
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
import java.util.stream.Collectors;

/**
 * 证物跨案移交 API。写操作携带 commandKey 保证幂等：
 * 指纹含规范化证物列表、两案、令版本与操作者；同键同参重放首次快照，同键改参 409，失败不占键。
 */
@RestController
@RequestMapping("/api/case-transfers")
@Validated
public class CaseTransferController {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    private final CaseTransferService caseTransferService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public CaseTransferController(CaseTransferService caseTransferService,
                                  IdempotencyAdvisor idempotencyAdvisor) {
        this.caseTransferService = caseTransferService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 登记移交令：令版本全局唯一，有效期按 UTC 左闭右开解释。
     */
    @PostMapping("/orders")
    public ResponseEntity<String> registerOrder(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                @Valid @RequestBody TransferOrderRequest request) {
        String hash = idempotencyAdvisor.hash(CaseTransferService.OP_ORDER_REGISTER, actorId,
                request.orderVersion(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> caseTransferService.registerOrder(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 执行跨案移交：批量证物规范排序后在同一事务内写入双案链与封存快照。
     */
    @PostMapping
    public ResponseEntity<String> transfer(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                           @Valid @RequestBody CaseTransferRequest request) {
        // 指纹使用规范化（排序后）证物列表，保证同批不同顺序的请求重放首次结果。
        List<String> sortedKeys = request.evidenceKeys().stream().sorted().toList();
        CaseTransferRequest normalized = new CaseTransferRequest(request.commandKey(),
                request.sourceCaseKey(), request.targetCaseKey(), request.orderVersion(),
                request.sourceCustodian(), request.targetCustodian(), sortedKeys);
        String canonical = sortedKeys.stream().collect(Collectors.joining(","));
        String hash = idempotencyAdvisor.hash(CaseTransferService.OP_CASE_TRANSFER, actorId,
                canonical, normalized);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> caseTransferService.transfer(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 撤销跨案移交：双方不同保管人确认，追加反向链，不删除原移交。
     */
    @PostMapping("/{transferId}/revoke")
    public ResponseEntity<String> revoke(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String transferId,
                                         @Valid @RequestBody CaseTransferRevokeRequest request) {
        String hash = idempotencyAdvisor.hash(CaseTransferService.OP_CASE_TRANSFER_REVOKE, actorId,
                transferId, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> caseTransferService.revoke(actorId, transferId, request, hash));
        return toEntity(response);
    }

    /**
     * 查询跨案移交明细：移交头 + 双案封存快照 + 双案链条目。
     */
    @GetMapping("/{transferId}")
    public CaseTransferDetailView detail(@PathVariable String transferId) {
        return caseTransferService.detail(transferId);
    }

    /**
     * 按案件查询双案保管链历史（只读链证据）。
     */
    @GetMapping("/by-case/{caseKey}")
    public List<CaseTransferChainView> chainByCase(@PathVariable String caseKey) {
        return caseTransferService.chainByCase(caseKey);
    }

    /**
     * 按证物查询跨案移交历史（全部封存快照）。
     */
    @GetMapping("/by-evidence/{evidenceKey}")
    public List<CaseTransferItemView> historyByEvidence(@PathVariable String evidenceKey) {
        return caseTransferService.historyByEvidence(evidenceKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
