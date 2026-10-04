package com.example.starter.evidence;

import com.example.starter.evidence.dto.CopyContentView;
import com.example.starter.evidence.dto.IssueCopyRequest;
import com.example.starter.evidence.dto.RevokeCopyRequest;
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
 * 阅检副本 API。签发与回收为幂等写操作（requestId），指纹由操作、操作者、
 * 资源路径段与全部请求参数（含期望版本）结构化计算，避免分隔符碰撞。
 * 读取不做幂等记录，每次重新鉴权。
 */
@RestController
@RequestMapping("/api/evidence/{evidenceKey}/copies")
@Validated
public class InspectionCopyController {

    static final String ACTOR_HEADER = "X-Actor-Id";

    private final InspectionCopyService copyService;
    private final IdempotencyAdvisor idempotencyAdvisor;

    public InspectionCopyController(InspectionCopyService copyService,
                                    IdempotencyAdvisor idempotencyAdvisor) {
        this.copyService = copyService;
        this.idempotencyAdvisor = idempotencyAdvisor;
    }

    /**
     * 签发阅检副本：仅当前保管人；首次响应含合成文本，重放仅返回元数据。
     */
    @PostMapping
    public ResponseEntity<String> issue(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                        @PathVariable String evidenceKey,
                                        @Valid @RequestBody IssueCopyRequest request) {
        String hash = idempotencyAdvisor.hashStructured(InspectionCopyService.OP_COPY_ISSUE,
                actorId, List.of(evidenceKey), request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> copyService.issue(actorId, evidenceKey, request, hash));
        return toEntity(response);
    }

    /**
     * 读取副本内容：仅阅检人本人且在有效期内；每次重新鉴权。
     */
    @GetMapping("/{copyId}")
    public CopyContentView read(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                @PathVariable String evidenceKey,
                                @PathVariable String copyId) {
        return copyService.read(actorId, evidenceKey, copyId);
    }

    /**
     * 回收副本：仅当前保管人；回收不删除签发历史。
     */
    @PostMapping("/{copyId}/revoke")
    public ResponseEntity<String> revoke(@RequestHeader(ACTOR_HEADER) @NotBlank String actorId,
                                         @PathVariable String evidenceKey,
                                         @PathVariable String copyId,
                                         @Valid @RequestBody RevokeCopyRequest request) {
        String hash = idempotencyAdvisor.hashStructured(InspectionCopyService.OP_COPY_REVOKE,
                actorId, List.of(evidenceKey, copyId), request);
        StoredResponse response = idempotencyAdvisor.guard(request.requestId(), hash,
                () -> copyService.revoke(actorId, evidenceKey, copyId, request, hash));
        return toEntity(response);
    }

    private ResponseEntity<String> toEntity(StoredResponse response) {
        return ResponseEntity.status(HttpStatus.valueOf(response.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}