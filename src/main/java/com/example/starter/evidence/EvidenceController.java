package com.example.starter.evidence;

import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.CustodyChainView;
import com.example.starter.evidence.dto.EvidenceView;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.LoanReturnRequest;
import com.example.starter.evidence.dto.LoanView;
import com.example.starter.evidence.dto.ResealApplyRequest;
import com.example.starter.evidence.dto.SealInspectionRequest;
import com.example.starter.evidence.dto.TransferInitiateRequest;
import com.example.starter.evidence.dto.TransferView;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * 限时借出：仅当前保管人，证物须 SEALED；进入 BORROWED，保管人不变。
     */
    @PostMapping("/{evidenceKey}/loans")
    public ResponseEntity<String> borrow(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String evidenceKey,
                                         @Valid @RequestBody LoanCreateRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_LOAN_BORROW, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> evidenceService.borrow(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 确认归还：仅借出时的保管人；完好回到 SEALED，异常进入 SEAL_BROKEN。
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
     * 申请双人重新封存：仅 SEAL_BROKEN 且无借出/待交接证物的当前保管人可申请。
     * 提交全局唯一 resealKey、新封条号、非空原因及不同于自己的见证人；申请不改变当前状态。
     */
    @PostMapping("/{evidenceKey}/reseals")
    public ResponseEntity<String> applyReseal(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                              @PathVariable String evidenceKey,
                                              @Valid @RequestBody ResealApplyRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_RESEAL_APPLY, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> evidenceService.applyReseal(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 见证人确认重新封存：仅申请指定见证人；原子恢复 SEALED、换用新封条并追加确认快照。
     */
    @PostMapping("/{evidenceKey}/reseals/{resealKey}/confirm")
    public ResponseEntity<String> confirmReseal(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                @PathVariable String evidenceKey,
                                                @PathVariable String resealKey,
                                                @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_RESEAL_CONFIRM, actorId,
                evidenceKey + "|" + resealKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> evidenceService.confirmReseal(actorId, evidenceKey, resealKey, request, hash));
        return toEntity(response);
    }

    /**
     * 申请人撤销重新封存：仅申请人；置 CANCELLED，不换封条、不改状态。
     */
    @PostMapping("/{evidenceKey}/reseals/{resealKey}/cancel")
    public ResponseEntity<String> cancelReseal(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                               @PathVariable String evidenceKey,
                                               @PathVariable String resealKey,
                                               @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_RESEAL_CANCEL, actorId,
                evidenceKey + "|" + resealKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> evidenceService.cancelReseal(actorId, evidenceKey, resealKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询证物双人重新封存申请历史与证物当前状态。
     */
    @GetMapping("/{evidenceKey}/reseals")
    public Map<String, Object> listReseals(@PathVariable String evidenceKey) {
        CustodyChainView chain = evidenceService.custodyChain(evidenceKey);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("evidence", chain.evidence());
        view.put("reseals", chain.reseals());
        return view;
    }

    /**
     * 按借用人查询未归还借出及逾期记录。
     */
    @GetMapping("/loans/by-borrower/{borrowerId}")
    public List<LoanView> listActiveLoans(@PathVariable String borrowerId) {
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
