package com.example.starter.evidence;

import com.example.starter.evidence.dto.ChunkAppendRequest;
import com.example.starter.evidence.dto.ChunkCommandRequest;
import com.example.starter.evidence.dto.ChunkIntakeCreateRequest;
import com.example.starter.evidence.dto.ChunkIntakeManifestView;
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

/**
 * 分块入库 API：数字证物先接齐字节再进入封存链。
 * 写操作携带 commandKey（全局 requestId）保证幂等：同键同参重放返回首次结果，同键改参返回 409；
 * 结构化指纹覆盖操作、资源、全部参数与期望版本，字段无碰撞。
 */
@RestController
@RequestMapping("/api/chunk-intakes")
@Validated
public class ChunkIntakeController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final ChunkIntakeService chunkIntakeService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public ChunkIntakeController(ChunkIntakeService chunkIntakeService,
                                 IdempotencyAdvisor idempotencyAdvisor) {
        this.chunkIntakeService = chunkIntakeService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 创建待接收记录：固定总字节数与完整 SHA-256，入库人为操作人。
     */
    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @Valid @RequestBody ChunkIntakeCreateRequest request) {
        String hash = idempotencyAdvisor.hashStructured(ChunkIntakeService.OP_CHUNK_INTAKE_CREATE,
                actorId, request.intakeKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> chunkIntakeService.create(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 追加块：仅入库人；完全相同范围同内容返回已有块，其余重叠 409。
     */
    @PostMapping("/{intakeKey}/chunks")
    public ResponseEntity<String> appendChunk(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                              @PathVariable String intakeKey,
                                              @Valid @RequestBody ChunkAppendRequest request) {
        String hash = idempotencyAdvisor.hashStructured(ChunkIntakeService.OP_CHUNK_APPEND,
                actorId, intakeKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> chunkIntakeService.appendChunk(actorId, intakeKey, request, hash));
        return toEntity(response);
    }

    /**
     * 完成接收：覆盖完整范围且摘要相等才生成 SEALED 证物，否则 422。
     */
    @PostMapping("/{intakeKey}/complete")
    public ResponseEntity<String> complete(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                           @PathVariable String intakeKey,
                                           @Valid @RequestBody ChunkCommandRequest request) {
        String hash = idempotencyAdvisor.hashStructured(ChunkIntakeService.OP_CHUNK_COMPLETE,
                actorId, intakeKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> chunkIntakeService.complete(actorId, intakeKey, request, hash));
        return toEntity(response);
    }

    /**
     * 放弃接收：仅入库人；放弃后可另建新记录。
     */
    @PostMapping("/{intakeKey}/abandon")
    public ResponseEntity<String> abandon(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                          @PathVariable String intakeKey,
                                          @Valid @RequestBody ChunkCommandRequest request) {
        String hash = idempotencyAdvisor.hashStructured(ChunkIntakeService.OP_CHUNK_ABANDON,
                actorId, intakeKey, request);
        StoredResponse response = idempotencyAdvisor.guard(request.commandKey(), hash,
                () -> chunkIntakeService.abandon(actorId, intakeKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询只读块清单：待接收记录 + 已接收块（不含载荷）。
     */
    @GetMapping("/{intakeKey}")
    public ChunkIntakeManifestView manifest(@PathVariable String intakeKey) {
        return chunkIntakeService.manifest(intakeKey);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
