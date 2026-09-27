package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.domain.IsolationLockPoint;
import com.example.starter.maintenance.domain.IsolationOrder;
import com.example.starter.maintenance.domain.RejectedReading;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 隔离诊断证据服务：读数被生效隔离拒绝（409）属于业务失败，外层事务回滚且不占幂等键，
 * 但拒绝证据必须固化，因此在独立事务中提交，不受外层回滚影响。
 */
@Service
public class IsolationEvidenceService {

    private final EquipmentRepository repository;
    private final Clock clock;

    public IsolationEvidenceService(EquipmentRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 在独立事务中追加一条被拒读数证据；每次被拒提交均追加，历史证据不可改写。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejectedReading(IsolationOrder active, String readingId, Instant sampledAt,
                                      long cumulativeMinutes, String operatorId, String requestId,
                                      List<IsolationLockPoint> lockPoints) {
        List<String> pointIds = lockPoints.stream().map(IsolationLockPoint::lockPointId).toList();
        RejectedReading rejected = new RejectedReading(
                0L,
                active.equipmentId(),
                active.isolationId(),
                readingId,
                sampledAt,
                cumulativeMinutes,
                operatorId,
                requestId,
                FingerprintUtil.canonical(pointIds),
                clock.instant());
        repository.insertRejectedReading(rejected);
    }
}
