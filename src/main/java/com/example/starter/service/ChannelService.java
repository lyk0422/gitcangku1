package com.example.starter.service;

import com.example.starter.common.ApiException;
import com.example.starter.domain.Channel;
import com.example.starter.repo.AssetRepository;
import com.example.starter.repo.ChannelRepository;
import com.example.starter.repo.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 频道服务。
 */
@Service
public class ChannelService {

    private final ChannelRepository channelRepository;
    private final AssetRepository assetRepository;

    public ChannelService(ChannelRepository channelRepository, AssetRepository assetRepository) {
        this.channelRepository = channelRepository;
        this.assetRepository = assetRepository;
    }

    /**
     * 创建频道并指定保底素材。保底素材必须已存在且创建后不可撤销。
     */
    public Channel create(String id, String fallbackAssetId) {
        if (id == null || id.isBlank()) {
            throw ApiException.badRequest("INVALID_CHANNEL", "频道 ID 不能为空");
        }
        if (fallbackAssetId == null || fallbackAssetId.isBlank()) {
            throw ApiException.badRequest("INVALID_CHANNEL", "保底素材 ID 不能为空");
        }
        if (assetRepository.findById(fallbackAssetId).isEmpty()) {
            throw ApiException.notFound("ASSET_NOT_FOUND", "保底素材不存在: " + fallbackAssetId);
        }
        Channel channel = new Channel(id, fallbackAssetId);
        try {
            channelRepository.insert(channel);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("CHANNEL_EXISTS", "频道已存在: " + id);
        }
        return channel;
    }

    public Channel get(String id) {
        return channelRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("CHANNEL_NOT_FOUND", "频道不存在: " + id));
    }
}
