package com.example.starter.service;

import com.example.starter.model.TakeoffClearance;
import com.example.starter.repo.BaseDataRepository;
import com.example.starter.repo.ClearanceRepository;
import com.example.starter.repo.CompletionRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 起飞确认服务：校验截止时刻并生成放行记录。
 */
@Service
public class TakeoffService {

    private final CompletionRepository completionRepository;
    private final ClearanceRepository clearanceRepository;
    private final RejectionRecorder rejectionRecorder;
    private final BaseDataRepository baseDataRepository;
    private final Clock clock;

    public TakeoffService(CompletionRepository completionRepository,
            ClearanceRepository clearanceRepository, RejectionRecorder rejectionRecorder,
            BaseDataRepository baseDataRepository, Clock clock) {
        this.completionRepository = completionRepository;
        this.clearanceRepository = clearanceRepository;
        this.rejectionRecorder = rejectionRecorder;
        this.baseDataRepository = baseDataRepository;
        this.clock = clock;
    }

    /**
     * 确认起飞；requestId 同参重放返回原放行，异参 409；
     * 作业未完成或在截止时刻及之后确认，记录拒绝历史并返回 409。
     */
    @Transactional
    public TakeoffClearance confirmTakeoff(String requestId, long flightId) {
        if (!baseDataRepository.flightExists(flightId)) {
            throw new NotFoundException("航班不存在: " + flightId);
        }
        var existing = clearanceRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            TakeoffClearance clearance = existing.get();
            if (clearance.flightId() == flightId) {
                return clearance;
            }
            throw new ConflictException("requestId 已存在但参数不一致: " + requestId);
        }
        Instant now = clock.instant();
        var latest = completionRepository.findLatestByFlightId(flightId);
        if (latest.isEmpty()) {
            reject(flightId, requestId, "作业未完成，不能确认起飞", now);
            throw new ConflictException("作业未完成，不能确认起飞: flightId=" + flightId);
        }
        var completion = latest.get();
        if (!now.isBefore(completion.deadlineAt())) {
            reject(flightId, requestId, "除冰结果已过期，截止时刻 " + completion.deadlineAt(), now);
            throw new ConflictException("除冰结果已过期，须重新申请除冰: flightId=" + flightId);
        }
        try {
            return clearanceRepository.insert(new TakeoffClearance(null, requestId, flightId,
                    completion.id(), now, completion.fluidType(), completion.completedAt(),
                    completion.deadlineAt()));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("该完成记录已用于放行，不能重复放行: flightId=" + flightId);
        }
    }

    private void reject(long flightId, String requestId, String reason, Instant attemptedAt) {
        rejectionRecorder.record(flightId, requestId, reason, attemptedAt);
    }
}