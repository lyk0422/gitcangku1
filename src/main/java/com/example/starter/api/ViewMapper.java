package com.example.starter.api;

import com.example.starter.api.dto.Responses;
import com.example.starter.common.TimeSupport;
import com.example.starter.domain.Asset;
import com.example.starter.domain.Channel;
import com.example.starter.domain.Decision;
import com.example.starter.domain.Draft;
import com.example.starter.domain.DraftSegment;
import com.example.starter.domain.Grant;
import com.example.starter.domain.PublishedSchedule;

import java.util.List;

/**
 * 领域对象到响应 DTO 的映射。
 */
final class ViewMapper {

    private ViewMapper() {
    }

    static Responses.AssetView toView(Asset asset) {
        return new Responses.AssetView(asset.id(), asset.durationMs());
    }

    static Responses.ChannelView toView(Channel channel) {
        return new Responses.ChannelView(channel.id(), channel.fallbackAssetId());
    }

    static Responses.GrantView toView(Grant grant) {
        return new Responses.GrantView(grant.id(), grant.channelId(), grant.assetId(),
                TimeSupport.format(grant.validFrom()), TimeSupport.format(grant.validTo()),
                grant.revoked());
    }

    static Responses.DraftView toView(Draft draft) {
        return new Responses.DraftView(draft.channelId(), draft.businessDay().toString(),
                draft.version(), toSegmentViews(draft.segments()));
    }

    static Responses.PublishedView toView(PublishedSchedule published) {
        return new Responses.PublishedView(published.channelId(),
                published.businessDay().toString(), published.version(),
                published.draftVersion(), toSegmentViews(published.segments()));
    }

    static Responses.DecisionView toView(Decision decision) {
        return new Responses.DecisionView(decision.type().name(), decision.assetId(),
                decision.reason(), decision.segmentId(),
                decision.start() == null ? null : TimeSupport.format(decision.start()),
                decision.end() == null ? null : TimeSupport.format(decision.end()));
    }

    private static List<Responses.SegmentView> toSegmentViews(List<DraftSegment> segments) {
        return segments.stream()
                .map(s -> new Responses.SegmentView(s.segmentId(), s.assetId(),
                        TimeSupport.format(s.start()), TimeSupport.format(s.end())))
                .toList();
    }
}
