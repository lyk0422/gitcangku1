package com.example.starter.service;

import com.example.starter.common.ApiException;
import com.example.starter.common.TimeSupport;
import com.example.starter.domain.Asset;
import com.example.starter.domain.Draft;
import com.example.starter.domain.DraftSegment;
import com.example.starter.repo.AssetRepository;
import com.example.starter.repo.ChannelRepository;
import com.example.starter.repo.DraftRepository;
import com.example.starter.repo.GrantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 编排草稿服务：每个“频道＋业务日”一份草稿，整份替换，乐观版本控制。
 */
@Service
public class DraftService {

    private final DraftRepository draftRepository;
    private final ChannelRepository channelRepository;
    private final AssetRepository assetRepository;
    private final GrantRepository grantRepository;
    private final IdempotencyExecutor idempotency;

    public DraftService(DraftRepository draftRepository, ChannelRepository channelRepository,
                        AssetRepository assetRepository, GrantRepository grantRepository,
                        IdempotencyExecutor idempotency) {
        this.draftRepository = draftRepository;
        this.channelRepository = channelRepository;
        this.assetRepository = assetRepository;
        this.grantRepository = grantRepository;
        this.idempotency = idempotency;
    }

    /**
     * 整份替换草稿，携带 requestId 幂等、expectedDraftVersion 乐观并发控制。
     * 版本不符返回 409 且不改变原草稿；片段规则违反返回 422。
     */
    @Transactional
    public Draft replace(String channelId, LocalDate businessDay, String requestId,
                         long expectedVersion, List<DraftSegment> segments) {
        String fingerprint = fingerprint(channelId, businessDay, expectedVersion, segments);
        return idempotency.execute("REPLACE_DRAFT", requestId, fingerprint, Draft.class,
                () -> doReplace(channelId, businessDay, expectedVersion, segments));
    }

    private Draft doReplace(String channelId, LocalDate businessDay,
                            long expectedVersion, List<DraftSegment> segments) {
        if (channelRepository.findById(channelId).isEmpty()) {
            throw ApiException.notFound("CHANNEL_NOT_FOUND", "频道不存在: " + channelId);
        }
        List<DraftSegment> sorted = validateAndSort(channelId, businessDay, segments);
        long newVersion = expectedVersion + 1;
        boolean replaced = draftRepository.replace(channelId, businessDay,
                expectedVersion, newVersion, sorted);
        if (!replaced) {
            throw ApiException.conflict("DRAFT_VERSION_CONFLICT",
                    "草稿版本不符，期望 " + expectedVersion);
        }
        return new Draft(channelId, businessDay, newVersion, sorted);
    }

    /**
     * 校验片段规则并按开始时间升序排序：
     * 不跨日、不重叠、时长与素材一致、由单条未撤销授权完整覆盖。
     */
    private List<DraftSegment> validateAndSort(String channelId, LocalDate businessDay,
                                               List<DraftSegment> segments) {
        List<DraftSegment> safe = segments == null ? List.of() : segments;
        Instant dayStart = TimeSupport.dayStart(businessDay);
        Instant dayEnd = TimeSupport.dayEnd(businessDay);
        Set<String> seenIds = new HashSet<>();
        for (DraftSegment segment : safe) {
            if (segment.segmentId() == null || segment.segmentId().isBlank()
                    || segment.assetId() == null || segment.assetId().isBlank()
                    || segment.start() == null || segment.end() == null) {
                throw ApiException.badRequest("INVALID_SEGMENT",
                        "片段必须包含 segmentId、assetId、start、end");
            }
            if (!seenIds.add(segment.segmentId())) {
                throw ApiException.badRequest("INVALID_SEGMENT",
                        "片段 ID 重复: " + segment.segmentId());
            }
            if (!segment.end().isAfter(segment.start())) {
                throw ApiException.unprocessable("INVALID_SEGMENT_INTERVAL",
                        "片段结束时间必须晚于开始时间: " + segment.segmentId());
            }
            if (segment.start().isBefore(dayStart) || segment.end().isAfter(dayEnd)) {
                throw ApiException.unprocessable("SEGMENT_OUT_OF_DAY",
                        "片段不得跨业务日: " + segment.segmentId());
            }
            Asset asset = assetRepository.findById(segment.assetId())
                    .orElseThrow(() -> ApiException.notFound("ASSET_NOT_FOUND",
                            "素材不存在: " + segment.assetId()));
            long actualMs = segment.end().toEpochMilli() - segment.start().toEpochMilli();
            if (actualMs != asset.durationMs()) {
                throw ApiException.unprocessable("SEGMENT_DURATION_MISMATCH",
                        "片段时长与素材时长不一致: " + segment.segmentId());
            }
            if (!grantRepository.existsCovering(channelId, segment.assetId(),
                    segment.start(), segment.end())) {
                throw ApiException.unprocessable("GRANT_NOT_COVERING",
                        "片段未被单条有效授权完整覆盖: " + segment.segmentId());
            }
        }
        List<DraftSegment> sorted = new ArrayList<>(safe);
        sorted.sort(Comparator.comparing(DraftSegment::start));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).start().isBefore(sorted.get(i - 1).end())) {
                throw ApiException.unprocessable("SEGMENT_OVERLAP",
                        "片段时间重叠: " + sorted.get(i).segmentId());
            }
        }
        return sorted;
    }

    public Draft get(String channelId, LocalDate businessDay) {
        return draftRepository.find(channelId, businessDay)
                .orElseThrow(() -> ApiException.notFound("DRAFT_NOT_FOUND",
                        "草稿不存在: " + channelId + " " + businessDay));
    }

    private String fingerprint(String channelId, LocalDate businessDay,
                               long expectedVersion, List<DraftSegment> segments) {
        StringBuilder sb = new StringBuilder("REPLACE_DRAFT|").append(channelId)
                .append('|').append(businessDay).append('|').append(expectedVersion);
        if (segments != null) {
            for (DraftSegment s : segments) {
                sb.append('|').append(s.segmentId()).append(',').append(s.assetId())
                        .append(',').append(s.start()).append(',').append(s.end());
            }
        }
        return sb.toString();
    }
}
