package com.example.starter.service;

import com.example.starter.model.TakeoffRejection;
import com.example.starter.repo.RejectionRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 起飞拒绝历史记录器：独立事务写入，确认失败回滚时历史仍保留。
 */
@Service
public class RejectionRecorder {

    private final RejectionRepository rejectionRepository;

    public RejectionRecorder(RejectionRepository rejectionRepository) {
        this.rejectionRepository = rejectionRepository;
    }

    /**
     * 以新事务写入拒绝历史，不受调用方事务回滚影响。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TakeoffRejection record(long flightId, String requestId, String reason, Instant attemptedAt) {
        return rejectionRepository.insert(
                new TakeoffRejection(null, flightId, requestId, reason, attemptedAt));
    }
}