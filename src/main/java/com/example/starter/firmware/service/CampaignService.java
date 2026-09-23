package com.example.starter.firmware.service;

import com.example.starter.firmware.api.AssignmentView;
import com.example.starter.firmware.api.CampaignReceiptRequest;
import com.example.starter.firmware.api.CampaignReceiptView;
import com.example.starter.firmware.api.CampaignView;
import com.example.starter.firmware.api.CohortView;
import com.example.starter.firmware.api.CreateCampaignRequest;
import com.example.starter.firmware.api.CreateCohortRequest;
import com.example.starter.firmware.api.EnrollDeviceRequest;
import com.example.starter.firmware.domain.Campaign;
import com.example.starter.firmware.domain.CampaignStatus;
import com.example.starter.firmware.domain.Cohort;
import com.example.starter.firmware.domain.CohortStatus;
import com.example.starter.firmware.domain.DeviceAssignment;
import com.example.starter.firmware.domain.DispatchCommand;
import com.example.starter.firmware.domain.ReceiptDisposition;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.AssignmentRepository;
import com.example.starter.firmware.repo.CampaignRepository;
import com.example.starter.firmware.repo.CohortReceiptRepository;
import com.example.starter.firmware.repo.CohortRepository;
import com.example.starter.firmware.repo.DispatchCommandRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 投放活动与队列：创建、设备入组、回执入账、失败率自动暂停、人工恢复与活动结束。
 * 所有变更先锁活动行再操作，与迁移形成一致提交顺序；
 * 回执按指令代次隔离：当前代次结算入队，迟到旧代次仅存档为 LATE。
 */
@Service
public class CampaignService {

    private final CampaignRepository campaignRepository;
    private final CohortRepository cohortRepository;
    private final AssignmentRepository assignmentRepository;
    private final DispatchCommandRepository commandRepository;
    private final CohortReceiptRepository receiptRepository;
    private final DeviceService deviceService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public CampaignService(CampaignRepository campaignRepository, CohortRepository cohortRepository,
                           AssignmentRepository assignmentRepository,
                           DispatchCommandRepository commandRepository,
                           CohortReceiptRepository receiptRepository,
                           DeviceService deviceService, IdempotencyService idempotency, Clock clock) {
        this.campaignRepository = campaignRepository;
        this.cohortRepository = cohortRepository;
        this.assignmentRepository = assignmentRepository;
        this.commandRepository = commandRepository;
        this.receiptRepository = receiptRepository;
        this.deviceService = deviceService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public CampaignView create(CreateCampaignRequest request) {
        String fingerprint = String.join("|", "campaign.create", request.name());
        return idempotency.execute(request.requestId(), "campaign.create", fingerprint, () -> {
            long id = campaignRepository.insert(request.name());
            return CampaignView.of(findCampaign(id));
        }, CampaignView.class);
    }

    public CohortView createCohort(long campaignId, CreateCohortRequest request) {
        String fingerprint = String.join("|", "campaign.cohort.create", String.valueOf(campaignId),
                request.code(), request.firmwareVersion(), request.region(),
                String.valueOf(request.regionQuota()), String.valueOf(request.deviceCap()),
                String.valueOf(request.grayPercent()), String.valueOf(request.effectiveSampleFloor()),
                String.valueOf(request.effectiveFailureThresholdPercent()));
        return idempotency.execute(request.requestId(), "campaign.cohort.create", fingerprint, () -> {
            Campaign campaign = lockCampaign(campaignId);
            requireActive(campaign);
            long cohortId;
            try {
                cohortId = cohortRepository.insert(campaignId, request.code(), request.firmwareVersion(),
                        request.region(), request.regionQuota(), request.deviceCap(),
                        request.grayPercent(), request.effectiveSampleFloor(),
                        request.effectiveFailureThresholdPercent());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("COHORT_CODE_EXISTS", "队列编码已存在: " + request.code());
            }
            return CohortView.of(findCohort(cohortId));
        }, CohortView.class);
    }

    /**
     * 设备入组：校验活动与队列可用、队列配额后创建分配（版本与代次均从1开始）并签发未决指令。
     */
    public AssignmentView enroll(long campaignId, EnrollDeviceRequest request) {
        String fingerprint = String.join("|", "campaign.enroll", String.valueOf(campaignId),
                request.deviceId(), String.valueOf(request.cohortId()));
        return idempotency.execute(request.requestId(), "campaign.enroll", fingerprint, () -> {
            Campaign campaign = lockCampaign(campaignId);
            requireActive(campaign);
            deviceService.findDevice(request.deviceId());
            Cohort cohort = cohortRepository.findByCampaignAndId(campaignId, request.cohortId())
                    .orElseThrow(() -> ApiException.notFound("COHORT_NOT_FOUND",
                            "队列不存在: " + request.cohortId()));
            if (cohort.status() != CohortStatus.ACTIVE) {
                throw ApiException.conflict("COHORT_NOT_ACTIVE", "队列已暂停，不能入组: " + cohort.code());
            }
            long totalAssigned = assignmentRepository.countByCampaign(campaignId);
            checkQuota(cohort, cohort.deviceCount() + 1, totalAssigned + 1);
            try {
                assignmentRepository.insert(campaignId, request.deviceId(), cohort.id());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_ALREADY_ASSIGNED",
                        "设备已在活动内: " + request.deviceId());
            }
            cohortRepository.adjustDeviceCount(cohort.id(), 1);
            commandRepository.insertPending(campaignId, request.deviceId(), cohort.id(), 1);
            return AssignmentView.of(findAssignment(campaignId, request.deviceId()));
        }, AssignmentView.class);
    }

