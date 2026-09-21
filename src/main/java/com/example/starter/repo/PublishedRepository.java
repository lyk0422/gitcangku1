package com.example.starter.repo;

import com.example.starter.domain.DraftSegment;
import com.example.starter.domain.PublishedSchedule;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 已发布编排持久化：快照只读，发布动作原子地比较版本并写入新快照。
 */
public interface PublishedRepository {

    Optional<PublishedSchedule> find(String channelId, LocalDate businessDay);

    /**
     * 原子地比较发布版本并写入新快照。
     *
     * @param expectedVersion 期望的当前发布版本；0 表示从未发布
     * @param newVersion      新发布版本（expectedVersion + 1）
     * @param draftVersion    生成快照的草稿版本
     * @param segments        快照片段
     * @return 版本匹配并发布成功返回 true；版本冲突返回 false，不产生任何修改
     */
    boolean publish(String channelId, LocalDate businessDay, long expectedVersion,
                    long newVersion, long draftVersion, List<DraftSegment> segments);
}
