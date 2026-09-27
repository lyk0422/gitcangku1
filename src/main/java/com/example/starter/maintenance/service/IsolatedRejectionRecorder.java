package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.domain.IsolationOrder;
import com.example.starter.maintenance.store.IsolationRepository;

/**
 * 被拒读数证据记录器：在独立事务（REQUIRES_NEW）中固化证据，
 * 与读数业务事务相互独立——读数事务随后回滚（不占幂等键、读数不落库），证据仍保留。
 */
@Service
public class IsolatedRejectionRecorder {

    private final IsolationRepository isolationRepository;
    private final Clock clock;

    public IsolatedRejectionRecorder(IsolationRepository isolationRepository, Clock clock) {
        this.isolationRepository = isolationRepository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String equipmentId, IsolationOrder order, AddReadingRequest req,
                       List<String> lockPointSnapshot) {
        Instant now = clock.instant();
        isolationRepository.insertRejection(equipmentId, order.orderId(), req.readingId(),
                req.operatorId(), req.requestId(), req.sampledAt(), req.cumulativeMinutes(),
                lockPointSnapshot, now);
    }
}
