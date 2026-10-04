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
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 多语种段落修订与发布快照 REST API。
 * 所有写操作携带全局唯一 requestId 做幂等去重；提交/批准以 X-Actor-Id 表示本地操作者。
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

    /** 新增批注：绑定当前译文版本与非空码点区间；新增批注使当前批准失效。 */
    @PostMapping("/{documentId}/segments/{segmentId}/translations/{language}/annotations")
    public ResponseEntity<String> createAnnotation(@PathVariable long documentId, @PathVariable String segmentId,
                                                   @PathVariable String language,
                                                   @RequestHeader("X-Actor-Id") String actorId,
                                                   @Valid @RequestBody ApiDtos.CreateAnnotationRequest request) {
        String operation = "POST /api/documents/" + documentId + "/segments/" + segmentId
                + "/translations/" + language + "/annotations";
        return writeExecutor.execute(request.requestId(), hash(operation, actorId, request),
                () -> WriteResult.of(201, translationService.createAnnotation(
                        documentId, segmentId, language, actorId, request))).toResponseEntity();
    }

    /** 定点编辑：提交期望译文版本、替换区间与新文本；编辑使原批准失效并平移或孤立批注。 */
    @PutMapping("/{documentId}/segments/{segmentId}/translations/{language}/edit")
    public ResponseEntity<String> anchoredEdit(@PathVariable long documentId, @PathVariable String segmentId,
                                               @PathVariable String language,
                                               @RequestHeader("X-Actor-Id") String actorId,
                                               @Valid @RequestBody ApiDtos.AnchoredEditRequest request) {
        String operation = "PUT /api/documents/" + documentId + "/segments/" + segmentId
                + "/translations/" + language + "/edit";
        return writeExecutor.execute(request.requestId(), hash(operation, actorId, request),
                () -> WriteResult.of(200, translationService.anchoredEdit(
                        documentId, segmentId, language, actorId, request))).toResponseEntity();
    }

    /** 批注解决：仅批注创建人可将 OPEN 或 ORPHAN 确认为 RESOLVED，携带期望译文版本原子裁决。 */
    @PostMapping("/{documentId}/segments/{segmentId}/translations/{language}/annotations/{annotationId}/resolve")
    public ResponseEntity<String> resolveAnnotation(@PathVariable long documentId, @PathVariable String segmentId,
                                                    @PathVariable String language, @PathVariable long annotationId,
                                                    @RequestHeader("X-Actor-Id") String actorId,
                                                    @Valid @RequestBody ApiDtos.ResolveAnnotationRequest request) {
        String operation = "POST /api/documents/" + documentId + "/segments/" + segmentId
                + "/translations/" + language + "/annotations/" + annotationId + "/resolve";
        return writeExecutor.execute(request.requestId(), hash(operation, actorId, request),
                () -> WriteResult.of(200, translationService.resolveAnnotation(
                        documentId, segmentId, language, annotationId, actorId, request))).toResponseEntity();
    }

    /** 查询段落某语言下全部批注：OPEN 携带当前锚点，ORPHAN 仅保留原引用。 */
    @GetMapping("/{documentId}/segments/{segmentId}/translations/{language}/annotations")
    public ResponseEntity<ApiDtos.AnnotationListResponse> listAnnotations(@PathVariable long documentId,
                                                                          @PathVariable String segmentId,
                                                                          @PathVariable String language) {
        return ResponseEntity.ok(translationService.listAnnotations(documentId, segmentId, language));
    }
    /**
     * 计算请求摘要：操作（含路径变量定位资源）、操作者与全部请求参数（含期望版本）
     * 组成结构化 JSON 数组后取 SHA-256，避免分隔符拼接造成的身份碰撞。
     */
    private String hash(String operation, Object... parts) {
        try {
            List<Object> identity = new ArrayList<>(parts.length + 1);
            identity.add(operation);
            identity.addAll(Arrays.asList(parts));
            String canonical = objectMapper.writeValueAsString(identity);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("请求序列化失败", e);
        }
    }
}
