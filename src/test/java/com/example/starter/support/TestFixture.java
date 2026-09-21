package com.example.starter.support;

import com.example.starter.common.TimeSupport;
import com.example.starter.domain.Asset;
import com.example.starter.domain.Channel;
import com.example.starter.domain.DraftSegment;
import com.example.starter.domain.Grant;
import com.example.starter.service.AssetService;
import com.example.starter.service.ChannelService;
import com.example.starter.service.DecisionService;
import com.example.starter.service.DraftService;
import com.example.starter.service.GrantService;
import com.example.starter.service.IdempotencyExecutor;
import com.example.starter.service.PublishService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 测试基座：以内存仓储组装各服务，并提供通用测试数据。
 */
public class TestFixture {

    public static final String FALLBACK_ASSET = "asset-fallback";
    public static final String ASSET_60S = "asset-60s";
    public static final String ASSET_30S = "asset-30s";
    public static final String CHANNEL = "channel-1";
    public static final LocalDate DAY = LocalDate.of(2026, 9, 21);

    public final InMemoryRepositories.Assets assets = new InMemoryRepositories.Assets();
    public final InMemoryRepositories.Channels channels = new InMemoryRepositories.Channels();
    public final InMemoryRepositories.Grants grants = new InMemoryRepositories.Grants();
    public final InMemoryRepositories.Drafts drafts = new InMemoryRepositories.Drafts();
    public final InMemoryRepositories.Published published = new InMemoryRepositories.Published();
    public final InMemoryRepositories.RequestDedup dedup = new InMemoryRepositories.RequestDedup();

    public final AssetService assetService = new AssetService(assets);
    public final ChannelService channelService = new ChannelService(channels, assets);
    public final IdempotencyExecutor idempotency = new IdempotencyExecutor(dedup, objectMapper());
    public final GrantService grantService =
            new GrantService(grants, channels, assets, idempotency);
    public final DraftService draftService =
            new DraftService(drafts, channels, assets, grants, idempotency);
    public final PublishService publishService =
            new PublishService(published, drafts, channels, grants, idempotency);
    public final DecisionService decisionService =
            new DecisionService(channels, published, grants);

    public TestFixture() {
        assets.insert(new Asset(FALLBACK_ASSET, 60_000));
        assets.insert(new Asset(ASSET_60S, 60_000));
        assets.insert(new Asset(ASSET_30S, 30_000));
        channels.insert(new Channel(CHANNEL, FALLBACK_ASSET));
    }

    private static ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /** 业务日内的时刻，格式：当天 HH:mm（Asia/Shanghai）。 */
    public static Instant at(String hhmm) {
        return TimeSupport.dayStart(DAY).plusSeconds(
                Integer.parseInt(hhmm.substring(0, 2)) * 3600L
                        + Integer.parseInt(hhmm.substring(3, 5)) * 60L);
    }

    /** 60 秒素材的标准片段。 */
    public static DraftSegment segment(String segmentId, String assetId,
                                       String startHhmm, String endHhmm) {
        return new DraftSegment(segmentId, assetId, at(startHhmm), at(endHhmm));
    }

    /** 覆盖整个业务日的授权。 */
    public Grant grantCoveringDay(String assetId) {
        Grant grant = new Grant(java.util.UUID.randomUUID().toString(), CHANNEL, assetId,
                TimeSupport.dayStart(DAY), TimeSupport.dayEnd(DAY), false);
        grants.insert(grant);
        return grant;
    }
}
