package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.Severity;
import com.example.starter.blind.dto.AdverseEventView;
import com.example.starter.blind.repo.AdverseEventRepository;
import com.example.starter.blind.repo.AdverseEventRepository.AdverseEventRow;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 不良事件报告业务：
 * 任意角色可对已分配参与者上报；同一参与者可有多条报告；
 * SEVERE 自动将分配标记为 URGENT_REVIEW，其余严重度不改变分配状态；
 * 报告与查询均不暴露处理代码。
 */
@Service
public class AdverseEventService {

    private final AdverseEventRepository adverseEventRepository;
    private final AllocationRepository allocationRepository;
    private final ExperimentService experimentService;
    private final Clock clock;

    public AdverseEventService(AdverseEventRepository adverseEventRepository,
                               AllocationRepository allocationRepository,
                               ExperimentService experimentService,
                               Clock clock) {
        this.adverseEventRepository = adverseEventRepository;
        this.allocationRepository = allocationRepository;
        this.experimentService = experimentService;
        this.clock = clock;
    }

    /**
     * 创建不良事件报告；SEVERE 时同事务将分配标记为 URGENT_REVIEW。
     */
    @Transactional
    public AdverseEventView report(String experimentId, String participantId,
                                   String eventKey, Severity severity, String description,
                                   String reporterActor) {
        if (eventKey == null || eventKey.isBlank()) {
            throw ApiException.badRequest("eventKey 不能为空");
        }
        if (description == null || description.isBlank()) {
            throw ApiException.badRequest("description 不能为空");
        }
        if (severity == null) {
            throw ApiException.badRequest("severity 不能为空");
        }
        AllocationRow allocation =
                experimentService.mustFindAllocationRow(experimentId, participantId);
        long now = clock.nowMillis();
        AdverseEventRow row = new AdverseEventRow(0L, experimentId, participantId,
                allocation.id(), eventKey, severity.name(), description, reporterActor, now);
        try {
            adverseEventRepository.insert(row);
        } catch (DuplicateKeyException e) {
            // eventKey 实验内唯一；并发或重复上报由唯一索引兜底。
            throw ApiException.conflict("eventKey 已存在: " + eventKey);
        }
        if (severity == Severity.SEVERE) {
            allocationRepository.markUrgentReview(allocation.id());
        }
        return toView(row);
    }

    /**
     * 查询参与者的不良事件报告历史；不含处理代码。
     */
    public List<AdverseEventView> listHistory(String experimentId, String participantId) {
        experimentService.mustFindAllocationRow(experimentId, participantId);
        return adverseEventRepository.findByParticipant(experimentId, participantId).stream()
                .map(AdverseEventService::toView)
                .toList();
    }

    private static AdverseEventView toView(AdverseEventRow row) {
        return new AdverseEventView(row.eventKey(), row.experimentId(), row.participantId(),
                row.severity(), row.description(), row.reporterActor(), row.createdAt());
    }
}
