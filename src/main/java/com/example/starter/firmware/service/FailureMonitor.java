package com.example.starter.firmware.service;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 失败率自动暂停判定：样本数达到下限且 FAILED×100 >= 样本数×阈值 时，
 * 将仍为 ACTIVE 的发布单原子转为 PAUSED。普通回执与双分区回滚共用；
 * 调用方必须已持有发布单行锁，并发回执串行通过，每轮至多生成一条暂停记录。
 */
@Component
public class FailureMonitor {

    private final ReleaseRepository releaseRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final Clock clock;

    public FailureMonitor(ReleaseRepository releaseRepository,
                          PauseRecordRepository pauseRecordRepository, Clock clock) {
        this.releaseRepository = releaseRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.clock = clock;
    }

    public void pauseIfThresholdReached(ReleaseOrder order, long triggerTaskId) {
        if (order.status() != ReleaseStatus.ACTIVE) {
            return;
        }
        int samples = order.roundSuccess() + order.roundFailed();
        if (samples < order.sampleFloor()) {
            return;
        }
        if ((long) order.roundFailed() * 100 < (long) samples * order.failureThresholdPercent()) {
            return;
        }
        if (releaseRepository.pauseIfActive(order.id()) == 1) {
            pauseRecordRepository.insert(order.id(), order.monitorRound(), triggerTaskId,
                    order.roundSuccess(), order.roundFailed(), Instant.now(clock).toString());
        }
    }
}
