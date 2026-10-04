package com.example.starter.cue;

import java.time.LocalDateTime;

/**
 * 排练场次。
 *
 * @param id 场次ID
 * @param versionId 绑定的编排版本ID
 * @param status 场次状态：SCHEDULED未开始/RUNNING进行中/FINISHED已结束
 * @param lastExecutedSeq 已成功执行的最后一个提示点序号，0表示未执行
 * @param createdAt 创建时间，服务器本地时区
 * @param startedAt 开始时间，未开始为null
 * @param finishedAt 结束时间，未结束为null
 */
public record RehearsalSession(long id, long versionId, SessionStatus status,
                               int lastExecutedSeq, LocalDateTime createdAt,
                               LocalDateTime startedAt, LocalDateTime finishedAt) {
}