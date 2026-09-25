package com.example.starter.evidence;

import com.example.starter.evidence.dto.BatchIntakeRequest;
import com.example.starter.evidence.dto.BatchView;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.CustodyChainView;
import com.example.starter.evidence.dto.EvidenceView;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.SealInspectionRequest;
import com.example.starter.evidence.dto.TransferInitiateRequest;
import com.example.starter.evidence.dto.WeightReviewRequest;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * 批量入库：提交批次键、保管人与 1～50 件清单及一一对应的实测重量。
     * 同一事务内原子创建全部证物；差异超 5% 的项标记待复核。清单换序视为同参。
     */
    @PostMapping("/batch-intake")
    public ResponseEntity<String> batchIntake(@Valid @RequestBody BatchIntakeRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_BATCH_INTAKE,
                request.custodianId(), null, canonicalItems(request));
        StoredResponse response = idempotencyAdvisor.guard(request.intakeKey(), hash,
                () -> evidenceService.batchIntake(request, hash));
        return toEntity(response);
    }

    /**
     * 重量差异复核：仅当前保管人，携带说明写入不可变记录并关闭待复核状态；复核不可逆。
     */
    @PostMapping("/{evidenceKey}/weight-review")
    public ResponseEntity<String> reviewWeight(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                               @PathVariable String evidenceKey,
                                               @Valid @RequestBody WeightReviewRequest request) {
        String hash = idempotencyAdvisor.hash(EvidenceService.OP_WEIGHT_REVIEW, actorId,
                evidenceKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> evidenceService.reviewWeight(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 按批次键查询入库清单与差异复核状态。
     */
    @GetMapping("/batches/{intakeKey}")
    public BatchView batchView(@PathVariable String intakeKey) {
        return evidenceService.batchView(intakeKey);
    }

    /**
     * 构造批次请求的规范化清单：证物与实测重量按下标配对后按 evidenceKey 排序，
     * 保证清单换序得到相同请求指纹（同参）。
     */
    private List<Map<String, Object>> canonicalItems(BatchIntakeRequest request) {
        List<BatchIntakeRequest.BatchIntakeItem> items =
                request.items() == null ? List.of() : request.items();
        List<BigDecimal> weights =
                request.measuredWeights() == null ? List.of() : request.measuredWeights();
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            BatchIntakeRequest.BatchIntakeItem item = items.get(i);
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("evidenceKey", item == null ? null : item.evidenceKey());
            pair.put("description", item == null ? null : item.description());
            pair.put("declaredWeight", item == null || item.declaredWeight() == null
                    ? null : item.declaredWeight().stripTrailingZeros());
            pair.put("measuredWeight", i < weights.size() && weights.get(i) != null
                    ? weights.get(i).stripTrailingZeros() : null);
            pairs.add(pair);
        }
        pairs.sort(Comparator.comparing(pair -> String.valueOf(pair.get("evidenceKey"))));
        return pairs;
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
