package com.example.starter.evidence;

import com.example.starter.evidence.dto.CaseTransferInitiateRequest;
import com.example.starter.evidence.dto.CaseTransferRevokeRequest;
import com.example.starter.evidence.dto.CaseTransferView;
import com.example.starter.evidence.dto.ChainEventView;
import com.example.starter.evidence.dto.SnapshotView;
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
 * 证物跨案移交 API。操作人通过 X-Actor-Id 请求头提供；写操作携带 commandKey（requestId）保证幂等：
 * 指纹含规范化（去重排序）证物列表、双方案件、令版本与操作者；同键同参重放返回首次快照，同键异参 409，
 * 业务失败不写入命令日志（不占键）。读取类接口不改变任何状态。
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
     * 发起跨案移交：同事务写来源案移出链、目标案移入链与同一 transferId 的双案封存快照。
     */
    /**
     * 将请求中的证物列表去重并按证物键规范化排序，其余字段保持不变；
     * 幂等指纹基于规范化后的请求，保证仅顺序不同的提交视为同参。
     */
    public static CaseTransferInitiateRequest canonicalize(CaseTransferInitiateRequest request) {
        List<String> normalizedKeys = request.evidenceKeys().stream().distinct().sorted().toList();
        return new CaseTransferInitiateRequest(
                request.commandKey(), request.fromCaseKey(), request.toCaseKey(),
                request.orderKey(), request.orderVersion(), request.fromCustodian(),
                request.toCustodian(), normalizedKeys);
    }

    @PostMapping
    public ResponseEntity<String> initiate(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                           @Valid @RequestBody CaseTransferInitiateRequest request) {
        CaseTransferInitiateRequest canonical = canonicalize(request);
        String scope = request.fromCaseKey() + ">" + request.toCaseKey();
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_CASE_TRANSFER_INITIATE, actorId,
                scope, canonical);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> caseTransferService.initiate(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 撤销跨案移交：双方不同保管人确认，仅追加反向链，不删除原移交。
     */
    @PostMapping("/{transferId}/revoke")
    public ResponseEntity<String> revoke(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable long transferId,
                                         @Valid @RequestBody CaseTransferRevokeRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_CASE_TRANSFER_REVOKE, actorId,
                String.valueOf(transferId), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> caseTransferService.revoke(actorId, transferId, request, hash));
        return toEntity(response);
    }

    /**
     * 移交明细：主单、规范化排序证物及要求/实际数量；只读。
     */
    @GetMapping("/{transferId}")
    public CaseTransferView getTransfer(@PathVariable long transferId) {
        return caseTransferService.getTransfer(transferId);
    }

    /**
     * 双案封存快照明细；只读。
     */
    @GetMapping("/{transferId}/snapshots")
    public List<SnapshotView> listSnapshots(@PathVariable long transferId) {
        return caseTransferService.listSnapshots(transferId);
    }

    /**
     * 撤销诊断：逐件证物的当前案件/状态/保管人与阻塞原因，及可撤销数、要求数、差额；只读。
     */
    @GetMapping("/{transferId}/diagnostics")
    public CaseTransferService.TransferDiagnostics diagnose(@PathVariable long transferId) {
        return caseTransferService.diagnose(transferId);
    }

    /**
     * 案件保管链历史（可按证物过滤），按提交顺序；只读。
     */
    @GetMapping("/cases/{caseKey}/events")
    public List<ChainEventView> listCaseEvents(@PathVariable String caseKey,
                                               @RequestParam(required = false) String evidenceKey) {
        return caseTransferService.listCaseEvents(caseKey, evidenceKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
