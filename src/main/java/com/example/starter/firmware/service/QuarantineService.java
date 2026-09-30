package com.example.starter.firmware.service;

import com.example.starter.firmware.api.FailureRoundView;
import com.example.starter.firmware.api.ReleaseQuarantineRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.DeviceFailureRound;
import com.example.starter.firmware.domain.RoundStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceFailureRoundRepository;
import com.example.starter.firmware.repo.DeviceRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * 设备隔离解除审核：仅 QUARANTINED 轮次可解除，解除操作人必须不同于隔离确认人；
 * 解除把轮次置为 RELEASED 并释放活动槽位，只开启新一轮投放，不清除任何历史失败数据。
 */
@Service
public class QuarantineService {

    private final DeviceFailureRoundRepository roundRepository;
    private final DeviceRepository deviceRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public QuarantineService(DeviceFailureRoundRepository roundRepository, DeviceRepository deviceRepository,
                             IdempotencyService idempotency, Clock clock) {
        this.roundRepository = roundRepository;
        this.deviceRepository = deviceRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 人工解除设备对指定目标版本的隔离。设备行锁串行化解除与失败回执，
     * 同键同参重放原结果；并发重复解除至多一行生效（QUARANTINED 条件更新）。
     */
    public FailureRoundView release(String deviceId, ReleaseQuarantineRequest request) {
        String fingerprint = String.join("|", "quarantine.release", deviceId, request.toVersion(),
                request.operatorId(), request.reason());
        return idempotency.execute(request.requestId(), "quarantine.release", fingerprint, () -> {
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            DeviceFailureRound round = roundRepository.findActive(device.deviceId(), request.toVersion())
                    .orElseThrow(() -> ApiException.conflict("NOT_QUARANTINED",
                            "设备当前未对版本 " + request.toVersion() + " 隔离，无需解除"));
            if (round.status() != RoundStatus.QUARANTINED) {
                throw ApiException.conflict("NOT_QUARANTINED",
                        "设备对版本 " + request.toVersion() + " 连续失败次数为 " + round.consecutiveFailed()
                                + "，尚未达到隔离条件");
            }
            if (round.confirmedBy() != null && round.confirmedBy().equals(request.operatorId())) {
                throw ApiException.conflict("SAME_OPERATOR",
                        "解除操作人必须与隔离确认人不同: " + round.confirmedBy());
            }
            int affected = roundRepository.release(round.id(), request.operatorId(), request.reason(),
                    Instant.now(clock).toString());
            if (affected != 1) {
                throw new IllegalStateException("隔离解除状态流转失败: roundId=" + round.id());
            }
            return FailureRoundView.of(roundRepository.findById(round.id()).orElseThrow());
        }, FailureRoundView.class);
    }
}
