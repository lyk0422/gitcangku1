package com.example.starter.evidence;

import com.example.starter.evidence.dto.CaseCustodianGrantRequest;
import com.example.starter.evidence.dto.CaseTransferChainEntryView;
import com.example.starter.evidence.dto.CaseTransferDetailView;
import com.example.starter.evidence.dto.CaseTransferDiagnosticsView;
import com.example.starter.evidence.dto.CaseTransferRequest;
import com.example.starter.evidence.dto.CaseTransferRevokeRequest;
import com.example.starter.evidence.dto.CaseTransferView;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.TransferOrderCreateRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 证物跨案移交 API。操作人通过 X-Actor-Id 请求头提供；
 * 写操作携带 commandKey（requestId）保证幂等：指纹含规范化证物列表、两案、令版本与操作者，
 * 同键同参重放返回首次快照，同键改参返回 409，失败不占键。
 */
@RestController
@RequestMapping("/api")
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
     * 案件保管人授权：新案件首名保管人可自助登记，之后仅已有保管人可追加。
     */
    @PostMapping("/case-custodians")
    public ResponseEntity<String> grantCustodian(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                 @Valid @RequestBody CaseCustodianGrantRequest request) {
        String hash = idempotencyAdvisor.hash(CaseTransferService.OP_CASE_CUSTODIAN_GRANT, actorId,
                request.caseKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> caseTransferService.grantCustodian(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 签发移交令：签发人须具备来源案件保管权限；有效期 UTC 左闭右开。
     */
    @PostMapping("/transfer-orders")
    public ResponseEntity<String> createOrder(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                              @Valid @RequestBody TransferOrderCreateRequest request) {
        String hash = idempotencyAdvisor.hash(CaseTransferService.OP_TRANSFER_ORDER_CREATE, actorId,
                request.orderVersion(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> caseTransferService.createOrder(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 撤销移交令：仅来源案件保管人；撤销后令立即失效。
     */
    @PostMapping("/transfer-orders/{orderVersion}/revoke")
    public ResponseEntity<String> revokeOrder(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                              @PathVariable String orderVersion,
                                              @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(CaseTransferService.OP_TRANSFER_ORDER_REVOKE, actorId,
                orderVersion, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> caseTransferService.revokeOrder(actorId, orderVersion, request, hash));
        return toEntity(response);
    }

    /**
     * 跨案移交：整批证物在单事务内完成，写入双案链与同一 transferId 的封存快照。
     * 证物列表先按字典序规范化排序再计入幂等指纹。
     */
    @PostMapping("/case-transfers")
    public ResponseEntity<String> transfer(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                           @Valid @RequestBody CaseTransferRequest request) {
        CaseTransferRequest normalized = normalize(request);
        String hash = idempotencyAdvisor.hash(CaseTransferService.OP_CASE_TRANSFER, actorId,
                normalized.sourceCaseKey() + "->" + normalized.targetCaseKey(), normalized);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> caseTransferService.transfer(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 撤销跨案移交：由双方不同保管人确认，追加反向链，不删除原移交。
     */
    @PostMapping("/case-transfers/{transferKey}/revoke")
    public ResponseEntity<String> revoke(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String transferKey,
                                         @Valid @RequestBody CaseTransferRevokeRequest request) {
        String hash = idempotencyAdvisor.hash(CaseTransferService.OP_CASE_TRANSFER_REVOKE, actorId,
                transferKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> caseTransferService.revoke(actorId, transferKey, request, hash));
        return toEntity(response);
    }

    /**
     * 移交明细：主表 + 双案封存快照 + 双案保管链（只读）。
     */
    @GetMapping("/case-transfers/{transferKey}")
    public CaseTransferDetailView detail(@PathVariable String transferKey) {
        return caseTransferService.detail(transferKey);
    }

    /**
     * 案件维度移交历史（只读）。
     */
    @GetMapping("/case-transfers")
    public List<CaseTransferView> listByCase(@RequestParam String caseKey) {
        return caseTransferService.listByCase(caseKey);
    }

    /**
     * 案件维度诊断：移交计数与保管人清单（只读）。
     */
    @GetMapping("/case-transfers/diagnostics")
    public CaseTransferDiagnosticsView diagnostics(@RequestParam String caseKey) {
        return caseTransferService.diagnostics(caseKey);
    }

    /**
     * 案件维度跨案保管链（只读）。
     */
    @GetMapping("/case-transfers/chain")
    public List<CaseTransferChainEntryView> chainByCase(@RequestParam String caseKey) {
        return caseTransferService.chainByCase(caseKey);
    }

    /**
     * 证物维度跨案保管链（只读）。
     */
    @GetMapping("/evidence/{evidenceKey}/case-transfer-chain")
    public List<CaseTransferChainEntryView> chainByEvidence(@PathVariable String evidenceKey) {
        return caseTransferService.chainByEvidence(evidenceKey);
    }

    /**
     * 规范化移交请求：证物列表按字典序排序，保证幂等指纹与请求顺序无关。
     */
    private CaseTransferRequest normalize(CaseTransferRequest request) {
        List<String> sorted = request.evidenceKeys().stream().sorted().toList();
        return new CaseTransferRequest(request.commandKey(), request.sourceCaseKey(),
                request.targetCaseKey(), request.orderVersion(), request.sourceCustodianId(),
                request.targetCustodianId(), sorted);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
