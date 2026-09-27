package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 批量离线提交响应：逐项给出裁决结果，顺序与请求 items 一致。
 *
 * @param requestId      批量请求的幂等标识
 * @param operator       提交操作者标识
 * @param submittedAtUtc 服务端接收提交时刻（UTC）
 * @param results        逐项裁决结果，按提交顺序排列
 */
public record BatchSubmissionResponse(
        String requestId,
        String operator,
        Instant submittedAtUtc,
        List<SubmissionResponse> results) {
}
