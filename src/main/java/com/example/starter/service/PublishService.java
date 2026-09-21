package com.example.starter.service;

import com.example.starter.common.ApiException;
import com.example.starter.domain.Draft;
import com.example.starter.domain.DraftSegment;
import com.example.starter.domain.PublishedSchedule;
import com.example.starter.repo.ChannelRepository;
import com.example.starter.repo.DraftRepository;
import com.example.starter.repo.GrantRepository;
import com.example.starter.repo.PublishedRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 发布服务：将草稿原子发布为只读快照并递增发布版本。
 */
@Service
public class PublishService {

    private final PublishedRepository publishedRepository;
    private final DraftRepository draftRepository;
    private final ChannelRepository channelRepository;
    private final GrantRepository grantRepository;
    private final IdempotencyExecutor idempotency;

    public PublishService(PublishedRepository publishedRepository, DraftRepository draftRepository,
                          ChannelRepository channelRepository, GrantRepository grantRepository,
                          IdempotencyExecutor idempotency) {
        this.publishedRepository = publishedRepository;
        this.draftRepository = draftRepository;
        this.channelRepository = channelRepository;
        this.grantRepository = grantRepository;
        this.idempotency = idempotency;
    }

    /**
     * 发布草稿。草稿版本不符或发布版本冲突返回 409；授权失效返回 422；
     * 任一校验失败整次拒绝，不产生任何修改。
     */
    @Transactional
    public PublishedSchedule publish(String channelId, LocalDate businessDay, String requestId,
                                     long draftVersion, long expectedPublishedVersion) {
        String fingerprint = "PUBLISH|" + channelId + '|' + businessDay + '|'
                + draftVersion + '|' + expectedPublishedVersion;
        return idempotency.execute("PUBLISH", requestId, fingerprint, PublishedSchedule.class,
                () -> doPublish(channelId, businessDay, draftVersion, expectedPublishedVersion));
    }

    private PublishedSchedule doPublish(String channelId, LocalDate businessDay,
                                        long draftVersion, long expectedPublishedVersion) {
        if (channelRepository.findById(channelId).isEmpty()) {
            throw ApiException.notFound("CHANNEL_NOT_FOUND", "频道不存在: " + channelId);
        }
        Draft draft = draftRepository.find(channelId, businessDay)
                .orElseThrow(() -> ApiException.notFound("DRAFT_NOT_FOUND",
                        "草稿不存在: " + channelId + " " + businessDay));
        if (draft.version() != draftVersion) {
            throw ApiException.conflict("DRAFT_VERSION_CONFLICT",
                    "草稿版本不符，期望 " + draftVersion + "，当前 " + draft.version());
        }
        for (DraftSegment segment : draft.segments()) {
            if (!grantRepository.existsCovering(channelId, segment.assetId(),
                    segment.start(), segment.end())) {
                throw ApiException.unprocessable("GRANT_INVALID",
                        "片段授权已失效，发布被拒绝: " + segment.segmentId());
            }
        }
        long newVersion = expectedPublishedVersion + 1;
        boolean published = publishedRepository.publish(channelId, businessDay,
                expectedPublishedVersion, newVersion, draftVersion, draft.segments());
        if (!published) {
            throw ApiException.conflict("PUBLISHED_VERSION_CONFLICT",
                    "发布版本冲突，期望 " + expectedPublishedVersion);
        }
        return new PublishedSchedule(channelId, businessDay, newVersion, draftVersion,
                draft.segments());
    }

    public PublishedSchedule get(String channelId, LocalDate businessDay) {
        return publishedRepository.find(channelId, businessDay)
                .orElseThrow(() -> ApiException.notFound("PUBLISHED_NOT_FOUND",
                        "已发布编排不存在: " + channelId + " " + businessDay));
    }
}
