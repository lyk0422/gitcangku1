package com.example.starter.translation.api;

import com.example.starter.translation.service.TranslationService;
import com.example.starter.translation.service.WriteExecutor;
import com.example.starter.translation.service.WriteResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
 * 多语种段落修订、评审利益冲突与发布快照 REST API。
 * 所有写操作携带全局唯一 requestId 做幂等去重；提交/评审/声明以 X-Actor-Id 表示本地操作者，
 * 解除冲突另以 X-Actor-Role 表示合规角色。
 */
@RestController
@RequestMapping("/api/documents")
public class TranslationController {

    private final TranslationService translationService;
    private final WriteExecutor writeExecutor;
    private final ObjectMapper objectMapper;

    public TranslationController(TranslationService translationService, WriteExecutor writeExecutor,
                                 ObjectMapper objectMapper) {
        this.translationService = translationService;
        this.writeExecutor = writeExecutor;
        this.objectMapper = objectMapper;
    }

    /** 建文档：1~5 种目标语言，可携带初始段落。 */
    @PostMapping
    public ResponseEntity<String> createDocument(@Valid @RequestBody ApiDtos.CreateDocumentRequest request) {
        return writeExecutor.execute(request.requestId(), hash("POST /api/documents", request),
                () -> WriteResult.of(201, translationService.createDocument(request))).toResponseEntity();
    }

    /** 增加段落：文档草稿版本加一。 */
    @PostMapping("/{documentId}/segments")
    public ResponseEntity<String> addSegment(@PathVariable long documentId,
                                             @Valid @RequestBody ApiDtos.AddSegmentRequest request) {
        String operation = "POST /api/documents/" + documentId + "/segments";
        return writeExecutor.execute(request.requestId(), hash(operation, request),
                () -> WriteResult.of(201, translationService.addSegment(documentId, request))).toResponseEntity();
    }

    /** 源文修订：源文版本加一、相关译文待更新、草稿版本加一；不影响已发布快照。 */
    @PutMapping("/{documentId}/segments/{segmentId}/source")
    public ResponseEntity<String> reviseSource(@PathVariable long documentId, @PathVariable String segmentId,
                                               @Valid @RequestBody ApiDtos.ReviseSourceRequest request) {
        String operation = "PUT /api/documents/" + documentId + "/segments/" + segmentId + "/source";
        return writeExecutor.execute(request.requestId(), hash(operation, request),
                () -> WriteResult.of(200, translationService.reviseSource(documentId, segmentId, request)))
                .toResponseEntity();
    }

    /** 译文提交：作者取 X-Actor-Id，所依据源文版本必须匹配当前源文版本。 */
    @PutMapping("/{documentId}/segments/{segmentId}/translations/{language}")
    public ResponseEntity<String> submitTranslation(@PathVariable long documentId, @PathVariable String segmentId,
                                                    @PathVariable String language,
                                                    @RequestHeader("X-Actor-Id") String actorId,
                                                    @Valid @RequestBody ApiDtos.SubmitTranslationRequest request) {
        String operation = "PUT /api/documents/" + documentId + "/segments/" + segmentId
                + "/translations/" + language;
        return writeExecutor.execute(request.requestId(), hash(operation, actorId, request),
                () -> WriteResult.of(200, translationService.submitTranslation(
                        documentId, segmentId, language, actorId, request))).toResponseEntity();
    }

    /** 译文批准：评审人取 X-Actor-Id，不得是作者，须匹配当前源文与译文版本且无有效冲突。 */
    @PostMapping("/{documentId}/segments/{segmentId}/translations/{language}/approve")
    public ResponseEntity<String> approveTranslation(@PathVariable long documentId, @PathVariable String segmentId,
                                                     @PathVariable String language,
                                                     @RequestHeader("X-Actor-Id") String actorId,
                                                     @Valid @RequestBody ApiDtos.ApproveTranslationRequest request) {
        String operation = "POST /api/documents/" + documentId + "/segments/" + segmentId
                + "/translations/" + language + "/approve";
        return writeExecutor.execute(request.requestId(), hash(operation, actorId, request),
                () -> WriteResult.of(200, translationService.approveTranslation(
                        documentId, segmentId, language, actorId, request))).toResponseEntity();
    }

