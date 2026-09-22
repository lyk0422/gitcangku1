package com.example.starter.translation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * API 请求/响应模型。所有写请求携带全局唯一 requestId 用于幂等去重。
 */
public final class Dtos {

    private Dtos() {
    }

    /** 建文档请求：1~5 种目标语言，至少 1 个初始段落。 */
    public record CreateDocumentRequest(String requestId, String documentId, List<String> targetLanguages,
                                        List<SegmentInput> segments) {
    }

    /** 段落输入。 */
    public record SegmentInput(String segmentId, String sourceText) {
    }

    /** 增段落请求。 */
    public record AddSegmentRequest(String requestId, String segmentId, String sourceText) {
    }

    /** 源文修订请求。 */
    public record ReviseSourceRequest(String requestId, String sourceText) {
    }

    /** 译文提交请求：sourceVersion 必须匹配当前源文版本。 */
    public record SubmitTranslationRequest(String requestId, String language, String body, Integer sourceVersion) {
    }

    /** 译文批准请求：必须同时匹配当前源文版本与译文版本。 */
    public record ApproveRequest(String requestId, Integer sourceVersion, Integer translationVersion) {
    }

    /** 发布请求：携带期望草稿版本与期望发布版本。 */
    public record PublishRequest(String requestId, Integer expectedDraftVersion, Integer expectedPublishedVersion) {
    }

    /** 段落视图。 */
    public record SegmentView(String segmentId, String sourceText, int sourceVersion) {
    }

    /** 文档视图。 */
    public record DocumentView(String documentId, int draftVersion, int publishedVersion,
                               List<String> targetLanguages, List<SegmentView> segments, Instant createdAt) {
    }

    /** 译文视图。 */
    public record TranslationView(String segmentId, String language, String body, String author,
                                  int sourceVersion, int translationVersion) {
    }

    /** 发布结果视图。 */
    public record PublishView(String documentId, int draftVersion, int publishedVersion, Instant publishedAt) {
    }

    /** 批准结果视图。 */
    public record ApprovalView(String segmentId, String language, String reviewer,
                               int sourceVersion, int translationVersion, Instant approvedAt) {
    }

    /** 发布快照视图：完整只读内容。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PublicationView(String documentId, int publishedVersion, Instant publishedAt,
                                  List<SnapshotSegmentView> segments) {
    }

    /** 快照段落视图（含全部目标语言译文）。 */
    public record SnapshotSegmentView(String segmentId, String sourceText, int sourceVersion,
                                      List<TranslationView> translations) {
    }

    /** 统一错误体。 */
    public record ErrorView(String code, String message) {
    }
}
