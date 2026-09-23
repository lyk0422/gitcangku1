package com.example.starter.firmware.service;

import com.example.starter.firmware.api.AssignDeviceRequest;
import com.example.starter.firmware.api.CommandReceiptRequest;
import com.example.starter.firmware.api.CommandView;
import com.example.starter.firmware.api.CohortView;
import com.example.starter.firmware.api.CreateCohortRequest;
import com.example.starter.firmware.api.CreateRegionRequest;
import com.example.starter.firmware.api.ResumeCohortRequest;
import com.example.starter.firmware.domain.AssignmentCommand;
import com.example.starter.firmware.domain.Cohort;
import com.example.starter.firmware.domain.CohortAssignment;
import com.example.starter.firmware.domain.CohortRegion;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.AssignmentRepository;
import com.example.starter.firmware.repo.CohortRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * 投放队列与设备分配：区域配额、队列建档、设备首次分配（代次从1开始并下发PENDING指令）、
 * 代次指令回执入账与队列级失败率自动暂停、人工恢复。
 *
 * <p>并发顺序：回执、自动暂停、人工恢复与迁移均先锁活动行（release_order FOR UPDATE），
 * 再锁活动内的指令/分配/队列行，保证统计、设备归属与活动状态来自同一提交结果。
 * 迁移提交后到达的旧代次指令（已SUPERSEDED）回执仅保存为历史LATE，不改目标队列统计。
 */
@Service
public class CohortService {

