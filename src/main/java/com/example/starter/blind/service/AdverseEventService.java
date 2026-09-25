package com.example.starter.blind.service;

import com.example.starter.blind.Actor;
import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.Severity;
import com.example.starter.blind.dto.AdverseEventView;
import com.example.starter.blind.repo.AdverseEventRepository;
import com.example.starter.blind.repo.AdverseEventRepository.AdverseEventRow;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 不良事件报告业务：
 * 任意角色可对已分配参与者提交报告；报告本身不揭示处理代码；
 * SEVERE 自动把分配标记为 URGENT_REVIEW，其余严重度不改变分配状态；
 * 同一分配内 eventKey 唯一，重复提交 409。
 */
@Service
public class AdverseEventService {

    private final AdverseEventRepository adverseEventRepository;
    private final ExperimentService experimentService;
    private final Clock clock;

    public AdverseEventService(AdverseEventRepository adverseEventRepository,
                               ExperimentService experimentService,
                               Clock clock) {
        this.adverseEventRepository = adverseEventRepository;
        this.experimentService = experimentService;
        this.clock = clock;
    }

    /**
     * 提交不良事件报告；SEVERE 时同事务标记 URGENT_REVIEW。
     */
    @Transactional
    public AdverseEventView report(String experimentId, String participantId,
                                   String eventKey, String severityText, String description,
                                   Actor actor) {
        Severity severity = Severity.fromText(severityText);
        AllocationRow allocation =
                experimentService.mustFindAllocationRow(experimentId, participantId);
        long now = clock.nowMillis();
        AdverseEventRow row = new AdverseEventRow(0L, experimentId, participantId,
                allocation.id(), eventKey, severity.name(), description,
                actor.actorId(), actor.role().name(), now, null);
        try {
            adverseEventRepository.insert(row);
        } catch (DuplicateKeyException e) {
            if (adverseEventRepository.isDuplicateEventKey(e)) {
                // 同一分配重复 eventKey：含并发兜底。
                throw ApiException.conflict("该参与者已存在相同 eventKey 的不良事件报告");
            }
            throw e;
        }
        if (severity.marksUrgentReview()) {
            experimentService.markUrgentReview(allocation.id());
        }
        AdverseEventRow stored =
                adverseEventRepository.findByAllocationAndEventKey(allocation.id(), eventKey);
        return toView(stored);
    }

    /**
     * 某参与者的不良事件报告历史；不含处理代码。
     */
    public List<AdverseEventView> listHistory(String experimentId, String participantId) {
        AllocationRow allocation =
                experimentService.mustFindAllocationRow(experimentId, participantId);
        return adverseEventRepository.findByAllocation(allocation.id()).stream()
                .map(AdverseEventService::toView)
                .toList();
    }

    static AdverseEventView toView(AdverseEventRow row) {
        return new AdverseEventView(row.id(), row.experimentId(), row.participantId(),
                row.eventKey(), row.severity(), row.description(),
                row.reporterActor(), row.reporterRole(), row.createdAt(),
                row.unblindRequestId());
    }
}
