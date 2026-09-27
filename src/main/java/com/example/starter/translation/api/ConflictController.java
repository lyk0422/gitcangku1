package com.example.starter.translation.api;

import com.example.starter.translation.service.ConflictService;
import com.example.starter.translation.service.WriteExecutor;
import com.example.starter.translation.service.WriteResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 评审利益冲突 REST API：声明登记（单条/批量）、解除、明细/历史/诊断查询。
 * 登记人取 X-Actor-Id；解除还须携带 X-Actor-Role: COMPLIANCE 且不同于声明人。
 */
@RestController
@RequestMapping("/api/documents/{documentId}/conflicts")
public class ConflictController {

    private final ConflictService conflictService;
    private final WriteExecutor writeExecutor;
    private final ObjectMapper objectMapper;

    public ConflictController(ConflictService conflictService, WriteExecutor writeExecutor,
                              ObjectMapper objectMapper) {
        this.conflictService = conflictService;
        this.writeExecutor = writeExecutor;
        this.objectMapper = objectMapper;
    }

    /** 单条登记冲突声明。 */
    @PostMapping
    public ResponseEntity<String> declare(@PathVariable long documentId,
                                          @RequestHeader("X-Actor-Id") String actorId,
                                          @Valid @RequestBody ApiDtos.DeclareConflictRequest request) {
        String operation = "POST /api/documents/" + documentId + "/conflicts";
        return writeExecutor.execute(request.requestId(), hash(operation, actorId, request),
                () -> WriteResult.of(201, conflictService.declare(documentId, actorId, request)))
                .toResponseEntity();
    }

    /** 批量登记冲突：任一范围非法整批不改变评审资格。 */
    @PostMapping("/batch")
    public ResponseEntity<String> declareBatch(@PathVariable long documentId,
                                               @RequestHeader("X-Actor-Id") String actorId,
                                               @Valid @RequestBody ApiDtos.BatchDeclareConflictRequest request) {
        String operation = "POST /api/documents/" + documentId + "/conflicts/batch";
        return writeExecutor.execute(request.requestId(), hash(operation, actorId, request),
                () -> WriteResult.of(201, conflictService.declareBatch(documentId, actorId, request)))
                .toResponseEntity();
    }

    /** 解除冲突：须由不同于声明人的合规角色确认。 */
    @PostMapping("/{declarationId}/release")
    public ResponseEntity<String> release(@PathVariable long documentId, @PathVariable long declarationId,
                                          @RequestHeader("X-Actor-Id") String actorId,
                                          @RequestHeader(value = "X-Actor-Role", required = false) String actorRole,
                                          @Valid @RequestBody ApiDtos.ReleaseConflictRequest request) {
        String operation = "POST /api/documents/" + documentId + "/conflicts/" + declarationId + "/release";
        return writeExecutor.execute(request.requestId(), hash(operation, actorId, actorRole, request),
                () -> WriteResult.of(200,
                        conflictService.release(documentId, declarationId, actorId, actorRole, request)))
                .toResponseEntity();
    }

    /** 查询全部冲突声明明细，按声明 ID 升序。 */
    @GetMapping
    public ResponseEntity<List<ApiDtos.ConflictDeclarationView>> listConflicts(@PathVariable long documentId) {
        return ResponseEntity.ok(conflictService.listConflicts(documentId));
    }

    /** 查询冲突生命周期历史；可按声明过滤，按事件 ID 升序。 */
    @GetMapping("/events")
    public ResponseEntity<List<ApiDtos.ConflictEventView>> listEvents(
            @PathVariable long documentId,
            @RequestParam(value = "declarationId", required = false) Long declarationId) {
        return ResponseEntity.ok(conflictService.listConflictEvents(documentId, declarationId));
    }

    /** 查询评审历史；可按段落、语种过滤，按历史 ID 升序。 */
    @GetMapping("/reviews/history")
    public ResponseEntity<List<ApiDtos.ReviewHistoryView>> reviewHistory(
            @PathVariable long documentId,
            @RequestParam(value = "segmentId", required = false) String segmentId,
            @RequestParam(value = "language", required = false) String language) {
        return ResponseEntity.ok(conflictService.listReviewHistory(documentId, segmentId, language));
    }

    /** 评审诊断：当前有效冲突、评审资格与重算法定人数，只读。 */
    @GetMapping("/diagnostics")
    public ResponseEntity<ApiDtos.ReviewDiagnosticsResponse> diagnostics(@PathVariable long documentId) {
        return ResponseEntity.ok(conflictService.diagnostics(documentId));
    }

    /** 计算请求摘要：操作（含路径变量）+ 操作者/角色 + 规范化请求体的 SHA-256。 */
    private String hash(String operation, Object... parts) {
        try {
            StringBuilder canonical = new StringBuilder(operation);
            for (Object part : parts) {
                canonical.append('\n').append(objectMapper.writeValueAsString(part));
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("请求序列化失败", e);
        }
    }
}
