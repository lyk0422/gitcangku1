package com.example.starter.observation;

import java.time.Instant;

/**
 * 闭合导线成果记录：对应 traverse_result 表的一行，生成后冻结，永不修改。
 *
 * @param resultId       成果唯一标识
 * @param requestId      生成该成果的请求标识
 * @param operator       操作者标识
 * @param edgeCount      边数（3 至 10）
 * @param closureDx      X 向闭合差（米，十进制原文）
 * @param closureDy      Y 向闭合差（米，十进制原文）
 * @param totalW         边长总和（米，正数）
 * @param generatedAtUtc 成果生成时刻（UTC）
 */
public record TraverseResult(
        String resultId,
        String requestId,
        String operator,
        int edgeCount,
        String closureDx,
        String closureDy,
        String totalW,
        Instant generatedAtUtc) {
}
