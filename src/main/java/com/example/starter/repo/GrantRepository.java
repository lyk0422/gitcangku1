package com.example.starter.repo;

import com.example.starter.domain.Grant;

import java.time.Instant;
import java.util.Optional;

/**
 * 授权持久化。授权创建后只能撤销，不提供更新。
 */
public interface GrantRepository {

    void insert(Grant grant);

    Optional<Grant> findById(String id);

    /**
     * 是否存在未撤销且有效区间完整覆盖 [start, end) 的授权。
     */
    boolean existsCovering(String channelId, String assetId, Instant start, Instant end);

    /**
     * 将授权标记为已撤销；返回撤销后的授权，授权不存在时返回 empty。
     * 对已撤销授权重复调用应保持已撤销状态。
     */
    Optional<Grant> markRevoked(String id);
}
