package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.AccessTokenView;
import com.example.starter.blind.dto.DataSubmissionView;
import com.example.starter.blind.repo.AccessGenerationRepository;
import com.example.starter.blind.repo.AccessGenerationRepository.AccessGenerationRow;
import com.example.starter.blind.repo.AccessTokenRepository;
import com.example.starter.blind.repo.AccessTokenRepository.AccessTokenRow;
import com.example.starter.blind.repo.DataSubmissionRepository;
import com.example.starter.blind.repo.DataSubmissionRepository.DataSubmissionRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 代次令牌签发与数据提交的强制访问控制。
 *
 * <p>令牌仅在其代次仍为实验当前 ACTIVE 代次时有效；轮换在同一事务切换活动指针后，
 * 旧代次令牌（即使在生效时刻之前签发）一律拒绝。数据提交按事务提交顺序归属当时活动代次。</p>
 */
@Service
public class AccessControlService {

    private final ExperimentRepository experimentRepository;
    private final AccessGenerationRepository generationRepository;
    private final AccessTokenRepository accessTokenRepository;
    private final DataSubmissionRepository dataSubmissionRepository;
    private final AllocationRepository allocationRepository;
    private final AccessTokenGenerator tokenGenerator;
    private final Clock clock;

    public AccessControlService(ExperimentRepository experimentRepository,
                                AccessGenerationRepository generationRepository,
                                AccessTokenRepository accessTokenRepository,
                                DataSubmissionRepository dataSubmissionRepository,
                                AllocationRepository allocationRepository,
                                AccessTokenGenerator tokenGenerator,
                                Clock clock) {
        this.experimentRepository = experimentRepository;
        this.generationRepository = generationRepository;
        this.accessTokenRepository = accessTokenRepository;
        this.dataSubmissionRepository = dataSubmissionRepository;
        this.allocationRepository = allocationRepository;
        this.tokenGenerator = tokenGenerator;
        this.clock = clock;
    }

    /**
     * 为当前 ACTIVE 代次中、与请求头同名角色的在册人员签发代次令牌。
     * 实验关闭或尚无活动代次时拒绝。
     */
    @Transactional
    public AccessTokenView issueToken(String experimentId, String actorId, String roleName) {
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        AccessGenerationRow generation = requireOpenActiveGeneration(experiment);
        if (!isRosterMember(generation.id(), roleName, actorId)) {
            throw ApiException.forbidden("人员不在当前授权代次的 " + roleName + " 名册中");
        }
        long now = clock.nowMillis();
        String tokenId = uniqueToken();
        accessTokenRepository.insert(new AccessTokenRow(tokenId, generation.id(), experimentId,
                actorId, roleName, now));
        return new AccessTokenView(tokenId, experimentId, generation.id(),
                generation.generationNo(), actorId, roleName, now);
    }

    /**
     * 数据提交：令牌代次必须仍是当前活动代次；提交人须在该代次名册且被授予该受试者范围。
     * 行锁实验串行化与轮换的并发，按事务提交顺序归属旧或新代次。
     */
    @Transactional
    public DataSubmissionView submit(String experimentId, String participantId, String tokenId,
                                     String actorId, String roleName, String payload) {
        if (tokenId == null || tokenId.isBlank()) {
            throw ApiException.unauthorized("缺少 X-Access-Token 请求头");
        }
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        AccessGenerationRow activeGeneration = requireOpenActiveGeneration(experiment);

        AccessTokenRow token = accessTokenRepository.findById(tokenId);
        if (token == null) {
            throw ApiException.unauthorized("代次令牌不存在");
        }
        if (!token.experimentId().equals(experimentId)) {
            throw ApiException.forbidden("令牌不属于该实验");
        }
        if (!token.actorId().equals(actorId) || !token.roleName().equals(roleName)) {
            // 令牌绑定持有人与角色，冒用身份直接拒绝。
            throw ApiException.forbidden("令牌持有人/角色与请求身份不一致");
        }
        if (token.generationId() != activeGeneration.id()) {
            // 生效时刻前签发、代次切换后使用的旧令牌：拒绝。
            throw ApiException.conflict("授权代次已轮换，旧代次令牌已失效");
        }
        if (!RotationService.ROLE_DATA_COLLECTOR.equals(roleName)
                || !isRosterMember(activeGeneration.id(), roleName, actorId)) {
            throw ApiException.forbidden("仅当前代次在册数据采集者可提交数据");
        }
        AllocationRow allocation =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
            throw ApiException.notFound("受试者尚未在该实验登记");
        }
        if (!"ASSIGNED".equals(allocation.status())) {
            // 受试者已退组（结束），不属于采集范围。
            throw ApiException.forbidden("受试者已结束，不在数据采集范围内");
        }
        if (!generationRepository.scopeContains(activeGeneration.id(), actorId, participantId)) {
            // 不在最小授权范围（含轮换后被排除的受试者）。
            throw ApiException.forbidden("该受试者不在当前代次授予该采集者的数据范围内");
        }

        long now = clock.nowMillis();
        long submissionId = dataSubmissionRepository.insert(new DataSubmissionRow(
                0L, experimentId, participantId, actorId, activeGeneration.id(), payload, now));
        return new DataSubmissionView(submissionId, experimentId, participantId, actorId,
                activeGeneration.id(), activeGeneration.generationNo(), payload, now);
    }

    private AccessGenerationRow requireOpenActiveGeneration(ExperimentRow experiment) {
        if (experiment == null) {
            throw ApiException.notFound("实验不存在");
        }
        if (!"OPEN".equals(experiment.status())) {
            throw ApiException.conflict("实验已关闭，拒绝签发令牌或提交数据");
        }
        if (experiment.activeGenerationId() == null) {
            throw ApiException.conflict("实验尚无活动授权代次，请先执行职责轮换");
        }
        AccessGenerationRow generation =
                generationRepository.findById(experiment.activeGenerationId());
        if (generation == null || !"ACTIVE".equals(generation.status())) {
            // 结构性保证不应发生；防御性拒绝。
            throw ApiException.conflict("当前活动授权代次不可用");
        }
        return generation;
    }

    private boolean isRosterMember(long generationId, String roleName, String actorId) {
        return generationRepository.findRoleAssignments(generationId).stream()
                .anyMatch(a -> a.roleName().equals(roleName) && a.actorId().equals(actorId));
    }

    private String uniqueToken() {
        // 随机冲突概率极低，仍重试若干次由主键兜底。
        for (int attempt = 0; attempt < 5; attempt++) {
            String token = tokenGenerator.nextToken();
            if (accessTokenRepository.findById(token) == null) {
                return token;
            }
        }
        throw ApiException.conflict("令牌生成冲突，请重试");
    }
}
