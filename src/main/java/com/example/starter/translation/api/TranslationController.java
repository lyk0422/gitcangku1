package com.example.starter.translation.api;

import com.example.starter.translation.service.CitationService;
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
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 多语种段落修订与发布快照 REST API。
 * 所有写操作携带全局唯一 requestId 做幂等去重；提交/批准以 X-Actor-Id 表示本地操作者。
 */
@RestController
@RequestMapping("/api/documents")
public class TranslationController {

    private final TranslationService translationService;
    private final CitationService citationService;
    private final WriteExecutor writeExecutor;
    private final ObjectMapper objectMapper;

    public TranslationController(TranslationService translationService, CitationService citationService,
                                 WriteExecutor writeExecutor, ObjectMapper objectMapper) {
        this.translationService = translationService;
        this.citationService = citationService;
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

    /** 译文批准：审核人取 X-Actor-Id，不得是作者，须同时匹配当前源文与译文版本。 */
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

    /** 发布：全部段落全部目标语言均有有效批准且术语校验通过时原子生成只读快照并递增发布版本。 */
    @PostMapping("/{documentId}/publish")
    public ResponseEntity<String> publish(@PathVariable long documentId,
                                          @Valid @RequestBody ApiDtos.PublishRequest request) {
        String operation = "POST /api/documents/" + documentId + "/publish";
        return writeExecutor.execute(request.requestId(), hash(operation, request),
                () -> WriteResult.of(201, translationService.publish(documentId, request))).toResponseEntity();
    }

    /**
     * 引文锚点登记：在指定语言的已批准译文版本上登记一个或多个锚点；
     * 创建人取 X-Actor-Id，角色取 X-Actor-Role（缺省 EDITOR）。
     */
    @PostMapping("/{documentId}/segments/{segmentId}/translations/{language}/anchors")
    public ResponseEntity<String> registerAnchors(@PathVariable long documentId,
                                                  @PathVariable String segmentId,
                                                  @PathVariable String language,
                                                  @RequestHeader("X-Actor-Id") String actorId,
                                                  @RequestHeader(value = "X-Actor-Role", required = false)
                                                  String actorRole,
                                                  @Valid @RequestBody ApiDtos.RegisterAnchorsRequest request) {
        String operation = "POST /api/documents/" + documentId + "/segments/" + segmentId
                + "/translations/" + language + "/anchors";
        return writeExecutor.execute(request.requestId(), hash(operation, actorId, actorRole, request),
                () -> WriteResult.of(201, citationService.registerAnchors(
                        documentId, segmentId, language, actorId, actorRole, request))).toResponseEntity();
    }

    /** 批量译文修订：先整批校验锚点映射与术语版本，任一失败整次回滚，草稿版本只加一。 */
    @PostMapping("/{documentId}/translation-revisions")
    public ResponseEntity<String> batchReviseTranslations(@PathVariable long documentId,
                                                          @RequestHeader("X-Actor-Id") String actorId,
                                                          @RequestHeader(value = "X-Actor-Role", required = false)
                                                          String actorRole,
                                                          @Valid @RequestBody
                                                          ApiDtos.BatchReviseTranslationsRequest request) {
        String operation = "POST /api/documents/" + documentId + "/translation-revisions";
        return writeExecutor.execute(request.requestId(), hash(operation, actorId, actorRole, request),
                () -> WriteResult.of(200, citationService.batchRevise(
                        documentId, actorId, actorRole, request))).toResponseEntity();
    }

    /** 解除锚点：须由异于创建人的法务角色（X-Actor-Role: LEGAL）确认并写入不可变理由。 */
    @PostMapping("/{documentId}/anchors/{anchorId}/release")
    public ResponseEntity<String> releaseAnchor(@PathVariable long documentId, @PathVariable long anchorId,
                                                @RequestHeader("X-Actor-Id") String actorId,
                                                @RequestHeader(value = "X-Actor-Role", required = false)
                                                String actorRole,
                                                @Valid @RequestBody ApiDtos.ReleaseAnchorRequest request) {
        String operation = "POST /api/documents/" + documentId + "/anchors/" + anchorId + "/release";
        return writeExecutor.execute(request.requestId(), hash(operation, actorId, actorRole, request),
                () -> WriteResult.of(200, citationService.releaseAnchor(
                        documentId, anchorId, actorId, actorRole, request))).toResponseEntity();
    }

    /** 查询段落全部引文锚点明细（含已解除），只读。 */
    @GetMapping("/{documentId}/segments/{segmentId}/anchors")
    public ResponseEntity<List<ApiDtos.AnchorView>> getAnchors(@PathVariable long documentId,
                                                               @PathVariable String segmentId) {
        return ResponseEntity.ok(citationService.getAnchors(documentId, segmentId));
    }

    /** 查询段落锚点不可变事件历史（登记/迁移/解除，按提交顺序），只读。 */
    @GetMapping("/{documentId}/segments/{segmentId}/anchors/history")
    public ResponseEntity<ApiDtos.AnchorHistoryResponse> getAnchorHistory(@PathVariable long documentId,
                                                                          @PathVariable String segmentId) {
        return ResponseEntity.ok(citationService.getHistory(documentId, segmentId));
    }

    /** 查询文档全部锚点的当前一致性诊断，只读。 */
    @GetMapping("/{documentId}/anchors/diagnostics")
    public ResponseEntity<ApiDtos.AnchorDiagnosticsResponse> getAnchorDiagnostics(
            @PathVariable long documentId) {
        return ResponseEntity.ok(citationService.getDiagnostics(documentId));
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
