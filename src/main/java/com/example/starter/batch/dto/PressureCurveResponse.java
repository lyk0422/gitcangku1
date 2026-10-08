package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;
import com.example.starter.batch.TestOutcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 压力曲线提交结果。
 *
 * @param points        按提交身份顺序返回的节点（不重排）
 * @param segments      逐段线性变化的下降信息
 * @param totalDecline  各段正下降之和（千帕），精确值，非首末差
 * @param maxSegmentRate 下降段最大逐段速率（千帕/秒），未提前舍入
 */
public record PressureCurveResponse(
        String batchKey,
        String requestId,
        String actorId,
        TestOutcome result,
        String rejectReason,
        int nodeCount,
        BigDecimal totalDeclineKpa,
        BigDecimal maxSegmentRateKpa,
        long expectedVersion,
        long batchVersion,
        BatchStatus batchStatus,
        List<NodeView> points,
        List<SegmentView> segments,
        Instant createdAt
) {

    /**
     * 节点视图：seq 为提交顺序（从 1 开始），pressureKpa 为提交原文。
     */
    public record NodeView(int seq, long second, String pressureKpa) {
    }

    /**
     * 分段视图：declineKpa 为该段正下降（千帕，上升段为 0），rateKpaPerSecond 为精确逐段速率，
     * rateExceeded 标记该段速率是否超过每秒 0.2 千帕。
     */
    public record SegmentView(int seq, long fromSecond, long toSecond,
                              BigDecimal declineKpa, BigDecimal rateKpaPerSecond,
                              boolean rateExceeded) {
    }
}
