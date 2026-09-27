package com.example.starter.evidence;

import com.example.starter.evidence.dto.CaseHistoryView;
import com.example.starter.evidence.dto.CaseTransferDiagnosisView;
import com.example.starter.evidence.dto.CaseTransferRequest;
import com.example.starter.evidence.dto.CaseTransferRevokeRequest;
import com.example.starter.evidence.dto.CaseTransferView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * 证物跨案移交 API。操作者（来源案件保管人）通过 X-Actor-Id 请求头提供；
 * 写操作携带 commandKey 保证幂等：requestId 指纹含规范化证物列表、双案、令版本与操作者，
 * 同键同参重放返回首次快照，同键异参返回 409，失败不占键。
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
     * 执行整批跨案移交：同事务写来源案移出链、目标案移入链与同一 transferId 的双案封存快照。
     */
    @PostMapping
    public ResponseEntity<String> transfer(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                           @Valid @RequestBody CaseTransferRequest request) {
        CaseTransferRequest canonical = canonicalRequest(request);
        String hash = idempotencyAdvisor.hashPayload(
                CaseTransferService.OP_CASE_TRANSFER, actorId, canonical);
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
        String hash = idempotencyAdvisor.hash(CaseTransferService.OP_CASE_TRANSFER_REVOKE,
                actorId, transferId, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> caseTransferService.revoke(actorId, transferId, request, hash));
        return toEntity(response);
    }

    /**
     * 移交明细查询（只读）。
     */
    @GetMapping("/{transferId}")
    public CaseTransferView getTransfer(@PathVariable String transferId) {
        return caseTransferService.getTransfer(transferId);
    }

    /**
     * 诊断查询（只读）：按当前 UTC 时刻判定令版本区间、双方授权与每件证物前置条件，不写入任何数据。
     */
    @PostMapping("/diagnose")
    public CaseTransferDiagnosisView diagnose(@Valid @RequestBody CaseTransferRequest request) {
        return caseTransferService.diagnose(request);
    }

    /**
     * 证物跨案历史查询（只读）：证物当前状态 + 跨案移交 + 案件链事件 + 双案封存快照。
     */
    @GetMapping("/by-evidence/{evidenceKey}/history")
    public CaseHistoryView history(@PathVariable String evidenceKey) {
        return caseTransferService.history(evidenceKey);
    }

    /**
     * 指纹规范化：证物列表按 evidenceKey 排序去重，保证乱序同参请求指纹一致。
     */
    private CaseTransferRequest canonicalRequest(CaseTransferRequest request) {
        return new CaseTransferRequest(request.commandKey(), request.orderNo(), request.orderVersion(),
                request.fromCaseKey(), request.toCaseKey(), request.fromCustodian(),
                request.toCustodian(),
                request.evidenceKeys().stream().sorted().distinct().toList());
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
