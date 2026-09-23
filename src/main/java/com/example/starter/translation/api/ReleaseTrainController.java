package com.example.starter.translation.api;

import com.example.starter.translation.service.ReleaseTrainService;
import com.example.starter.translation.service.TranslationService;
import com.example.starter.translation.service.WriteExecutor;
import com.example.starter.translation.service.WriteResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 发布列车 REST API：创建、只读预检、READY 冻结、整列取消、激活发布，以及列车/快照/指针只读查询。
 * 写操作复用全局 requestId 幂等机制：同参重放首次快照，候选 locale 换序经规范化后视为同参，
 * 异参 409，失败不占键。
 */
@RestController
@RequestMapping("/api")
public class ReleaseTrainController {

    private final ReleaseTrainService trainService;
    private final TranslationService translationService;
    private final WriteExecutor writeExecutor;
    private final ObjectMapper objectMapper;

    public ReleaseTrainController(ReleaseTrainService trainService, TranslationService translationService,
                                  WriteExecutor writeExecutor, ObjectMapper objectMapper) {
        this.trainService = trainService;
        this.translationService = translationService;
        this.writeExecutor = writeExecutor;
        this.objectMapper = objectMapper;
    }

    /** 创建发布列车（DRAFT）。 */
    @PostMapping("/documents/{documentId}/release-trains")
    public ResponseEntity<String> createTrain(@PathVariable long documentId,
                                              @Valid @RequestBody TrainDtos.CreateTrainRequest request) {
        String operation = "POST /api/documents/" + documentId + "/release-trains";
        return writeExecutor.execute(request.requestId(), hash(operation, canonicalCreate(request)),
                () -> WriteResult.of(201, trainService.createTrain(documentId, request))).toResponseEntity();
    }

    /** 只读预检，不写数据。 */
    @GetMapping("/release-trains/{trainKey}/precheck")
    public ResponseEntity<TrainDtos.PrecheckResponse> precheck(@PathVariable String trainKey) {
        return ResponseEntity.ok(trainService.precheck(trainKey));
    }

    /** 进入 READY：冻结候选集合、源文摘要、术语版本与预检结果。 */
    @PostMapping("/release-trains/{trainKey}/ready")
    public ResponseEntity<String> markReady(@PathVariable String trainKey,
                                            @Valid @RequestBody TrainDtos.TrainActionRequest request) {
        String operation = "POST /api/release-trains/" + trainKey + "/ready";
        return writeExecutor.execute(request.requestId(), hash(operation, request.requestId()),
                () -> WriteResult.of(200, trainService.markReady(trainKey))).toResponseEntity();
    }

    /** 整列取消。 */
    @PostMapping("/release-trains/{trainKey}/cancel")
    public ResponseEntity<String> cancel(@PathVariable String trainKey,
                                         @Valid @RequestBody TrainDtos.TrainActionRequest request) {
        String operation = "POST /api/release-trains/" + trainKey + "/cancel";
        return writeExecutor.execute(request.requestId(), hash(operation, request.requestId()),
                () -> WriteResult.of(200, trainService.cancel(trainKey))).toResponseEntity();
    }

    /** 到达计划时刻后一次激活发布。 */
    @PostMapping("/release-trains/{trainKey}/activate")
    public ResponseEntity<String> activate(@PathVariable String trainKey,
                                           @Valid @RequestBody TrainDtos.TrainActionRequest request) {
        String operation = "POST /api/release-trains/" + trainKey + "/activate";
        return writeExecutor.execute(request.requestId(), hash(operation, request.requestId()),
                () -> WriteResult.of(201, trainService.activate(trainKey))).toResponseEntity();
    }

    /** 查询单个列车。 */
    @GetMapping("/release-trains/{trainKey}")
    public ResponseEntity<TrainDtos.TrainView> getTrain(@PathVariable String trainKey) {
        return ResponseEntity.ok(trainService.getTrain(trainKey));
    }

    /** 查询文档下全部列车（trainKey 稳定排序）。 */
    @GetMapping("/documents/{documentId}/release-trains")
    public ResponseEntity<List<TrainDtos.TrainView>> listTrains(@PathVariable long documentId) {
        return ResponseEntity.ok(trainService.listTrains(documentId));
    }

    /** 查询列车全部语言快照（locale 稳定排序）。 */
    @GetMapping("/release-trains/{trainKey}/snapshots")
    public ResponseEntity<List<TrainDtos.TrainSnapshotView>> listSnapshots(@PathVariable String trainKey) {
        return ResponseEntity.ok(trainService.listSnapshots(trainKey));
    }

    /** 查询列车单个语言快照。 */
    @GetMapping("/release-trains/{trainKey}/snapshots/{locale}")
    public ResponseEntity<TrainDtos.TrainSnapshotView> getSnapshot(@PathVariable String trainKey,
                                                                  @PathVariable String locale) {
        return ResponseEntity.ok(trainService.getSnapshot(trainKey, locale));
    }

    /** 查询文档当前全部发布指针（locale 稳定排序）。 */
    @GetMapping("/documents/{documentId}/release-pointers")
    public ResponseEntity<List<TrainDtos.ReleasePointerView>> listPointers(@PathVariable long documentId) {
        return ResponseEntity.ok(trainService.listPointers(documentId));
    }

    /** 撤批：删除段落语言的当前批准。 */
    @DeleteMapping("/documents/{documentId}/segments/{segmentId}/translations/{language}/approval")
    public ResponseEntity<String> unapprove(@PathVariable long documentId, @PathVariable String segmentId,
                                            @PathVariable String language,
                                            @Valid @RequestBody TrainDtos.UnapproveRequest request) {
        String operation = "DELETE /api/documents/" + documentId + "/segments/" + segmentId
                + "/translations/" + language + "/approval";
        return writeExecutor.execute(request.requestId(), hash(operation, request.requestId()),
                () -> {
                    translationService.unapprove(documentId, segmentId, language);
                    return WriteResult.of(200, new TrainDtos.UnapproveResponse(
                            documentId, segmentId, language.toLowerCase()));
                }).toResponseEntity();
    }

    /**
     * 规范化创建请求用于幂等摘要：候选按 locale 排序，locale 换序产生相同 JSON 与相同摘要。
     */
    private CanonicalCreateTrain canonicalCreate(TrainDtos.CreateTrainRequest request) {
        List<TrainDtos.CandidateInput> sorted = request.candidates().stream()
                .sorted(Comparator.comparing(c -> c.locale().trim().toLowerCase()))
                .map(c -> new TrainDtos.CandidateInput(
                        c.locale().trim().toLowerCase(), c.translationVersion(), c.expectedVersion()))
                .toList();
        return new CanonicalCreateTrain(request.requestId(), request.trainKey(),
                request.sourceDocumentVersion(), request.scheduledAt(), sorted);
    }

    /** 创建请求的规范化形式，仅用于计算幂等摘要。 */
    private record CanonicalCreateTrain(String requestId, String trainKey, int sourceDocumentVersion,
                                        java.time.Instant scheduledAt,
                                        List<TrainDtos.CandidateInput> candidates) {
    }

    /** 计算请求摘要：操作（含路径变量）+ 规范化参数的 SHA-256。 */
    private String hash(String operation, Object canonicalPart) {
        try {
            String canonical = operation + '\n' + objectMapper.writeValueAsString(canonicalPart);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (Exception e) {
            throw new IllegalArgumentException("请求序列化失败", e);
        }
    }
}
