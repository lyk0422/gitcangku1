package com.example.starter.translation.api;

import com.example.starter.translation.service.RetirementService;
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
    private final RetirementService retirementService;
    private final WriteExecutor writeExecutor;
    private final ObjectMapper objectMapper;

    public TranslationController(TranslationService translationService, RetirementService retirementService,
                                 WriteExecutor writeExecutor, ObjectMapper objectMapper) {
        this.translationService = translationService;
        this.retirementService = retirementService;
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

    /** 创建术语版本退役单：校验窗口、替代版本 ACTIVE、无替代环、窗口不重叠；仅预览影响，不改写内容。 */
    @PostMapping("/{documentId}/term-retirements")
    public ResponseEntity<String> createRetirement(@PathVariable long documentId,
                                                   @Valid @RequestBody ApiDtos.CreateRetirementRequest request) {
        String operation = "POST /api/documents/" + documentId + "/term-retirements";
        return writeExecutor.execute(request.requestId(), hash(operation, request),
                () -> WriteResult.of(201, retirementService.createRetirement(documentId, request)))
                .toResponseEntity();
    }

    /** 激活退役单：一个事务内重算影响集合并冻结快照，撤回仍绑定退役术语的 APPROVED 译文。 */
    @PostMapping("/{documentId}/term-retirements/{retirementKey}/activate")
    public ResponseEntity<String> activateRetirement(@PathVariable long documentId,
                                                     @PathVariable String retirementKey,
                                                     @Valid @RequestBody ApiDtos.ActivateRetirementRequest request) {
        String operation = "POST /api/documents/" + documentId + "/term-retirements/" + retirementKey + "/activate";
        return writeExecutor.execute(request.requestId(), hash(operation, request),
                () -> WriteResult.of(200, retirementService.activateRetirement(documentId, retirementKey)))
                .toResponseEntity();
    }

    /** 退役影响查询：只读、稳定排序；未激活返回实时预览，已激活返回冻结快照。 */
    @GetMapping("/{documentId}/term-retirements/{retirementKey}/impact")
    public ResponseEntity<ApiDtos.RetirementResponse> getRetirementImpact(@PathVariable long documentId,
                                                                          @PathVariable String retirementKey) {
        return ResponseEntity.ok(retirementService.getImpact(documentId, retirementKey));
    }

    /**
     * 草稿迁移：提交完整草稿集合、expectedVersion 与逐段替换结果；必须恰好覆盖仍受影响的全部草稿，
     * 替换后通过替代版本规则校验，遗漏、多余或任一违规整体 422。请求摘要对草稿集合排序规范化，换序等价。
     */
    @PostMapping("/{documentId}/term-retirements/{retirementKey}/migrate-drafts")
    public ResponseEntity<String> migrateDrafts(@PathVariable long documentId,
                                                @PathVariable String retirementKey,
                                                @RequestHeader("X-Actor-Id") String actorId,
                                                @Valid @RequestBody ApiDtos.MigrateDraftsRequest request) {
        String operation = "POST /api/documents/" + documentId + "/term-retirements/" + retirementKey
                + "/migrate-drafts";
        return writeExecutor.execute(request.requestId(), hash(operation, actorId, canonical(request)),
                () -> WriteResult.of(200, retirementService.migrateDrafts(
                        documentId, retirementKey, actorId, request))).toResponseEntity();
    }

    /** 迁移请求规范化：草稿集合按段落与语言排序、语言码统一小写，使集合换序的请求摘要等价。 */
    private static ApiDtos.MigrateDraftsRequest canonical(ApiDtos.MigrateDraftsRequest request) {
        List<ApiDtos.DraftReplacement> sorted = request.drafts().stream()
                .map(d -> new ApiDtos.DraftReplacement(d.segmentId(),
                        d.language().trim().toLowerCase(java.util.Locale.ROOT), d.content()))
                .sorted((a, b) -> (a.segmentId() + " " + a.language())
                        .compareTo(b.segmentId() + " " + b.language()))
                .toList();
        return new ApiDtos.MigrateDraftsRequest(request.requestId(), request.expectedVersion(), sorted);
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
