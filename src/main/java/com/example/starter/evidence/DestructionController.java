package com.example.starter.evidence;

import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.DestructionCreateRequest;
import com.example.starter.evidence.dto.DestructionOrderView;
import com.example.starter.evidence.dto.DestructionRejectRequest;
import com.example.starter.evidence.dto.FreezeStatusView;
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
 * 证物销毁令 API。所有操作人通过 X-Actor-Id 请求头提供；
 * 写操作携带 commandKey 保证幂等：同键同参重放返回首次结果，同键改参返回 409。
 * 创建时证物集合换序视为同参（按排序后的集合计算请求指纹）。
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

    /**
     * 保管人创建 PENDING 销毁令：任一入列证物不合格整单 422 并逐件返回原因。
     */
    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                        @Valid @RequestBody DestructionCreateRequest request) {
        // 证物集合换序视为同参：指纹按排序后的证物键计算。
        List<String> canonicalKeys = request.evidenceKeys().stream().sorted().toList();
        DestructionCreateRequest canonical = new DestructionCreateRequest(
                request.commandKey(), request.destructionKey(), canonicalKeys,
                request.legalBasis(), request.destructionMethod(), request.forceIncludeBroken());
        String hash = idempotencyAdvisor.hash(DestructionService.OP_DESTRUCTION_CREATE, actorId,
                request.destructionKey(), canonical);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> destructionService.create(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 审批人同意：两名互异且不同于提交人的审批人各自同意一次，第二次同意后转 APPROVED。
     */
    @PostMapping("/{destructionKey}/approvals")
    public ResponseEntity<String> approve(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String destructionKey,
                                         @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(DestructionService.OP_DESTRUCTION_APPROVE, actorId,
                destructionKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> destructionService.approve(actorId, destructionKey, request, hash));
        return toEntity(response);
    }

    /**
     * 审批人拒绝：立即转 REJECTED 终态，证物恢复可用，拒绝原因不可改写。
     */
    @PostMapping("/{destructionKey}/rejections")
    public ResponseEntity<String> reject(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                       @PathVariable String destructionKey,
                                       @Valid @RequestBody DestructionRejectRequest request) {
        String hash = idempotencyAdvisor.hash(DestructionService.OP_DESTRUCTION_REJECT, actorId,
                destructionKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> destructionService.reject(actorId, destructionKey, request, hash));
        return toEntity(response);
    }

    /**
     * 保管人在 APPROVED 后一次提交执行；事务内重查全部证物，任一被改动整单 409 回滚。
     */
    @PostMapping("/{destructionKey}/execution")
    public ResponseEntity<String> execute(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String destructionKey,
                                         @Valid @RequestBody CommandRequest request) {
        String hash = idempotencyAdvisor.hash(DestructionService.OP_DESTRUCTION_EXECUTE, actorId,
                destructionKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> destructionService.execute(actorId, destructionKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询销毁令明细。
     */
    @GetMapping("/{destructionKey}")
    public DestructionOrderView detail(@PathVariable String destructionKey) {
        return destructionService.detail(destructionKey);
    }

    /**
     * 待审清单（全部 PENDING 销毁令）。
     */
    @GetMapping("/pending")
    public List<DestructionOrderView> pending() {
        return destructionService.pendingList();
    }

    /**
     * 查询证物冻结状态；冻结时返回冻结它的 destructionKey。
     */
    @GetMapping("/evidence/{evidenceKey}/freeze")
    public FreezeStatusView freeze(@PathVariable String evidenceKey) {
        return destructionService.freezeStatus(evidenceKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