    /** 译文否决：资格规则同批准，须给出非空否决原因；有效否决阻断发布。 */
    @PostMapping("/{documentId}/segments/{segmentId}/translations/{language}/reject")
    public ResponseEntity<String> rejectTranslation(@PathVariable long documentId, @PathVariable String segmentId,
                                                    @PathVariable String language,
                                                    @RequestHeader("X-Actor-Id") String actorId,
                                                    @Valid @RequestBody ApiDtos.RejectTranslationRequest request) {
        String operation = "POST /api/documents/" + documentId + "/segments/" + segmentId
                + "/translations/" + language + "/reject";
        return writeExecutor.execute(request.requestId(), hash(operation, actorId, request),
                () -> WriteResult.of(200, translationService.rejectTranslation(
                        documentId, segmentId, language, actorId, request))).toResponseEntity();
    }

    /**
     * 批量登记评审利益冲突：评审人与声明人取 X-Actor-Id；全部范围预校验通过后原子登记，
     * 重叠声明返回 409。登记后命中范围的既有评审标记 CONFLICTED 并排除出法定人数。
     */
    @PostMapping("/{documentId}/conflicts")
    public ResponseEntity<String> registerConflicts(@PathVariable long documentId,
                                                    @RequestHeader("X-Actor-Id") String actorId,
                                                    @Valid @RequestBody ApiDtos.RegisterConflictsRequest request) {
        String operation = "POST /api/documents/" + documentId + "/conflicts";
        return writeExecutor.execute(request.requestId(), hash(operation, actorId, request),
                () -> WriteResult.of(201, translationService.registerConflicts(documentId, actorId, request)))
                .toResponseEntity();
    }

    /** 冲突明细查询：可按 reviewer、language、status 过滤，读取不改变状态。 */
    @GetMapping("/{documentId}/conflicts")
    public ResponseEntity<List<ApiDtos.ConflictView>> listConflicts(
            @PathVariable long documentId,
            @RequestParam(name = "reviewer", required = false) String reviewer,
            @RequestParam(name = "language", required = false) String language,
            @RequestParam(name = "status", required = false) String status) {
        return ResponseEntity.ok(translationService.listConflicts(documentId, reviewer, language, status));
    }

    /** 单条冲突明细（含历史）。 */
    @GetMapping("/{documentId}/conflicts/{conflictId}")
    public ResponseEntity<ApiDtos.ConflictView> getConflict(@PathVariable long documentId,
                                                            @PathVariable long conflictId) {
        return ResponseEntity.ok(translationService.getConflict(documentId, conflictId));
    }

    /** 冲突历史查询：按 eventId 升序，可按冲突声明过滤。 */
    @GetMapping("/{documentId}/conflicts/history")
    public ResponseEntity<List<ApiDtos.ConflictEventView>> listConflictHistory(
            @PathVariable long documentId,
            @RequestParam(name = "conflictId", required = false) Long conflictId) {
        return ResponseEntity.ok(translationService.listConflictHistory(documentId, conflictId));
    }

    /** 解除冲突：须由不同于声明人的合规角色（X-Actor-Role: COMPLIANCE）确认，追加历史记录。 */
    @PostMapping("/{documentId}/conflicts/{conflictId}/resolve")
    public ResponseEntity<String> resolveConflict(@PathVariable long documentId,
                                                  @PathVariable long conflictId,
                                                  @RequestHeader("X-Actor-Id") String actorId,
                                                  @RequestHeader(name = "X-Actor-Role", required = false)
                                                  String actorRole,
                                                  @Valid @RequestBody ApiDtos.ResolveConflictRequest request) {
        String operation = "POST /api/documents/" + documentId + "/conflicts/" + conflictId + "/resolve";
        return writeExecutor.execute(request.requestId(), hash(operation, actorId, actorRole, request),
                () -> WriteResult.of(200, translationService.resolveConflict(
                        documentId, conflictId, actorId, actorRole, request))).toResponseEntity();
    }

