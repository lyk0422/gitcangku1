package com.example.starter.maintenance.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.domain.RejectedReading;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 被拒读数证据记录器：以独立事务（REQUIRES_NEW）固化被生效隔离令拒绝的读数证据与事件。
 * 调用方主事务随后抛出 409 并回滚（读数不落库、版本不前进、幂等键不占用），
 * 证据在独立事务中已提交，不受主事务回滚影响。
 */
@Service
public class RejectedReadingRecorder {

    private static final String EVENT_REJECTED_READING = "REJECTED_READING";

    private final EquipmentRepository repository;

    public RejectedReadingRecorder(EquipmentRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(RejectedReading rejected) {
        repository.insertRejectedReading(rejected);
        repository.insertEvent(rejected.orderId(), rejected.equipmentId(), EVENT_REJECTED_READING,
                null, null, rejected.operatorId(), rejected.requestId(),
                rejected.reasonCode() + ";lockPoints=" + rejected.lockPointIds(),
                rejected.rejectedAt());
    }
}
