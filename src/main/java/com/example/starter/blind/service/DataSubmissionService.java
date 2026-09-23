package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.DutyRole;
import com.example.starter.blind.dto.DataSubmissionView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.RotationRepository;
import com.example.starter.blind.repo.RotationRepository.GenerationRow;
import com.example.starter.blind.repo.RotationRepository.GrantRow;
import com.example.starter.blind.repo.SubjectDataRepository;
import com.example.starter.blind.repo.UnblindRequestRepository;
import com.example.starter.blind.repo.UnblindRequestRepository.UnblindRequestRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 受试者数据提交：
 * 仅当前活动代次下持有 DATA_COLLECTOR 最小授权的人员可写；
 * 生效时刻前签发的旧代次令牌一律拒绝；写入按事务提交顺序归属旧或新代次。
 * 实验行锁保证与轮换、关闭、另一提交串行，杜绝两代授权同时有效。
 */
@Service
public class DataSubmissionService {

    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final UnblindRequestRepository unblindRequestRepository;
    private final RotationRepository rotationRepository;
    private final SubjectDataRepository subjectDataRepository;
    private final Clock clock;

    public DataSubmissionService(ExperimentRepository experimentRepository,
                                 AllocationRepository allocationRepository,
                                 UnblindRequestRepository unblindRequestRepository,
                                 RotationRepository rotationRepository,
                                 SubjectDataRepository subjectDataRepository,
                                 Clock clock) {
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.unblindRequestRepository = unblindRequestRepository;
        this.rotationRepository = rotationRepository;
        this.subjectDataRepository = subjectDataRepository;
        this.clock = clock;
    }

    /**
     * 提交受试者数据；归属提交事务内读到的活动代次。
     */
    @Transactional
    public DataSubmissionView submit(String experimentId, String participantId,
                                     long accessGeneration, String payload, String actorId) {
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        if (!"OPEN".equals(experiment.status())) {
            throw ApiException.conflict("实验已关闭，拒绝数据提交");
        }
        GenerationRow generation = rotationRepository.findActiveGeneration(experimentId);
        if (generation == null) {
            throw ApiException.conflict("当前无活动授权代次");
        }
        if (generation.generationNo() != accessGeneration) {
            // 生效时刻前签发、之后使用的旧代次令牌在此被拒绝。
            throw ApiException.conflict("授权代次已失效，请使用当前代次 "
                    + generation.generationNo());
        }
        long now = clock.nowMillis();
        if (now < generation.effectiveAt()) {
            throw ApiException.conflict("授权代次尚未生效");
        }
        GrantRow grant = rotationRepository.findGrant(generation.id(), actorId,
                DutyRole.DATA_COLLECTOR.name());
        if (grant == null) {
            throw ApiException.forbidden("当前代次下无数据采集授权");
        }
        AllocationRow allocation =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        if (!"ASSIGNED".equals(allocation.status())) {
            throw ApiException.conflict("受试者已结束，拒绝数据提交");
        }
        // 已通过揭盲知悉该受试者分组的人不得采集其数据（知情历史不可删除，持续生效）。
        for (UnblindRequestRow row : unblindRequestRepository.findApprovedByExperiment(experimentId)) {
            if (row.participantId().equals(participantId)
                    && (row.applicantActor().equals(actorId)
                    || actorId.equals(row.reviewerActor()))) {
                throw ApiException.forbidden("已知悉该受试者分组，不得承担其数据采集");
            }
        }
        long id = subjectDataRepository.insert(experimentId, participantId, actorId,
                generation.generationNo(), payload, now);
        return new DataSubmissionView(id, experimentId, participantId, actorId,
                generation.generationNo(), now);
    }
}
