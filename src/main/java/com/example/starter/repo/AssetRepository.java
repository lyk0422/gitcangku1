package com.example.starter.repo;

import com.example.starter.domain.Asset;

import java.util.Optional;

/**
 * 素材持久化。
 */
public interface AssetRepository {

    /**
     * 新增素材；ID 已存在时抛 {@link DuplicateKeyException}。
     */
    void insert(Asset asset);

    Optional<Asset> findById(String id);
}
