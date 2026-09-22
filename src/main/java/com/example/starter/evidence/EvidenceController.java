package com.example.starter.evidence;

import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.CustodyChainView;
import com.example.starter.evidence.dto.EvidenceView;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.LoanReturnRequest;
import com.example.starter.evidence.dto.LoanView;
import com.example.starter.evidence.dto.SealInspectionRequest;
import com.example.starter.evidence.dto.TransferInitiateRequest;
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
 * 证物封存交接 API。所有操作人通过 X-Actor-Id 请求头提供；
 * 写操作携带 commandKey 保证幂等：同键同参重放返回首次结果，同键改参返回 409。
 */
@RestController
@RequestMapping("/api/evidence")
@Validated
public class EvidenceController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final EvidenceService evidenceService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public EvidenceController(EvidenceService evidenceService, IdempotencyAdvisor idempotencyAdvisor) {
        this.evidenceService = evidenceService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 证物入库：初始 SEALED，保管人为操作人。
     */
    @PostMapping
    public ResponseEntity<String> intake(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @Valid @RequestBody IntakeRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_INTAKE, actorId,
                request.evidenceKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> evidenceService.intake(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 发起交接：仅当前保管人，指定一名不同接收人，进入 TRANSFER_PENDING。
     */
    @PostMapping("/{evidenceKey}/transfers")
    public ResponseEntity<String> initiateTransfer(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                   @PathVariable String evidenceKey,
                                                   @Valid @RequestBody TransferInitiateRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_TRANSFER_INITIATE, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> evidenceService.initiateTransfer(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 接受交接：仅指定接收人，保管人原子切换并回到 SEALED。
     */
    @PostMapping("/{evidenceKey}/transfers/accept")
    public ResponseEntity<String> acceptTransfer(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                 @PathVariable String evidenceKey,
                                                 @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_TRANSFER_ACCEPT, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> evidenceService.acceptTransfer(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 取消交接：仅原保管人，证物回到 SEALED。
     */
    @PostMapping("/{evidenceKey}/transfers/cancel")
    public ResponseEntity<String> cancelTransfer(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                 @PathVariable String evidenceKey,
                                                 @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_TRANSFER_CANCEL, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> evidenceService.cancelTransfer(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 封条核验：仅当前保管人；通过仅追加记录，失败进入 SEAL_BROKEN。
     */
    @PostMapping("/{evidenceKey}/seal-inspections")
    public ResponseEntity<String> inspectSeal(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                              @PathVariable String evidenceKey,
                                              @Valid @RequestBody SealInspectionRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_SEAL_INSPECTION, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> evidenceService.inspectSeal(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 限时借出：仅当前保管人，证物须 SEALED；成功后进入 BORROWED，保管人不变。
     */
    @PostMapping("/{evidenceKey}/loans")
    public ResponseEntity<String> createLoan(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                             @PathVariable String evidenceKey,
                                             @Valid @RequestBody LoanCreateRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_LOAN_CREATE, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> evidenceService.createLoan(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 确认归还：仅当前保管人，必须指定本次 loanKey；完好回 SEALED，异常进 SEAL_BROKEN。
     */
    @PostMapping("/{evidenceKey}/loans/return")
    public ResponseEntity<String> returnLoan(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                             @PathVariable String evidenceKey,
                                             @Valid @RequestBody LoanReturnRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_LOAN_RETURN, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> evidenceService.returnLoan(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 按借用人查询未归还借出记录（逾期仅体现为查询标识 overdue）。
     */
    @GetMapping("/loans")
    public List<LoanView> listActiveLoansByBorrower(
            @RequestParam("borrowerId") @NotBlank String borrowerId) {
        return evidenceService.listActiveLoansByBorrower(borrowerId);
    }

    /**
     * 查询当前操作人可交接的证物（本人保管且 SEALED）。
     */
    @GetMapping("/transferable")
    public List<EvidenceView> listTransferable(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId) {
        return evidenceService.listTransferable(actorId);
    }

    /**
     * 查询完整保管链：当前状态 + 全部交接与核验记录。
     */
    @GetMapping("/{evidenceKey}/custody-chain")
    public CustodyChainView custodyChain(@PathVariable String evidenceKey) {
        return evidenceService.custodyChain(evidenceKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
