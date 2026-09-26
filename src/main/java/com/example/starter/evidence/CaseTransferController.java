package com.example.starter.evidence;

import com.example.starter.evidence.dto.CaseTransferDiagView;
import com.example.starter.evidence.dto.CaseTransferRequest;
import com.example.starter.evidence.dto.CaseTransferRevokeRequest;
import com.example.starter.evidence.dto.CaseTransferView;
import com.example.starter.evidence.dto.CustodianGrantRequest;
import com.example.starter.evidence.dto.CustodyCaseLinkView;
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
import java.util.Map;

/**
 * 证物跨案移交 API。一次请求整批证物在同一事务内双案一致封存；
 * 写操作携带 commandKey（requestId）保证幂等：同键同参重放返回首次快照，同键改参返回 409，
 * 失败请求不占用幂等键。
 */
@RestController
@RequestMapping("/api/evidence")
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
     * 跨案移交：整批证物从来源案件移交到目标案件，同一事务写入双案链与封存快照。
     */
    @PostMapping("/case-transfers")
    public ResponseEntity<String> caseTransfer(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                               @Valid @RequestBody CaseTransferRequest request) {
        String hash = idempotencyAdvisor.hash(CaseTransferService.OP_CASE_TRANSFER, actorId,
                null, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> caseTransferService.caseTransfer(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 撤销跨案移交：仅限目标案件尚未发生后续交接；双方不同保管人确认，追加反向链。
     */
    @PostMapping("/case-transfers/{transferId}/revoke")
    public ResponseEntity<String> revokeCaseTransfer(
            @RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
            @PathVariable String transferId,
            @Valid @RequestBody CaseTransferRevokeRequest request) {
        String hash = idempotencyAdvisor.hash(CaseTransferService.OP_CASE_TRANSFER_REVOKE,
                actorId, transferId, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> caseTransferService.revokeCaseTransfer(actorId, transferId, request, hash));
        return toEntity(response);
    }

    /**
     * 明细查询：批次与双案封存快照。只读。
     */
    @GetMapping("/case-transfers/{transferId}")
    public CaseTransferView getTransfer(@PathVariable String transferId) {
        return caseTransferService.getTransfer(transferId);
    }

    /**
     * 诊断查询：实际数量、令版本有效性与剩余秒数、双案链事件条数。只读。
     */
    @GetMapping("/case-transfers/{transferId}/diagnostics")
    public CaseTransferDiagView diagnose(@PathVariable String transferId) {
        return caseTransferService.diagnose(transferId);
    }

    /**
     * 历史查询：案件相关（作为来源或目标）的全部跨案移交。只读。
     */
    @GetMapping("/cases/{caseKey}/case-transfers")
    public List<CaseTransferView> listByCase(@PathVariable String caseKey) {
        return caseTransferService.listByCase(caseKey);
    }

    /**
     * 案件跨案保管链事件历史（移出/移入/撤销反向链）。只读。
     */
    @GetMapping("/cases/{caseKey}/case-links")
    public List<CustodyCaseLinkView> listCaseLinks(@PathVariable String caseKey) {
        return caseTransferService.listCaseLinks(caseKey);
    }

    /**
     * 证物跨案保管链事件历史。只读。
     */
    @GetMapping("/{evidenceKey}/case-links")
    public List<CustodyCaseLinkView> listEvidenceCaseLinks(@PathVariable String evidenceKey) {
        return caseTransferService.listEvidenceCaseLinks(evidenceKey);
    }

    /**
     * 登记/续期移交令版本（合成数据管理入口）。有效期 UTC 左闭右开。
     */
    @PostMapping("/transfer-orders")
    public ResponseEntity<Map<String, String>> registerOrder(
            @Valid @RequestBody TransferOrderRegisterRequest request) {
        caseTransferService.registerOrder(request.orderVersion(), request.validFrom(),
                request.validTo());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("orderVersion", request.orderVersion()));
    }

    /**
     * 授权案件保管人（合成数据管理入口）。
     */
    @PostMapping("/cases/{caseKey}/custodians")
    public ResponseEntity<Map<String, String>> grantCustodian(
            @PathVariable String caseKey,
            @Valid @RequestBody CustodianGrantRequest request) {
        caseTransferService.grantCustodian(caseKey, request.custodianId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("caseKey", caseKey, "custodianId", request.custodianId()));
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
