package com.example.starter.repo;

import com.example.starter.domain.Draft;
import com.example.starter.domain.DraftSegment;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 编排草稿持久化：每个“频道＋业务日”一份，整份替换，乐观版本控制。
 */
public interface DraftRepository {

    Optional<Draft> find(String channelId, LocalDate businessDay);

    /**
     * 原子地比较版本并整份替换草稿。
     *
     * @param expectedVersion 期望的当前版本；0 表示草稿尚不存在
     * @param newVersion      替换后的新版本（expectedVersion + 1）
     * @param segments        新片段列表
     * @return 版本匹配并替换成功返回 true；版本不符返回 false，不产生任何修改
     */
    boolean replace(String channelId, LocalDate businessDay, long expectedVersion,
                    long newVersion, List<DraftSegment> segments);
}
