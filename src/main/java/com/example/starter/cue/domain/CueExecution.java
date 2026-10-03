package com.example.starter.cue.domain;

import java.time.LocalDateTime;

/**
 * 提示点执行回报记录。同一序号仅允许一条 SUCCESS；FAILED 记录原因但不推进已执行序号，
 * 且失败不消耗幂等键，可用同一 requestId 重试。
 *
 * @param id         回报主键
 * @param sessionId  所属场次 ID
 * @param seq        回报的提示点序号
 * @param requestId  操作员幂等请求 ID
 * @param operator   回报操作员
 * @param result     回报结果：SUCCESS 成功 / FAILED 失败
 * @param failReason 失败原因，仅 FAILED 有值，SUCCESS 为 null
 * @param reportedAt 回报时间（Asia/Shanghai）
 */
public record CueExecution(long id, long sessionId, int seq, String requestId, String operator,
                           String result, String failReason, LocalDateTime reportedAt) {

    /** 执行成功，推进已执行序号。 */
    public static final String RESULT_SUCCESS = "SUCCESS";
    /** 执行失败，记录原因但不推进已执行序号。 */
    public static final String RESULT_FAILED = "FAILED";
}