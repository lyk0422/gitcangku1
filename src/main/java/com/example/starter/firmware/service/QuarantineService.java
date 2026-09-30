package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceQuarantineView;
import com.example.starter.firmware.api.ReleaseQuarantineRequest;
import com.example.starter.firmware.domain.DeviceFailureRound;
import com.example.starter.firmware.domain.DeviceFailureRoundStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceFailureRoundRepository;
import com.example.starter.firmware.repo.DeviceRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 设备隔离与解除：同一设备同一版本连续两次失败后隔离；解除须由不同于隔离确认人的操作人审核。
 * 解除只开启新一轮投放（不清除历史失败次数）；版本变化时新一轮计数独立，历史不重算。
 * 解除事务先锁设备行，与回执/拉取串行，避免审核与第二次失败并发竞态。
 */
@Service
public class QuarantineService {

    private final DeviceRepository deviceRepository;
    private final DeviceFailureRoundRepository roundRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public QuarantineService(DeviceRepository deviceRepository,
                             DeviceFailureRoundRepository roundRepository,
                             IdempotencyService idempotency, Clock clock) {
        this.deviceRepository = deviceRepository;
        this.roundRepository = roundRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 查询设备本轮连续失败次数、当前隔离状态、隔离依据与解除记录；设备不存在 404。
     */
    public DeviceQuarantineView getQuarantine(String deviceId) {
        requireDevice(deviceId);
        return buildView(deviceId);
    }

    /**
     * 人工解除隔离：仅当前存在 QUARANTINED 轮次可解除，审核人必须不同于该轮隔离确认人。
     * 解除只把该轮置为 RELEASED 并留痕，使设备可领取新一轮任务，历史失败次数保留。
     */
    public DeviceQuarantineView release(String deviceId, ReleaseQuarantineRequest request) {
        String fingerprint = String.join("|", "quarantine.release", deviceId,
                request.operator().trim(), request.reason().trim());
        return idempotency.execute(request.requestId(), "quarantine.release", fingerprint, () -> {
            deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            DeviceFailureRound quarantined = roundRepository.findByDevice(deviceId).stream()
                    .filter(round -> round.status() == DeviceFailureRoundStatus.QUARANTINED)
                    .max(Comparator.comparingLong(DeviceFailureRound::id))
                    .orElseThrow(() -> ApiException.conflict("NOT_QUARANTINED", "设备当前不处于隔离状态"));
            if (quarantined.confirmedBy().equals(request.operator().trim())) {
                throw ApiException.conflict("QUARANTINE_RELEASE_SELF",
                        "解除审核人不能与隔离确认人相同: " + quarantined.confirmedBy());
            }
            int updated = roundRepository.markReleased(quarantined.id(), request.operator().trim(),
                    request.reason().trim(), Instant.now(clock).toString());
            if (updated != 1) {
                throw ApiException.conflict("QUARANTINE_STATE_CHANGED", "隔离状态已变更，请刷新后重试");
            }
            return buildView(deviceId);
        }, DeviceQuarantineView.class);
    }

    private void requireDevice(String deviceId) {
        deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
    }

    /**
     * 汇总视图：顶层字段取全部版本中最近一轮；basis 取最近一条 QUARANTINED；
     * releases 与 rounds 覆盖全部历史轮次（解除后旧两次失败仍可查）。
     */
    private DeviceQuarantineView buildView(String deviceId) {
        List<DeviceFailureRound> rounds = roundRepository.findByDevice(deviceId);
        DeviceFailureRound latest = rounds.stream()
                .max(Comparator.comparingLong(DeviceFailureRound::id))
                .orElse(null);
        DeviceFailureRound quarantined = rounds.stream()
                .filter(round -> round.status() == DeviceFailureRoundStatus.QUARANTINED)
                .max(Comparator.comparingLong(DeviceFailureRound::id))
                .orElse(null);
        List<DeviceQuarantineView.ReleaseRecordView> releases = rounds.stream()
                .filter(round -> round.releasedAtUtc() != null)
                .map(DeviceQuarantineView.ReleaseRecordView::of)
                .toList();
        List<DeviceQuarantineView.RoundView> roundViews = rounds.stream()
                .map(DeviceQuarantineView.RoundView::of)
                .toList();
        return new DeviceQuarantineView(deviceId,
                latest == null ? null : latest.version(),
                latest == null ? 0 : latest.roundNo(),
                latest == null ? 0 : latest.consecutiveFailures(),
                quarantined != null,
                quarantined == null ? null : DeviceQuarantineView.QuarantineBasis.of(quarantined),
                releases, roundViews);
    }
}
