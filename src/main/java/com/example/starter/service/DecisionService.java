package com.example.starter.service;

import com.example.starter.common.ApiException;
import com.example.starter.common.TimeSupport;
import com.example.starter.domain.Channel;
import com.example.starter.domain.Decision;
import com.example.starter.domain.DraftSegment;
import com.example.starter.domain.PublishedSchedule;
import com.example.starter.repo.ChannelRepository;
import com.example.starter.repo.GrantRepository;
import com.example.starter.repo.PublishedRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 播出决定服务：按频道与时刻查询应播出内容。
 * 命中有效片段返回节目素材；无编排、空档或授权已撤销时返回保底素材及原因。
 */
@Service
public class DecisionService {

    private final ChannelRepository channelRepository;
    private final PublishedRepository publishedRepository;
    private final GrantRepository grantRepository;

    public DecisionService(ChannelRepository channelRepository,
                           PublishedRepository publishedRepository,
                           GrantRepository grantRepository) {
        this.channelRepository = channelRepository;
        this.publishedRepository = publishedRepository;
        this.grantRepository = grantRepository;
    }

    public Decision decide(String channelId, Instant at) {
        Channel channel = channelRepository.findById(channelId)
                .orElseThrow(() -> ApiException.notFound("CHANNEL_NOT_FOUND",
                        "频道不存在: " + channelId));
        LocalDate day = TimeSupport.businessDayOf(at);
        Optional<PublishedSchedule> published = publishedRepository.find(channelId, day);
        if (published.isEmpty()) {
            return Decision.fallback(channel.fallbackAssetId(), "NO_PUBLISHED_SCHEDULE");
        }
        Optional<DraftSegment> hit = published.get().segments().stream()
                .filter(s -> !at.isBefore(s.start()) && at.isBefore(s.end()))
                .findFirst();
        if (hit.isEmpty()) {
            return Decision.fallback(channel.fallbackAssetId(), "GAP");
        }
        DraftSegment segment = hit.get();
        boolean covered = grantRepository.existsCovering(channelId, segment.assetId(),
                segment.start(), segment.end());
        if (!covered) {
            return Decision.fallback(channel.fallbackAssetId(), "GRANT_REVOKED");
        }
        return Decision.program(segment);
    }
}