    /** 评审明细查询：可按段落、语种、评审人过滤，保留 ACTIVE/CONFLICTED 及版本失效全部记录。 */
    @GetMapping("/{documentId}/reviews")
    public ResponseEntity<List<ApiDtos.ReviewView>> listReviews(
            @PathVariable long documentId,
            @RequestParam(name = "segmentId", required = false) String segmentId,
            @RequestParam(name = "language", required = false) String language,
            @RequestParam(name = "reviewer", required = false) String reviewer) {
        return ResponseEntity.ok(translationService.listReviews(documentId, segmentId, language, reviewer));
    }

    /** 评审资格与法定人数诊断：读取不改变状态。 */
    @GetMapping("/{documentId}/reviews/diagnostics")
    public ResponseEntity<ApiDtos.ReviewDiagnosticsResponse> getReviewDiagnostics(@PathVariable long documentId) {
        return ResponseEntity.ok(translationService.getReviewDiagnostics(documentId));
    }

    /** 发布：按当前译文/术语版本与非冲突有效评审计数，全部段落语种满足法定人数时原子生成只读快照。 */
    @PostMapping("/{documentId}/publish")
    public ResponseEntity<String> publish(@PathVariable long documentId,
                                          @Valid @RequestBody ApiDtos.PublishRequest request) {
        String operation = "POST /api/documents/" + documentId + "/publish";
        return writeExecutor.execute(request.requestId(), hash(operation, request),
                () -> WriteResult.of(201, translationService.publish(documentId, request))).toResponseEntity();
    }

    /** 新增术语版本：不可变快照，术语版本与草稿版本各加一；已有版本不可覆盖。 */
    @PutMapping("/{documentId}/terms")
    public ResponseEntity<String> updateTerms(@PathVariable long documentId,
                                              @Valid @RequestBody ApiDtos.UpdateTermsRequest request) {
        String operation = "PUT /api/documents/" + documentId + "/terms";
        return writeExecutor.execute(request.requestId(), hash(operation, request),
                () -> WriteResult.of(201, translationService.updateTerms(documentId, request))).toResponseEntity();
    }

    /** 查询当前术语版本及完整规则集。 */
    @GetMapping("/{documentId}/terms")
    public ResponseEntity<ApiDtos.TermVersionView> getCurrentTerms(@PathVariable long documentId) {
        return ResponseEntity.ok(translationService.getCurrentTerms(documentId));
    }

    /** 查询指定术语版本的不可变规则集。 */
    @GetMapping("/{documentId}/terms/{termVersion}")
    public ResponseEntity<ApiDtos.TermVersionView> getTerms(@PathVariable long documentId,
                                                            @PathVariable int termVersion) {
        return ResponseEntity.ok(translationService.getTerms(documentId, termVersion));
    }

    /** 查询全部译文的术语状态：绑定版本、是否过期及当前规则下的违规术语。 */
    @GetMapping("/{documentId}/terms/status")
    public ResponseEntity<ApiDtos.TermStatusResponse> getTermStatus(@PathVariable long documentId) {
        return ResponseEntity.ok(translationService.getTermStatus(documentId));
    }

    /** 查询指定发布版本的只读快照。 */
    @GetMapping("/{documentId}/releases/{publishedVersion}")
    public ResponseEntity<String> getRelease(@PathVariable long documentId, @PathVariable int publishedVersion) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(translationService.getRelease(documentId, publishedVersion));
    }

    /** 计算请求摘要：操作（含路径变量）+ 操作者 + 规范化请求体的 SHA-256。 */
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
