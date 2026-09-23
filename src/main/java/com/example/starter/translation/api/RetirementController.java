package com.example.starter.translation.api;

import com.example.starter.translation.service.RetirementService;
import com.example.starter.translation.service.WriteExecutor;
import com.example.starter.translation.service.WriteResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 术语版本退役窗口与草稿原子迁移 REST API。
 * 写操作复用统一 requestId 幂等执行器；草稿迁移的逐段集合先做稳定排序再参与摘要，集合换序等价。
 */
@RestController
@RequestMapping("/api/documents/{documentId}/retirements")
public class RetirementController {

    private final RetirementService retirementService;
    private final WriteExecutor writeExecutor;
    private final ObjectMapper objectMapper;

    public RetirementController(RetirementService retirementService, WriteExecutor writeExecutor,
                                ObjectMapper objectMapper) {
        this.retirementService = retirementService;
        this.writeExecutor = writeExecutor;
        this.objectMapper = objectMapper;
    }

    /** 创建退役单：校验窗口/替代版本/替代环/窗口重叠，返回只读影响预览，不改写内容。 */
    @PostMapping
    public ResponseEntity<String> createRetirement(@PathVariable long documentId,
                                                   @Valid @RequestBody ApiDtos.CreateRetirementRequest request) {
        String operation = "POST /api/documents/" + documentId + "/retirements";
        return writeExecutor.execute(request.requestId(), hash(operation, request),
                () -> WriteResult.of(201, retirementService.createRetirement(documentId, request)))
                .toResponseEntity();
    }

    /** 激活退役单：事务内重算影响集、冻结快照、撤批并退役术语版本。 */
    @PostMapping("/{retirementKey}/activate")
    public ResponseEntity<String> activateRetirement(@PathVariable long documentId,
                                                     @PathVariable String retirementKey,
                                                     @Valid @RequestBody ApiDtos.ActivateRetirementRequest request) {
        String operation = "POST /api/documents/" + documentId + "/retirements/" + retirementKey + "/activate";
        return writeExecutor.execute(request.requestId(), hash(operation, request),
                () -> WriteResult.of(200, retirementService.activateRetirement(documentId, retirementKey, request)))
                .toResponseEntity();
    }

    /** 草稿原子迁移：一次覆盖全部仍受影响草稿，遗漏/多余/违规整体 422。 */
    @PostMapping("/{retirementKey}/migrate-drafts")
    public ResponseEntity<String> migrateDrafts(@PathVariable long documentId,
                                                @PathVariable String retirementKey,
                                                @Valid @RequestBody ApiDtos.MigrateDraftsRequest request) {
        String operation = "POST /api/documents/" + documentId + "/retirements/"
                + retirementKey + "/migrate-drafts";
        List<ApiDtos.MigrationEntryInput> orderedEntries = request.entries().stream()
                .sorted((a, b) -> {
                    int bySegment = a.segmentId().compareTo(b.segmentId());
                    return bySegment != 0 ? bySegment : a.language().compareToIgnoreCase(b.language());
                })
                .toList();
        ApiDtos.MigrateDraftsRequest canonical = new ApiDtos.MigrateDraftsRequest(
                request.requestId(), request.expectedVersion(), orderedEntries);
        return writeExecutor.execute(request.requestId(), hash(operation, canonical),
                () -> WriteResult.of(200,
                        retirementService.migrateDrafts(documentId, retirementKey, canonical)))
                .toResponseEntity();
    }

    /** 影响查询：只读稳定排序；未激活返回预览，已激活返回冻结快照。 */
    @GetMapping("/{retirementKey}")
    public ResponseEntity<ApiDtos.RetirementView> getRetirement(@PathVariable long documentId,
                                                                @PathVariable String retirementKey) {
        return ResponseEntity.ok(retirementService.getRetirement(documentId, retirementKey));
    }

    /** 计算请求摘要：操作（含路径变量）+ 规范化请求体的 SHA-256。 */
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
