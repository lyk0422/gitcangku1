package com.example.starter.service;

import com.example.starter.common.ApiException;
import com.example.starter.domain.Grant;
import com.example.starter.repo.AssetRepository;
import com.example.starter.repo.ChannelRepository;
import com.example.starter.repo.GrantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 授权服务：授权创建后只能撤销。
 */
@Service
public class GrantService {

    private final GrantRepository grantRepository;
    private final ChannelRepository channelRepository;
    private final AssetRepository assetRepository;
    private final IdempotencyExecutor idempotency;

    public GrantService(GrantRepository grantRepository, ChannelRepository channelRepository,
                        AssetRepository assetRepository, IdempotencyExecutor idempotency) {
        this.grantRepository = grantRepository;
        this.channelRepository = channelRepository;
        this.assetRepository = assetRepository;
        this.idempotency = idempotency;
    }

    /**
     * 创建授权。频道或素材不存在抛 404；有效区间非左闭右开的正区间抛 422。
     */
    public Grant create(String channelId, String assetId, Instant validFrom, Instant validTo) {
        if (channelRepository.findById(channelId).isEmpty()) {
            throw ApiException.notFound("CHANNEL_NOT_FOUND", "频道不存在: " + channelId);
        }
        if (assetId == null || assetId.isBlank()) {
            throw ApiException.badRequest("INVALID_GRANT", "素材 ID 不能为空");
        }
        if (assetRepository.findById(assetId).isEmpty()) {
            throw ApiException.notFound("ASSET_NOT_FOUND", "素材不存在: " + assetId);
        }
        if (validFrom == null || validTo == null || !validFrom.isBefore(validTo)) {
            throw ApiException.unprocessable("INVALID_GRANT_INTERVAL",
                    "授权有效区间必须满足 validFrom < validTo");
        }
        Grant grant = new Grant(UUID.randomUUID().toString(), channelId, assetId,
                validFrom, validTo, false);
        grantRepository.insert(grant);
        return grant;
    }

    /**
     * 撤销授权，携带 requestId 幂等。授权不存在抛 404；
     * 已撤销的授权再次撤销（新 requestId）为幂等成功，返回当前状态。
     */
    @Transactional
    public Grant revoke(String grantId, String requestId) {
        return idempotency.execute("REVOKE_GRANT", requestId, "REVOKE_GRANT|" + grantId,
                Grant.class, () -> {
                    Grant grant = grantRepository.findById(grantId)
                            .orElseThrow(() -> ApiException.notFound("GRANT_NOT_FOUND",
                                    "授权不存在: " + grantId));
                    if (grant.revoked()) {
                        return grant;
                    }
                    return grantRepository.markRevoked(grantId)
                            .orElseThrow(() -> ApiException.notFound("GRANT_NOT_FOUND",
                                    "授权不存在: " + grantId));
                });
    }

    public Grant get(String id) {
        return grantRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("GRANT_NOT_FOUND", "授权不存在: " + id));
    }
}
