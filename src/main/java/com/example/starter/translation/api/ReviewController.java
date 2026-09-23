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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 双阶段法定人数评审 REST API。
 * 写操作（策略配置、投票/改票）携带全局唯一 requestId 做幂等去重；投票以 X-Actor-Id 表示审核人。
 * 评审矩阵与历史票查询为只读。
 */
@RestController
@RequestMapping("/api/documents")
public class ReviewController {

    private final ReviewService reviewService;
    private final WriteExecutor writeExecutor;
    private final ObjectMapper objectMapper;

    public ReviewController(ReviewService reviewService, WriteExecutor writeExecutor,
                            ObjectMapper objectMapper) {
        this.reviewService = reviewService;
        this.writeExecutor = writeExecutor;
        this.objectMapper = objectMapper;
    }

    /** 配置评审策略：为某语言新增策略版本并激活，仅作用于新投票；携带期望策略版本做乐观校验。 */
    @PutMapping("/{documentId}/review-policies/{language}")
    public ResponseEntity<String> configurePolicy(@PathVariable long documentId, @PathVariable String language,
                                                  @Valid @RequestBody ApiDtos.ConfigureReviewPolicyRequest request) {
        String operation = "PUT /api/documents/" + documentId + "/review-policies/" + language;
        return writeExecutor.execute(request.requestId(), hash(operation, request),
                () -> WriteResult.of(201, reviewService.configurePolicy(documentId, language, request)))
                .toResponseEntity();
    }

    /** 投票/改票：审核人取 X-Actor-Id，针对精确版本组合；改票须携带 expectedVoteVersion。 */
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

    /** 查询当前评审矩阵：全部段落 × 全部目标语言的各阶段状态与当前有效票统计（只读）。 */
    @GetMapping("/{documentId}/review-matrix")
    public ResponseEntity<ApiDtos.ReviewMatrixResponse> getReviewMatrix(@PathVariable long documentId) {
        return ResponseEntity.ok(reviewService.getReviewMatrix(documentId));
    }

    /** 查询历史票：含被改票取代的旧版本票，可按段落与语言过滤（只读）。 */
    @GetMapping("/{documentId}/votes")
    public ResponseEntity<ApiDtos.VoteHistoryResponse> getVoteHistory(
            @PathVariable long documentId,
            @RequestParam(required = false) String segmentId,
            @RequestParam(required = false) String language) {
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
