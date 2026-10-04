package com.example.starter.cue;

import java.time.LocalDateTime;

/**
 * 提示点成功执行记录，同一序号只留一条。
 *
 * @param id 执行记录ID
 * @param sessionId 排练场次ID
 * @param seq 已执行提示点序号
 * @param requestId 幂等请求ID，仅成功回报占用
 * @param payloadHash 回报参数指纹，用于同requestId异参判重
 * @param executedAt 执行时间，服务器本地时区
 */
public record CueExecution(long id, long sessionId, int seq, String requestId,
                           String payloadHash, LocalDateTime executedAt) {
}