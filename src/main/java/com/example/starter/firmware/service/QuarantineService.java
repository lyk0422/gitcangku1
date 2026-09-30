package com.example.starter.firmware.service;

import com.example.starter.firmware.api.QuarantineRoundView;
import com.example.starter.firmware.api.ReleaseQuarantineRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.QuarantineRound;
import com.example.starter.firmware.domain.QuarantineStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.QuarantineRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 设备隔离解除与查询。
 * 解除必须由不同于隔离确认人的操作人审核；解除只开启新一轮投放（计数清零、历史保留）。
 * 解除事务先锁设备行，保证与回执、拉取串行，不会出现解除与隔离交叉。
 */
@Service
public class QuarantineService {

    private final QuarantineRepository quarantineRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceService deviceService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public QuarantineService(QuarantineRepository quarantineRepository, DeviceRepository deviceRepository,
                             DeviceService deviceService, IdempotencyService idempotency, Clock clock) {
        this.quarantineRepository = quarantineRepository;
        this.deviceRepository = deviceRepository;
        this.deviceService = deviceService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 解除设备对某目标版本的隔离：仅 QUARANTINED 轮次可解除，审核人必须不同于确认人。
     * 解除后旧轮置 RELEASED（解除记录固化），另开一轮 count=0 的 TRACKING，不清除历史失败。
     */
    public QuarantineRoundView release(String deviceId, ReleaseQuarantineRequest request) {
        String fingerprint = String.join("|", "quarantine.release", deviceId, request.targetVersion(),
                request.reviewer(), request.reason());
        return idempotency.execute(request.requestId(), "quarantine.release", fingerprint, () -> {
            Device device = deviceService.findDevice(deviceId);
            deviceRepository.findByIdForUpdate(device.deviceId());
            QuarantineRound active = quarantineRepository
                    .findActiveForUpdate(device.deviceId(), request.targetVersion())
                    .orElseThrow(() -> ApiException.conflict("DEVICE_NOT_QUARANTINED",
                            "设备当前未对该版本隔离: " + deviceId + " / " + request.targetVersion()));
            if (active.status() != QuarantineStatus.QUARANTINED) {
                throw ApiException.conflict("DEVICE_NOT_QUARANTINED",
                        "设备当前未对该版本隔离（状态 " + active.status() + "）");
            }
            if (request.reviewer().equals(active.confirmedBy())) {
                throw ApiException.conflict("REVIEWER_SAME_AS_CONFIRMER",
                        "解除审核人不能与隔离确认人相同: " + request.reviewer());
            }
            String releasedAt = Instant.now(clock).toString();
            if (quarantineRepository.markReleased(active.id(), request.reviewer(), releasedAt, request.reason())
                    != 1) {
                throw new IllegalStateException("隔离轮次状态已变更，解除未生效: " + active.id());
            }
            int nextRoundNo = quarantineRepository.maxRoundNo(device.deviceId(), request.targetVersion()) + 1;
            long nextRoundId = quarantineRepository.insertNextRound(
                    device.deviceId(), request.targetVersion(), nextRoundNo);
            quarantineRepository.linkNextRound(active.id(), nextRoundId);
            QuarantineRound released = quarantineRepository.findById(active.id())
                    .orElseThrow(() -> new IllegalStateException("解除后轮次读取失败"));
            return QuarantineRoundView.of(released);
        }, QuarantineRoundView.class);
    }

    /**
     * 设备全部目标版本的连续失败/隔离轮次（含历史），按ID升序。
     */
    public List<QuarantineRound> roundsOfDevice(String deviceId) {
        deviceService.findDevice(deviceId);
        return quarantineRepository.findByDevice(deviceId);
    }
}