    /**
     * 回执入账：当前代次首次回执结算入队（仅 SUCCESS 确认安装成功），同代次重复回执不重复计数、
     * 改结果 409；旧代次回执若已在迁移提交前结算则按旧队列口径保持不变，迁移提交后迟到的
     * 旧代次回执只存档为 LATE，不改变任何队列统计；更高代次 409。
     * 样本达到下限且失败率越限时，同事务将仍为 ACTIVE 的队列原子转为 PAUSED。
     */
    public CampaignReceiptView receipt(long campaignId, CampaignReceiptRequest request) {
        String fingerprint = String.join("|", "campaign.receipt", String.valueOf(campaignId),
                request.deviceId(), String.valueOf(request.generation()), request.result().name());
        return idempotency.execute(request.requestId(), "campaign.receipt", fingerprint, () -> {
            Campaign campaign = lockCampaign(campaignId);
            requireActive(campaign);
            DeviceAssignment assignment = assignmentRepository.findForUpdate(campaignId,
                            request.deviceId())
                    .orElseThrow(() -> ApiException.notFound("ASSIGNMENT_NOT_FOUND",
                            "设备未入组: " + request.deviceId()));
            var existing = receiptRepository.findByGeneration(campaignId, request.deviceId(),
                    request.generation());
            if (existing.isPresent()) {
                if (existing.get().result() != request.result()) {
                    throw ApiException.conflict("RECEIPT_RESULT_CONFLICT",
                            "代次 " + request.generation() + " 回执已入账为 "
                                    + existing.get().result() + "，不能改为 " + request.result());
                }
                return CampaignReceiptView.of(existing.get());
            }
            if (request.generation() > assignment.assignmentGeneration()) {
                throw ApiException.conflict("UNKNOWN_GENERATION",
                        "回执代次超过当前指令代次: " + assignment.assignmentGeneration());
            }
            String receivedAt = Instant.now(clock).toString();
            if (request.generation() < assignment.assignmentGeneration()) {
                long cohortId = commandRepository
                        .findByGeneration(campaignId, request.deviceId(), request.generation())
                        .map(DispatchCommand::cohortId)
                        .orElse(assignment.cohortId());
                receiptRepository.insert(campaignId, request.deviceId(), cohortId,
                        request.generation(), request.result(), ReceiptDisposition.LATE, receivedAt);
                return CampaignReceiptView.of(receiptRepository
                        .findByGeneration(campaignId, request.deviceId(), request.generation())
                        .orElseThrow());
            }
            Cohort cohort = findCohort(assignment.cohortId());
            assignmentRepository.markSettled(campaignId, request.deviceId(), request.generation(),
                    request.result());
            commandRepository.settleIfPending(campaignId, request.deviceId(), request.generation());
            cohortRepository.incrementSettledStats(cohort.id(), request.result());
            receiptRepository.insert(campaignId, request.deviceId(), cohort.id(),
                    request.generation(), request.result(), ReceiptDisposition.SETTLED, receivedAt);
            pauseIfThresholdReached(cohortRepository.findById(cohort.id()).orElseThrow());
            return CampaignReceiptView.of(receiptRepository
                    .findByGeneration(campaignId, request.deviceId(), request.generation())
                    .orElseThrow());
        }, CampaignReceiptView.class);
    }

