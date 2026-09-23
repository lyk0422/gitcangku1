package com.example.starter.consent.dto;

import java.time.Instant;
import java.util.List;

/**
 * 当前有效委托链只读视图：自主体到目标处理方的最短有效路径。
 *
 * @param subjectKey 授权主体标识
 * @param purpose    用途
 * @param epoch      授权代次
 * @param callerKey  目标处理方标识（主体直查时等于主体标识）
 * @param edges      有序边列表；主体直写场景为空列表
 * @param evaluatedAt 查询评估时刻（UTC）
 */
public record ChainResponse(
        String subjectKey,
        String purpose,
        int epoch,
        String callerKey,
        List<ChainEdgeResponse> edges,
        Instant evaluatedAt) {
}
