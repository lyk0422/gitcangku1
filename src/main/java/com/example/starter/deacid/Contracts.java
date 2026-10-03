package com.example.starter.deacid;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 档案脱酸业务链接口与视图对象。
 */
public final class Contracts {

    private Contracts() {
    }

    /** 档案页登记请求，collectionNo 为唯一馆藏号。 */
    public record CreatePageRequest(@NotBlank String collectionNo) {
    }

    /** 档案页登记结果。 */
    public record PageResponse(long pageId, String collectionNo) {
    }

    /** 批次创建请求，batchNo 为唯一批次编号。 */
    public record CreateBatchRequest(@NotBlank String batchNo) {
    }

    /** 批次创建结果，status 为 OPEN 或 SEALED。 */
    public record BatchResponse(long batchId, String batchNo, String status) {
    }

    /** 入组请求，requestId 为幂等键。 */
    public record EnrollRequest(@NotBlank String requestId, @NotBlank String collectionNo) {
    }

    /** 入组结果，replayed=true 表示同键同参重放。 */
    public record EnrollResponse(long batchPageId, long batchId, long pageId, String status, boolean replayed) {
    }

    /** 处理轮次追加请求，pH 无量纲，合规范围 6.5 至 8.5。 */
    public record TreatmentRequest(@NotNull BigDecimal phBefore, @NotNull BigDecimal phAfter,
                                   @NotBlank String reagentBatchNo) {
    }

    /** 处理轮次追加结果。 */
    public record TreatmentResponse(long recordId, int roundNo) {
    }

    /** 封存请求，requestId 为幂等键。 */
    public record SealRequest(@NotBlank String requestId) {
    }

    /** 封存成功结果，replayed=true 表示同键重放。 */
    public record SealResponse(long batchId, String status, boolean replayed) {
    }

    /** 问题页及拒绝原因清单。 */
    public record ProblemPage(String collectionNo, List<String> reasons) {
    }

    /** 质检退回响应体。 */
    public record SealRejectedBody(String code, String message, List<ProblemPage> problems, boolean replayed) {
    }

    /** 统一错误响应体。 */
    public record ErrorBody(String code, String message) {
    }

    /** 归还释放占用结果。 */
    public record ReleaseResponse(long batchPageId, String status) {
    }

    /** 处理轮次视图：含批次状态、pH 前后值与药剂来源。 */
    public record TreatmentRoundView(int roundNo, long batchId, String batchNo, String batchStatus,
                                     BigDecimal phBefore, BigDecimal phAfter, String reagentBatchNo,
                                     LocalDateTime recordedAt) {
    }

    /** 档案页处理轮次查询结果。 */
    public record PageTreatmentsResponse(String collectionNo, List<TreatmentRoundView> rounds) {
    }
}