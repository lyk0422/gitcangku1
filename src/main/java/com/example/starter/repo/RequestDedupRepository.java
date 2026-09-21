package com.example.starter.repo;

import com.example.starter.domain.RequestRecord;

import java.util.Optional;

/**
 * 请求去重记录持久化。占位插入、结果回填与业务写入在同一事务提交；
 * 业务失败时占位记录随事务回滚，不占用 requestId。
 */
public interface RequestDedupRepository {

    /**
     * 插入去重占位记录；requestId 已存在时抛 {@link DuplicateKeyException}。
     */
    void insertPlaceholder(String requestId, String operation, String fingerprint);

    Optional<RequestRecord> find(String requestId);

    /**
     * 回填成功结果。
     */
    void complete(String requestId, String resultJson);

    /**
     * 业务失败时放弃占位。JDBC 实现依赖事务回滚，可为空操作；
     * 非事务实现应删除占位记录。
     */
    void abandon(String requestId);
}