    private final CohortRepository cohortRepository;
    private final AssignmentRepository assignmentRepository;
    private final ReleaseRepository releaseRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public CohortService(CohortRepository cohortRepository, AssignmentRepository assignmentRepository,
                         ReleaseRepository releaseRepository, IdempotencyService idempotency, Clock clock) {
        this.cohortRepository = cohortRepository;
        this.assignmentRepository = assignmentRepository;
        this.releaseRepository = releaseRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public CohortRegion createRegion(CreateRegionRequest request) {
        requireExistingActiveRelease(request.releaseId());
        String fingerprint = String.join("|", "cohort.region.create",
                String.valueOf(request.releaseId()), request.regionCode(), String.valueOf(request.quota()));
        return idempotency.execute(request.requestId(), "cohort.region.create", fingerprint, () -> {
            try {
                long id = cohortRepository.insertRegion(request.releaseId(), request.regionCode(), request.quota());
                return new CohortRegion(id, request.releaseId(), request.regionCode(), request.quota());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("REGION_EXISTS",
                        "区域已存在: " + request.regionCode());
            }
        }, CohortRegion.class);
    }

    public CohortView createCohort(CreateCohortRequest request) {
        requireExistingActiveRelease(request.releaseId());
        requireRegion(request.releaseId(), request.regionCode());
        String fingerprint = String.join("|", "cohort.create", String.valueOf(request.releaseId()),
                request.cohortCode(), request.firmwareVersion(), request.regionCode(),
                String.valueOf(request.deviceCap()), String.valueOf(request.canaryPercent()),
                String.valueOf(request.effectiveSampleFloor()),
                String.valueOf(request.effectiveFailureThresholdPercent()));
        return idempotency.execute(request.requestId(), "cohort.create", fingerprint, () -> {
            long id;
            try {
                id = cohortRepository.insertCohort(request.releaseId(), request.cohortCode(),
                        request.firmwareVersion(), request.regionCode(), request.deviceCap(),
                        request.canaryPercent(), request.effectiveSampleFloor(),
                        request.effectiveFailureThresholdPercent());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("COHORT_EXISTS", "队列已存在: " + request.cohortCode());
            }
            Cohort cohort = cohortRepository.findCohortById(id)
                    .orElseThrow(() -> new IllegalStateException("队列创建后读取失败"));
            return CohortView.of(cohort, 0);
        }, CohortView.class);
    }

    /**
     * 设备首次分配到队列：分配版本与指令代次均从1开始，同时下发一条PENDING指令。
     * 队列设备上限与灰度百分比在建档分配时同样校验。
     */
    public CommandView assignDevice(AssignDeviceRequest request) {
        String fingerprint = String.join("|", "cohort.assign", String.valueOf(request.releaseId()),
                request.deviceId(), String.valueOf(request.cohortId()));
        return idempotency.execute(request.requestId(), "cohort.assign", fingerprint, () -> {
            ReleaseOrder order = lockRelease(request.releaseId());
            Cohort cohort = cohortRepository.findCohortByIdForUpdate(request.cohortId())
                    .orElseThrow(() -> ApiException.notFound("COHORT_NOT_FOUND",
                            "队列不存在: " + request.cohortId()));
            if (cohort.releaseId() != request.releaseId()) {
                throw ApiException.badRequest("COHORT_WRONG_RELEASE", "队列不属于该投放活动");
            }
            int current = cohortRepository.countAssignmentsByCohort(cohort.id());
            validateCohortCapacity(cohort, current + 1, "首次分配");
            try {
                assignmentRepository.insertAssignment(request.releaseId(), request.deviceId(), cohort.id());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_ASSIGNED", "设备已在该活动内分配: " + request.deviceId());
            }
            long commandId = assignmentRepository.insertCommand(request.releaseId(), request.deviceId(),
                    cohort.id(), 1, null);
            return CommandView.of(assignmentRepository.findCommandById(commandId).orElseThrow());
        }, CommandView.class);
    }

    /**
     * 代次指令回执：
     * <ul>
     *   <li>当前代次PENDING指令首次回执：结算到该指令所属队列，SUCCESS标记设备已确认安装；
     *       样本达标且失败率越限时同事务原子暂停队列。重复回执不重复计数。</li>
     *   <li>已SUPERSEDED（迁移提交后到达）的旧代次回执：仅保存历史LATE，不改目标队列成功数、失败率或暂停位。</li>
     * </ul>
     */
    public CommandView receipt(long commandId, CommandReceiptRequest request) {
        String fingerprint = String.join("|", "cohort.command.receipt", String.valueOf(commandId),
                request.result().name());
        return idempotency.execute(request.requestId(), "cohort.command.receipt", fingerprint, () -> {
            AssignmentCommand snapshot = assignmentRepository.findCommandById(commandId)
                    .orElseThrow(() -> ApiException.notFound("COMMAND_NOT_FOUND", "指令不存在: " + commandId));
            // 先锁活动行，与迁移、暂停、恢复形成全活动一致提交顺序
            ReleaseOrder order = lockRelease(snapshot.releaseId());
            AssignmentCommand command = assignmentRepository.findCommandByIdForUpdate(commandId)
                    .orElseThrow(() -> ApiException.notFound("COMMAND_NOT_FOUND", "指令不存在: " + commandId));
            CohortAssignment assignment = assignmentRepository
                    .findAssignmentForUpdate(command.releaseId(), command.deviceId())
                    .orElseThrow(() -> ApiException.notFound("ASSIGNMENT_NOT_FOUND", "设备分配不存在"));
            String now = Instant.now(clock).toString();

            switch (command.status()) {
                case PENDING -> {
                    if (command.generation() != assignment.currentGeneration()) {
                        // 理论上迁移已把旧PENDING置SUPERSEDED；兜底按LATE存档，绝不结算
                        assignmentRepository.insertHistory(command.releaseId(), command.deviceId(),
                                command.id(), command.cohortId(), command.generation(), "LATE", false, false, now);
                        return CommandView.of(command);
                    }
                    assignmentRepository.completeCommand(commandId, request.result());
                    Cohort cohort = cohortRepository.findCohortByIdForUpdate(command.cohortId())
                            .orElseThrow(() -> new IllegalStateException("指令所属队列缺失"));
                    cohortRepository.incrementCohortStats(command.cohortId(), request.result());
                    if (request.result() == ReceiptResult.SUCCESS) {
                        assignmentRepository.markInstallConfirmed(command.releaseId(), command.deviceId());
                    }
                    Cohort updated = cohortRepository.findCohortById(command.cohortId()).orElseThrow();
                    pauseCohortIfThresholdReached(updated);
                    assignmentRepository.insertHistory(command.releaseId(), command.deviceId(),
                            command.id(), command.cohortId(), command.generation(),
                            request.result().name(), true, false, now);
                    return CommandView.of(assignmentRepository.findCommandById(commandId).orElseThrow());
                }
                case SUPERSEDED -> {
                    // 迁移提交后到达的旧代次回执：只保存历史LATE，不结算、不改统计/失败率/暂停位
                    assignmentRepository.insertHistory(command.releaseId(), command.deviceId(),
                            command.id(), command.cohortId(), command.generation(), "LATE", false, false, now);
                    return CommandView.of(command);
                }
                case SUCCESS, FAILED -> {
                    // 新代次回执只能结算一次：重复/改结果回执不重复计数
                    boolean sameResult = command.firstResult() == request.result();
                    assignmentRepository.insertHistory(command.releaseId(), command.deviceId(),
                            command.id(), command.cohortId(), command.generation(),
                            request.result().name(), false, true, now);
                    if (!sameResult) {
                        throw ApiException.conflict("RECEIPT_RESULT_CONFLICT",
                                "指令已终结为 " + command.firstResult() + "，不能改为 " + request.result());
                    }
                    return CommandView.of(command);
                }
                default -> throw new IllegalStateException("未知指令状态: " + command.status());
            }
        }, CommandView.class);
    }

    /**
     * 队列人工恢复：仅 paused 队列可恢复，清零本轮统计（开启新一轮监控）。
     */
    public CohortView resumeCohort(long cohortId, ResumeCohortRequest request) {
        String fingerprint = String.join("|", "cohort.resume", String.valueOf(cohortId), request.reason());
        return idempotency.execute(request.requestId(), "cohort.resume", fingerprint, () -> {
            Cohort snapshot = cohortRepository.findCohortById(cohortId)
                    .orElseThrow(() -> ApiException.notFound("COHORT_NOT_FOUND", "队列不存在: " + cohortId));
            lockRelease(snapshot.releaseId());
            Cohort cohort = cohortRepository.findCohortByIdForUpdate(cohortId)
                    .orElseThrow(() -> ApiException.notFound("COHORT_NOT_FOUND", "队列不存在: " + cohortId));
            if (!cohort.paused()) {
                throw ApiException.conflict("COHORT_NOT_PAUSED", "队列未暂停，不能恢复");
            }
            cohortRepository.resumeCohort(cohortId);
            Cohort updated = cohortRepository.findCohortById(cohortId).orElseThrow();
            return CohortView.of(updated, cohortRepository.countAssignmentsByCohort(cohortId));
        }, CohortView.class);
    }

    public CohortView getCohort(long cohortId) {
        Cohort cohort = cohortRepository.findCohortById(cohortId)
                .orElseThrow(() -> ApiException.notFound("COHORT_NOT_FOUND", "队列不存在: " + cohortId));
        return CohortView.of(cohort, cohortRepository.countAssignmentsByCohort(cohortId));
    }

    /**
     * 样本达标且 FAILED×100 &gt;= 样本数×阈值 时，将尚未暂停的队列同事务置暂停位。
     * 调用方持有活动行锁与队列行锁，并发回执串行，暂停至多生效一次。
     */
    private void pauseCohortIfThresholdReached(Cohort cohort) {
        if (cohort.paused()) {
            return;
        }
        int samples = cohort.successCount() + cohort.failedCount();
        if (samples < cohort.sampleFloor()) {
            return;
        }
        if ((long) cohort.failedCount() * 100 < (long) samples * cohort.failureThresholdPercent()) {
            return;
        }
        cohortRepository.pauseIfNotPaused(cohort.id());
    }

    private ReleaseOrder requireExistingActiveRelease(long releaseId) {
        ReleaseOrder order = releaseRepository.findById(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "投放活动不存在: " + releaseId));
        if (order.status() == ReleaseStatus.CANCELLED) {
            throw ApiException.conflict("RELEASE_ENDED", "投放活动已结束");
        }
        return order;
    }

