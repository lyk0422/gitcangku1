package com.example.starter.domain;

import java.time.Instant;

/**
 * 标签附着记录：有效期左闭右开 [attachedAt, detachedAt)，摘除时冻结为历史，不覆盖。
 *
 * @param id 附着记录主键
 * @param tagCode 标签编号
 * @param animalCode 动物编号（合成）
 * @param stationCode 执行附着的站点编号
 * @param attachedAt 附着开始时刻（UTC，左闭）
 * @param detachedAt 摘除时刻（UTC，右开；null 表示仍附着）
 * @param requestId 幂等请求键
 */
public record Attachment(long id, String tagCode, String animalCode, String stationCode,
        Instant attachedAt, Instant detachedAt, String requestId) {

    /**
     * 判断给定时刻是否落在本附着有效期内（左闭右开）。
     */
    public boolean covers(Instant at) {
        return !at.isBefore(attachedAt) && (detachedAt == null || at.isBefore(detachedAt));
    }
}