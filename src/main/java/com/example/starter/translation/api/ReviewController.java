package com.example.starter.translation.api;

import com.example.starter.translation.service.ReviewService;
import com.example.starter.translation.service.WriteExecutor;
import com.example.starter.translation.service.WriteResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
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

/**
 * 双阶段法定人数评审 REST API：按语言版本化策略、投票/改票、只读评审矩阵与历史票查询。
 * 写操作携带全局唯一 requestId 做幂等去重；投票以 X-Actor-Id 表示审核人。
 */
@RestController
@RequestMapping("/api/documents")
public class ReviewController {

    private final ReviewService reviewService;
    private final WriteExecutor writeExecutor;
    private final ObjectMapper objectMapper;

    public ReviewController(ReviewService reviewService, WriteExecutor writeExecutor, ObjectMapper objectMapper) {
        this.reviewService = reviewService;
        this.writeExecutor = writeExecutor;
        this.objectMapper = objectMapper;
    }

    /** 新增并激活某语言的策略版本：expectedPolicyVersion 为当前激活版本（无策略传 0）。 */
    @PutMapping("/{documentId}/review-policies/{language}")
    public ResponseEntity<String> putPolicy(@PathVariable long documentId, @PathVariable String language,
                                            @Valid @RequestBody ApiDtos.PutReviewPolicyRequest request) {
        String operation = "PUT /api/documents/" + documentId + "/review-policies/" + language;
        return writeExecutor.execute(request.requestId(), hash(operation, request),
                () -> WriteResult.of(201, reviewService.putPolicy(documentId, language, request)))
                .toResponseEntity();
    }

    /** 投票/改票：审核人取 X-Actor-Id，对精确四版本在 LANGUAGE/COMPLIANCE 阶段投票。 */
    @PostMapping("/{documentId}/segments/{segmentId}/translations/{language}/votes")
    public ResponseEntity<String> castVote(@PathVariable long documentId, @PathVariable String segmentId,
                                           @PathVariable String language,
                                           @RequestHeader("X-Actor-Id") String actorId,
                                           @Valid @RequestBody ApiDtos.CastVoteRequest request) {
        String operation = "POST /api/documents/" + documentId + "/segments/" + segmentId
                + "/translations/" + language + "/votes";
        return writeExecutor.execute(request.requestId(), hash(operation, actorId, request),
                () -> WriteResult.of(201, reviewService.castVote(
                        documentId, segmentId, language, actorId, request))).toResponseEntity();
    }

    /** 当前评审矩阵：全部段落 × 目标语言两阶段状态（只读）。 */
    @GetMapping("/{documentId}/review-matrix")
    public ResponseEntity<ApiDtos.ReviewMatrixResponse> getMatrix(@PathVariable long documentId) {
        return ResponseEntity.ok(reviewService.getMatrix(documentId));
    }

    /** 历史票查询：含已不计入法定人数的旧票（只读审计）。 */
    @GetMapping("/{documentId}/segments/{segmentId}/translations/{language}/votes")
    public ResponseEntity<ApiDtos.VoteHistoryResponse> getVoteHistory(@PathVariable long documentId,
                                                                      @PathVariable String segmentId,
                                                                      @PathVariable String language) {
        return ResponseEntity.ok(reviewService.getVoteHistory(documentId, segmentId, language));
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
