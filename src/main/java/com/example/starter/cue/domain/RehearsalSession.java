package com.example.starter.cue.domain;

import java.time.LocalDateTime;

/**
 * 排练场次。开始时冻结所绑定版本的提示点快照，之后版本修改不影响本场。
 *
 * @param id        场次主键
 * @param production 剧目名称
 * @param versionId 绑定的提示点版本 ID
 * @param status    场次状态：PENDING 未开始 / RUNNING 排练中 / ENDED 已结束
 * @param completed 结束时是否已按序执行完全部提示点；未结束为 null
 * @param createdAt 创建时间（Asia/Shanghai）
 * @param startedAt 开始时间；未开始为 null
 * @param endedAt   结束时间；未结束为 null
 */
public record RehearsalSession(long id, String production, long versionId, String status,
                               Boolean completed, LocalDateTime createdAt,
                               LocalDateTime startedAt, LocalDateTime endedAt) {

    /** 未开始，可重新绑定最新冻结版本。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 排练中，接受按序回报。 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 已结束，不再接受回报。 */
    public static final String STATUS_ENDED = "ENDED";
}