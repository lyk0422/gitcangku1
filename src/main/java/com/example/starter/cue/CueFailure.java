package com.example.starter.cue;

import java.time.LocalDateTime;

/**
 * 提示点执行失败回报，记录原因但不推进已执行序号。
 *
 * @param id 失败记录ID
 * @param sessionId 排练场次ID
 * @param seq 回报失败的提示点序号
 * @param requestId 回报请求ID，失败不占用幂等键
 * @param reason 失败原因
 * @param reportedAt 回报时间，服务器本地时区
 */
public record CueFailure(long id, long sessionId, int seq, String requestId,
                         String reason, LocalDateTime reportedAt) {
}