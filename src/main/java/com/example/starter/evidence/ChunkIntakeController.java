package com.example.starter.evidence;

import com.example.starter.evidence.dto.ChunkAbandonRequest;
import com.example.starter.evidence.dto.ChunkCompleteRequest;
import com.example.starter.evidence.dto.ChunkIntakeCreateRequest;
import com.example.starter.evidence.dto.ChunkIntakeView;
import com.example.starter.evidence.dto.ChunkUploadRequest;
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
import java.util.Map;

/**
 * 数字证物分块接收入口。写请求必须带非空 X-Actor-Id 与请求体内全局唯一 requestId；
 * 指纹按操作、资源（intakeKey）、操作人与全部参数结构化生成，块载荷按 Base64 解码字节规范化。
 */
@RestController
@RequestMapping("/api/evidence/chunk-intakes")
@Validated
public class ChunkIntakeController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final ChunkIntakeService service;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public ChunkIntakeController(ChunkIntakeService service, IdempotencyAdvisor idempotencyAdvisor) {
        this.service = service;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 创建待接收记录：固定总字节数 1..65536 与完整 SHA-256 摘要。
     */
    @PostMapping
    public ResponseEntity<String> create(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @Valid @RequestBody ChunkIntakeCreateRequest request) {
        String hash = idempotencyAdvisor.hash(ChunkIntakeService.OP_CREATE, actorId,
                request.intakeKey(), request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> service.create(actorId, request, hash));
        return toEntity(response);
    }

    /**
     * 补块：非负偏移 + Base64 字节（解码 1..4096，不越总长）；乱序允许，同范围同内容返回已有块。
     */
    @PostMapping("/{intakeKey}/chunks")
    public ResponseEntity<String> upload(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String intakeKey,
                                         @Valid @RequestBody ChunkUploadRequest request) {
        String hash = idempotencyAdvisor.hash(ChunkIntakeService.OP_UPLOAD, actorId, intakeKey,
                canonicalUpload(request));
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> service.uploadChunk(actorId, intakeKey, request, hash));
        return toEntity(response);
    }

    /**
     * 显式完成：覆盖 [0,总长度) 且摘要相等才创建原 SEALED 证物与只读块清单。
     */
    @PostMapping("/{intakeKey}/complete")
    public ResponseEntity<String> complete(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                           @PathVariable String intakeKey,
                                           @Valid @RequestBody ChunkCompleteRequest request) {
        String hash = idempotencyAdvisor.hash(ChunkIntakeService.OP_COMPLETE, actorId, intakeKey,
                request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> service.complete(actorId, intakeKey, request, hash));
        return toEntity(response);
    }

    /**
     * 放弃接收（如摘要错误）：仅 RECEIVING 状态的入库人可操作。
     */
    @PostMapping("/{intakeKey}/abandon")
    public ResponseEntity<String> abandon(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                          @PathVariable String intakeKey,
                                          @Valid @RequestBody ChunkAbandonRequest request) {
        String hash = idempotencyAdvisor.hash(ChunkIntakeService.OP_ABANDON, actorId, intakeKey,
                request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> service.abandon(actorId, intakeKey, request, hash));
        return toEntity(response);
    }

    /**
     * 查询接收状态：含状态、版本、已覆盖字节数；不暴露块载荷。
     */
    @GetMapping("/{intakeKey}")
    public ChunkIntakeView status(@PathVariable String intakeKey) {
        return service.status(intakeKey);
    }

    /**
     * 指纹参数规范化：dataBase64 先解码再按标准编码唯一化，等价 Base64 写法不形成不同内容。
     */
    private Map<String, Object> canonicalUpload(ChunkUploadRequest request) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("requestId", request.requestId());
        canonical.put("offset", request.offset());
        canonical.put("dataBase64", ChunkEncoding.canonical(request.dataBase64()));
        canonical.put("expectedVersion", request.expectedVersion());
        return canonical;
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
