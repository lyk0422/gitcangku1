package com.example.starter.evidence;

import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.ContainerCreateRequest;
import com.example.starter.evidence.dto.ContainerInspectionRequest;
import com.example.starter.evidence.dto.ContainerInspectionView;
import com.example.starter.evidence.dto.ContainerPlaceRequest;
import com.example.starter.evidence.dto.ContainerView;
import com.example.starter.evidence.dto.CustodyChainView;
import com.example.starter.evidence.dto.EvidenceView;
import com.example.starter.evidence.dto.InspectionReviewRequest;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.LoanReturnRequest;
import com.example.starter.evidence.dto.LoanView;
import com.example.starter.evidence.dto.SealInspectionRequest;
import com.example.starter.evidence.dto.SealReplaceRequest;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 证物封存交接 API。所有操作人通过 X-Actor-Id 请求头提供；
 * 写操作携带 commandKey 保证幂等：同键同参重放返回首次结果，同键改参返回 409。
 * 容器巡检双人确认例外：两名见证人复用请求体中的 requestId，由领域层保证重放语义。
 */
@RestController
@RequestMapping("/api")
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
    @PostMapping("/evidence")
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
    @PostMapping("/evidence/{evidenceKey}/transfers")
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
    @PostMapping("/evidence/{evidenceKey}/transfers/accept")
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
    @PostMapping("/evidence/{evidenceKey}/transfers/cancel")
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
    @PostMapping("/evidence/{evidenceKey}/seal-inspections")
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
    @PostMapping("/evidence/{evidenceKey}/loans")
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
    @PostMapping("/evidence/{evidenceKey}/loans/return")
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
     * 证物放入容器：仅当前保管人，证物 SEALED、容器 ACTIVE。
     */
    @PostMapping("/evidence/{evidenceKey}/container-placement")
    public ResponseEntity<String> placeIntoContainer(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                     @PathVariable String evidenceKey,
                                                     @Valid @RequestBody ContainerPlaceRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_CONTAINER_PLACE, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> evidenceService.placeIntoContainer(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 登记容器：初始封条版本 1、状态 ACTIVE，保管人为操作人。
     */
    @PostMapping("/containers")
    public ResponseEntity<String> createContainer(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                  @Valid @RequestBody ContainerCreateRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_CONTAINER_CREATE, actorId,
                request.containerKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> evidenceService.createContainer(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 更换封条：仅容器登记保管人，容器须 ACTIVE；封条版本递增 1。
     */
    @PostMapping("/containers/{containerKey}/seal-replacements")
    public ResponseEntity<String> replaceSeal(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                              @PathVariable String containerKey,
                                              @Valid @RequestBody SealReplaceRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_SEAL_REPLACE, actorId,
                containerKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> evidenceService.replaceSeal(actorId, containerKey, request, hash));
        return toEntity(response);
    }

    /**
     * 容器巡检见证：两名不同保管人凭同一 requestId 确认同一封条版本与结论；
     * 异常双人确认把当时仍在箱内的全部证物一次隔离。requestId 重放返回首次隔离清单，换封条结果 409。
     */
    @PostMapping("/containers/{containerKey}/inspections")
    public ResponseEntity<String> inspectContainer(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                   @PathVariable String containerKey,
                                                   @Valid @RequestBody ContainerInspectionRequest request) {
        StoredResponse response = evidenceService.inspectContainer(actorId, containerKey, request);
        return toEntity(response);
    }

    /**
     * 复核放行：仅异常双人确认巡检可复核；放行后历史与带时刻结论保留。
     */
    @PostMapping("/containers/inspections/{requestId}/review")
    public ResponseEntity<String> reviewInspection(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                                   @PathVariable String requestId,
                                                   @Valid @RequestBody InspectionReviewRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_INSPECTION_REVIEW, actorId,
                requestId, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> evidenceService.reviewInspection(actorId, requestId, request, hash));
        return toEntity(response);
    }

    /**
     * 查询容器详情（含封条版本与隔离状态）。
     */
    @GetMapping("/containers/{containerKey}")
    public ContainerView getContainer(@PathVariable String containerKey) {
        return evidenceService.containerView(containerKey);
    }

    /**
     * 按 requestId 查询巡检：巡检人、封条版本与结论、当时箱内清单及每件隔离状态、复核结论。
     */
    @GetMapping("/containers/inspections/{requestId}")
    public ContainerInspectionView getInspection(@PathVariable String requestId) {
        return evidenceService.inspectionView(requestId);
    }

    /**
     * 按借用人查询未归还借出及逾期记录。
     */
    @GetMapping("/evidence/loans/by-borrower/{borrowerId}")
    public List<LoanView> listActiveLoans(@PathVariable String borrowerId) {
        return evidenceService.listActiveLoansByBorrower(borrowerId);
    }

    /**
     * 查询当前操作人可交接的证物（本人保管且 SEALED）。
     */
    @GetMapping("/evidence/transferable")
    public List<EvidenceView> listTransferable(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId) {
        return evidenceService.listTransferable(actorId);
    }

    /**
     * 查询完整保管链：当前状态 + 位置轨迹 + 全部交接、借出与核验记录。
     */
    @GetMapping("/evidence/{evidenceKey}/custody-chain")
    public CustodyChainView custodyChain(@PathVariable String evidenceKey) {
        return evidenceService.custodyChain(evidenceKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
