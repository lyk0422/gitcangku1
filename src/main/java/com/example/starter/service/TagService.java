package com.example.starter.service;

import com.example.starter.domain.TagOwnership;
import com.example.starter.repo.TagOwnershipRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标签登记：登记时确定初始持有站点，重复登记返回 409。
 */
@Service
public class TagService {

    /**
     * 标签视图。
     *
     * @param tagCode 标签编号
     * @param stationCode 当前持有站点编号
     * @param registeredAt 登记时刻（UTC）
     */
    public record TagView(String tagCode, String stationCode, Instant registeredAt) {

        static TagView of(TagOwnership ownership) {
            return new TagView(ownership.tagCode(), ownership.stationCode(), ownership.registeredAt());
        }
    }

    private final TagOwnershipRepository ownerships;
    private final Clock clock;

    public TagService(TagOwnershipRepository ownerships, Clock clock) {
        this.ownerships = ownerships;
        this.clock = clock;
    }

    /**
     * 登记标签并指定初始持有站点。
     */
    @Transactional
    public TagView register(String tagCode, String stationCode) {
        try {
            ownerships.insert(tagCode, stationCode, clock.instant());
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("TAG_ALREADY_REGISTERED", "标签已登记: " + tagCode)
                    .operation("REGISTER").tagCode(tagCode).stationCode(stationCode).build();
        }
        return TagView.of(ownerships.findForUpdate(tagCode).orElseThrow());
    }

    /**
     * 查询标签当前持有状态。
     */
    @Transactional(readOnly = true)
    public TagView get(String tagCode) {
        return ownerships.findForUpdate(tagCode).map(TagView::of)
                .orElseThrow(() -> BusinessException.notFound("TAG_NOT_FOUND", "标签未登记: " + tagCode)
                        .operation("GET").tagCode(tagCode).build());
    }
}