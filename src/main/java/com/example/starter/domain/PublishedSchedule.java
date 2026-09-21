package com.example.starter.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 某一“频道＋业务日”的已发布编排快照，只读；授权撤销不改写历史快照。
 *
 * @param channelId    频道 ID
 * @param businessDay  业务日（Asia/Shanghai 日历日）
 * @param version      发布版本，每次成功发布加 1
 * @param draftVersion 生成该快照时的草稿版本
 * @param segments     快照片段列表，按开始时间升序
 */
public record PublishedSchedule(String channelId, LocalDate businessDay, long version,
                                long draftVersion, List<DraftSegment> segments) {
}