    private ReleaseOrder lockRelease(long releaseId) {
        ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "投放活动不存在: " + releaseId));
        if (order.status() == ReleaseStatus.CANCELLED) {
            throw ApiException.conflict("RELEASE_ENDED", "投放活动已结束");
        }
        return order;
    }

    private void requireRegion(long releaseId, String regionCode) {
        boolean exists = cohortRepository.findRegionsByReleaseForUpdate(releaseId).stream()
                .anyMatch(region -> region.regionCode().equals(regionCode));
        if (!exists) {
            throw ApiException.notFound("REGION_NOT_FOUND", "区域不存在: " + regionCode);
        }
    }

    /**
     * 校验队列规模后态：设备上限与灰度百分比（ceil(设备上限×百分比/100)）。
     */
    static void validateCohortCapacity(Cohort cohort, int afterCount, String scene) {
        if (afterCount > cohort.deviceCap()) {
            throw ApiException.unprocessable("DEVICE_CAP_EXCEEDED",
                    scene + "后队列设备数 " + afterCount + " 超过设备上限 " + cohort.deviceCap()
                            + "（队列 " + cohort.cohortCode() + "）");
        }
        int canaryLimit = (cohort.deviceCap() * cohort.canaryPercent() + 99) / 100;
        if (afterCount > canaryLimit) {
            throw ApiException.unprocessable("CANARY_LIMIT_EXCEEDED",
                    scene + "后队列设备数 " + afterCount + " 超过灰度上限 " + canaryLimit
                            + "（设备上限 " + cohort.deviceCap() + "×灰度 " + cohort.canaryPercent()
                            + "%，队列 " + cohort.cohortCode() + "）");
        }
    }
}