    /**
     * 人工恢复：仅 PAUSED 队列可恢复为 ACTIVE，开启新监控轮次并清零轮次统计。
     */
    public CohortView resumeCohort(long campaignId, long cohortId, String requestId) {
        String fingerprint = String.join("|", "campaign.cohort.resume", String.valueOf(campaignId),
                String.valueOf(cohortId));
        return idempotency.execute(requestId, "campaign.cohort.resume", fingerprint, () -> {
            Campaign campaign = lockCampaign(campaignId);
            requireActive(campaign);
            Cohort cohort = cohortRepository.findByCampaignAndId(campaignId, cohortId)
                    .orElseThrow(() -> ApiException.notFound("COHORT_NOT_FOUND",
                            "队列不存在: " + cohortId));
            if (cohort.status() != CohortStatus.PAUSED) {
                throw ApiException.conflict("COHORT_NOT_PAUSED",
                        "队列状态为 " + cohort.status() + "，仅 PAUSED 可恢复");
            }
            cohortRepository.resumeIfPaused(cohortId);
            return CohortView.of(findCohort(cohortId));
        }, CohortView.class);
    }

    /**
     * 结束活动：终态，结束后禁止入组、回执、恢复与迁移。
     */
    public CampaignView end(long campaignId, String requestId) {
        String fingerprint = String.join("|", "campaign.end", String.valueOf(campaignId));
        return idempotency.execute(requestId, "campaign.end", fingerprint, () -> {
            Campaign campaign = lockCampaign(campaignId);
            if (campaign.status() == CampaignStatus.ACTIVE) {
                campaignRepository.endIfActive(campaignId);
            }
            return CampaignView.of(findCampaign(campaignId));
        }, CampaignView.class);
    }

    public List<CohortView> listCohorts(long campaignId) {
        findCampaign(campaignId);
        return cohortRepository.findByCampaign(campaignId).stream().map(CohortView::of).toList();
    }

    public AssignmentView getAssignment(long campaignId, String deviceId) {
        findCampaign(campaignId);
        return AssignmentView.of(findAssignment(campaignId, deviceId));
    }

    /**
     * 样本数达到下限且 FAILED×100 >= 样本数×阈值 时，将仍为 ACTIVE 的队列原子转为 PAUSED。
     * 调用方持有活动行锁，并发回执串行通过。
     */
    private void pauseIfThresholdReached(Cohort cohort) {
        if (cohort.status() != CohortStatus.ACTIVE) {
            return;
        }
        int samples = cohort.roundSuccess() + cohort.roundFailed();
        if (samples < cohort.sampleFloor()) {
            return;
        }
        if ((long) cohort.roundFailed() * 100 < (long) samples * cohort.failureThresholdPercent()) {
            return;
        }
        cohortRepository.pauseIfActive(cohort.id());
    }

    /**
     * 队列后态配额校验：设备上限、区域配额与灰度百分比（相对活动内设备总数）。
     */
    static void checkQuota(Cohort cohort, int postSize, long totalAssigned) {
        if (postSize > cohort.deviceCap()) {
            throw ApiException.unprocessable("DEVICE_CAP_EXCEEDED",
                    "队列 " + cohort.code() + " 后态规模 " + postSize
                            + " 超过设备上限 " + cohort.deviceCap());
        }
        if (postSize > cohort.regionQuota()) {
            throw ApiException.unprocessable("REGION_QUOTA_EXCEEDED",
                    "队列 " + cohort.code() + " 后态规模 " + postSize
                            + " 超过区域配额 " + cohort.regionQuota());
        }
        if ((long) postSize * 100 > totalAssigned * cohort.grayPercent()) {
            throw ApiException.unprocessable("GRAY_PERCENT_EXCEEDED",
                    "队列 " + cohort.code() + " 后态规模 " + postSize
                            + " 超过灰度百分比 " + cohort.grayPercent() + "% 允许的规模");
        }
    }

    Campaign lockCampaign(long campaignId) {
        return campaignRepository.findByIdForUpdate(campaignId)
                .orElseThrow(() -> ApiException.notFound("CAMPAIGN_NOT_FOUND",
                        "投放活动不存在: " + campaignId));
    }

    static void requireActive(Campaign campaign) {
        if (campaign.status() != CampaignStatus.ACTIVE) {
            throw ApiException.conflict("CAMPAIGN_ENDED", "投放活动已结束: " + campaign.id());
        }
    }

    Campaign findCampaign(long campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> ApiException.notFound("CAMPAIGN_NOT_FOUND",
                        "投放活动不存在: " + campaignId));
    }

    Cohort findCohort(long cohortId) {
        return cohortRepository.findById(cohortId)
                .orElseThrow(() -> ApiException.notFound("COHORT_NOT_FOUND", "队列不存在: " + cohortId));
    }

    DeviceAssignment findAssignment(long campaignId, String deviceId) {
        return assignmentRepository.find(campaignId, deviceId)
                .orElseThrow(() -> ApiException.notFound("ASSIGNMENT_NOT_FOUND",
                        "设备未入组: " + deviceId));
    }
}
