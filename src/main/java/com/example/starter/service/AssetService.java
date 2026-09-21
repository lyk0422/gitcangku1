package com.example.starter.service;

import com.example.starter.common.ApiException;
import com.example.starter.domain.Asset;
import com.example.starter.repo.AssetRepository;
import com.example.starter.repo.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 素材服务。
 */
@Service
public class AssetService {

    private final AssetRepository assetRepository;

    public AssetService(AssetRepository assetRepository) {
        this.assetRepository = assetRepository;
    }

    /**
     * 创建素材。ID 空白或时长非正整数抛 400；ID 已存在抛 409。
     */
    public Asset create(String id, Long durationMs) {
        if (id == null || id.isBlank()) {
            throw ApiException.badRequest("INVALID_ASSET", "素材 ID 不能为空");
        }
        if (durationMs == null || durationMs <= 0) {
            throw ApiException.badRequest("INVALID_ASSET", "素材时长必须为正整数毫秒");
        }
        Asset asset = new Asset(id, durationMs);
        try {
            assetRepository.insert(asset);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("ASSET_EXISTS", "素材已存在: " + id);
        }
        return asset;
    }

    public Asset get(String id) {
        return assetRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("ASSET_NOT_FOUND", "素材不存在: " + id));
    }
}
