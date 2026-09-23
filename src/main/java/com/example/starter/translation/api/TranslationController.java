package com.example.starter.translation.api;

import com.example.starter.translation.service.ReleaseTrainService;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 多语种段落修订与发布快照 REST API。
 * 所有写操作携带全局唯一 requestId 做幂等去重；提交/批准以 X-Actor-Id 表示本地操作者。
 */
@RestController
@RequestMapping("/api/documents")
public class TranslationController {

    private final TranslationService translationService;
    private final ReleaseTrainService releaseTrainService;
    private final WriteExecutor writeExecutor;
    private final ObjectMapper objectMapper;

    public TranslationController(TranslationService translationService, ReleaseTrainService releaseTrainService,
                                 WriteExecutor writeExecutor, ObjectMapper objectMapper) {
        this.translationService = translationService;
        this.releaseTrainService = releaseTrainService;
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

    /** 创建发布列车：2~20 个唯一语言且与文档目标语言完全一致；locale 换序视为同参。 */
    @PostMapping("/{documentId}/release-trains")
    public ResponseEntity<String> createTrain(@PathVariable long documentId,
                                              @Valid @RequestBody ApiDtos.CreateReleaseTrainRequest request) {
        String operation = "POST /api/documents/" + documentId + "/release-trains";
        return writeExecutor.execute(request.requestId(), trainHash(operation, request),
                () -> WriteResult.of(201, releaseTrainService.createTrain(documentId, request)))
                .toResponseEntity();
    }

    /** 查询文档全部列车，按 trainKey 稳定排序。 */
    @GetMapping("/{documentId}/release-trains")
    public ResponseEntity<ApiDtos.TrainListResponse> listTrains(@PathVariable long documentId) {
        return ResponseEntity.ok(releaseTrainService.listTrains(documentId));
    }

    /** 查询单个列车详情。 */
    @GetMapping("/{documentId}/release-trains/{trainKey}")
    public ResponseEntity<ApiDtos.TrainResponse> getTrain(@PathVariable long documentId,
                                                          @PathVariable String trainKey) {
        return ResponseEntity.ok(releaseTrainService.getTrain(documentId, trainKey));
    }

    /** 列车预检：逐语言缺段、源段版本差异、术语违规与当前发布指针；只读不写数据。 */
    @GetMapping("/{documentId}/release-trains/{trainKey}/precheck")
    public ResponseEntity<ApiDtos.PrecheckResponse> precheck(@PathVariable long documentId,
                                                             @PathVariable String trainKey) {
        return ResponseEntity.ok(releaseTrainService.precheck(documentId, trainKey));
    }

    /** 进入 READY：预检通过后冻结候选集合、源文摘要、术语版本与预检结果。 */
    @PostMapping("/{documentId}/release-trains/{trainKey}/ready")
    public ResponseEntity<String> ready(@PathVariable long documentId, @PathVariable String trainKey,
                                        @Valid @RequestBody ApiDtos.TrainTransitionRequest request) {
        String operation = "POST /api/documents/" + documentId + "/release-trains/" + trainKey + "/ready";
        return writeExecutor.execute(request.requestId(), hash(operation, request),
                () -> WriteResult.of(200, releaseTrainService.ready(documentId, trainKey))).toResponseEntity();
    }

    /** 整列取消：仅 DRAFT/READY 可取消。 */
    @PostMapping("/{documentId}/release-trains/{trainKey}/cancel")
    public ResponseEntity<String> cancel(@PathVariable long documentId, @PathVariable String trainKey,
                                         @Valid @RequestBody ApiDtos.TrainTransitionRequest request) {
        String operation = "POST /api/documents/" + documentId + "/release-trains/" + trainKey + "/cancel";
        return writeExecutor.execute(request.requestId(), hash(operation, request),
                () -> WriteResult.of(200, releaseTrainService.cancel(documentId, trainKey))).toResponseEntity();
    }

    /** 激活：到达计划时刻后单事务重查并原子切换全部语言发布指针，生成全部语言不可变快照。 */
    @PostMapping("/{documentId}/release-trains/{trainKey}/activate")
    public ResponseEntity<String> activate(@PathVariable long documentId, @PathVariable String trainKey,
                                           @Valid @RequestBody ApiDtos.TrainTransitionRequest request) {
        String operation = "POST /api/documents/" + documentId + "/release-trains/" + trainKey + "/activate";
        return writeExecutor.execute(request.requestId(), hash(operation, request),
                () -> WriteResult.of(201, releaseTrainService.activate(documentId, trainKey)))
                .toResponseEntity();
    }

    /** 查询指定发布列车版本的全部语言快照，按语言码稳定排序。 */
    @GetMapping("/{documentId}/train-releases/{releaseTrainVersion}")
    public ResponseEntity<String> getTrainRelease(@PathVariable long documentId,
                                                  @PathVariable int releaseTrainVersion) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(releaseTrainService.getTrainRelease(documentId, releaseTrainVersion));
    }

    /** 查询文档全部语言的当前发布指针，按语言码稳定排序。 */
    @GetMapping("/{documentId}/release-pointers")
    public ResponseEntity<ApiDtos.ReleasePointerListResponse> getPointers(@PathVariable long documentId) {
        return ResponseEntity.ok(releaseTrainService.getPointers(documentId));
    }

    /** 列车创建请求摘要：语言候选规范化（小写、去空白、按语言码排序）后计算，locale 换序视为同参。 */
    private String trainHash(String operation, ApiDtos.CreateReleaseTrainRequest request) {
        List<ApiDtos.TrainLocaleInput> normalized = request.locales().stream()
                .map(l -> new ApiDtos.TrainLocaleInput(l.locale().trim().toLowerCase(Locale.ROOT),
                        l.translationVersion(), l.expectedVersion()))
                .sorted(Comparator.comparing(ApiDtos.TrainLocaleInput::locale))
                .toList();
        return hash(operation, new ApiDtos.CreateReleaseTrainRequest(request.requestId(), request.trainKey(),
                request.sourceDocumentVersion(), request.plannedAt(), normalized));
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
