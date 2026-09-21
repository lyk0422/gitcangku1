package com.example.starter.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 某一“频道＋业务日”的编排草稿。整份替换，版本从 1 开始递增。
 *
 * @param channelId   频道 ID
 * @param businessDay 业务日（Asia/Shanghai 日历日）
 * @param version     草稿版本，每次成功替换加 1
 * @param segments    片段列表，按开始时间升序保存
 */
public record Draft(String channelId, LocalDate businessDay, long version,
                    List<DraftSegment> segments) {
}
