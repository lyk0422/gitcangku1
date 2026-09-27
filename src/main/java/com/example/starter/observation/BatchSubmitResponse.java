package com.example.starter.observation;

import java.util.List;

/**
 * 观测批量提交响应：返回批次实际条数、受影响条数与逐条明细（含固化维护标记）。
 *
 * @param requestId     批量提交请求标识
 * @param submittedCount 本批提交数量（实际值）
 * @param affectedCount  本批命中维护窗口的提交数量（实际值）
 * @param submissions    逐条提交结果，顺序与请求明细一致
 */
public record BatchSubmitResponse(
        String requestId,
        int submittedCount,
        int affectedCount,
        List<SubmissionResponse> submissions) {
}
