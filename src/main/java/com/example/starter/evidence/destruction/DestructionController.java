package com.example.starter.evidence.destruction;

import com.example.starter.evidence.IdempotencyAdvisor;
import com.example.starter.evidence.StoredResponse;
import com.example.starter.evidence.destruction.dto.DestructionAgreeRequest;
import com.example.starter.evidence.destruction.dto.DestructionCreateRequest;
import com.example.starter.evidence.destruction.dto.DestructionExecuteRequest;
import com.example.starter.evidence.destruction.dto.DestructionOrderView;
import com.example.starter.evidence.destruction.dto.DestructionRejectRequest;
import com.example.starter.evidence.destruction.dto.EvidenceFreezeView;
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
 * 销毁令 API 骨架。
 */
@RestController
@RequestMapping("/api/destruction-orders")
@Validated
public class DestructionController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final DestructionService destructionService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public DestructionController(DestructionService destructionService,
                                 IdempotencyAdvisor idempotencyAdvisor) {
        this.destructionService = destructionService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @Valid @RequestBody DestructionCreateRequest request) {
        // 幂等指纹对入列证物集合归一化：集合换序视为同参。
        List<String> sortedEvidenceKeys = request.evidenceKeys().stream().sorted().toList();
        DestructionCreateRequest canonical = new DestructionCreateRequest(
                request.commandKey(), request.destructionKey(), sortedEvidenceKeys,
                request.legalBasis(), request.destructionMethod(), request.forceIncludeBroken());
        String hash = idempotencyAdvisor.hash(DestructionService.OP_DESTRUCTION_CREATE, actorId,
                request.destructionKey(), canonical);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> destructionService.create(actorId, request, hash));
        return toEntity(response);
    }

    @PostMapping("/{destructionKey}/agree")
    public ResponseEntity<String> agree(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                        @PathVariable String destructionKey,
                                        @Valid @RequestBody DestructionAgreeRequest request) {
        String hash = idempotencyAdvisor.hash(DestructionService.OP_DESTRUCTION_APPROVE, actorId,
                destructionKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> destructionService.agree(actorId, destructionKey, request, hash));
        return toEntity(response);
    }

    @PostMapping("/{destructionKey}/reject")
    public ResponseEntity<String> reject(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String destructionKey,
                                         @Valid @RequestBody DestructionRejectRequest request) {
        String hash = idempotencyAdvisor.hash(DestructionService.OP_DESTRUCTION_REJECT, actorId,
                destructionKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> destructionService.reject(actorId, destructionKey, request, hash));
        return toEntity(response);
    }

    @PostMapping("/{destructionKey}/execute")
    public ResponseEntity<String> execute(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                          @PathVariable String destructionKey,
                                          @Valid @RequestBody DestructionExecuteRequest request) {
        String hash = idempotencyAdvisor.hash(DestructionService.OP_DESTRUCTION_EXECUTE, actorId,
                destructionKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> destructionService.execute(actorId, destructionKey, request, hash));
        return toEntity(response);
    }

    @GetMapping("/{destructionKey}")
    public DestructionOrderView getOrder(@PathVariable String destructionKey) {
        return destructionService.getOrder(destructionKey);
    }

    @GetMapping("/pending")
    public List<DestructionOrderView> listPending() {
        return destructionService.listPending();
    }

    @GetMapping("/evidence/{evidenceKey}/freeze")
    public EvidenceFreezeView freezeStatus(@PathVariable String evidenceKey) {
        return destructionService.freezeStatus(evidenceKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
