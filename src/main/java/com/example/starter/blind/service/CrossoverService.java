package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.PhaseView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.CrossoverPhaseRepository;
import com.example.starter.blind.repo.CrossoverPhaseRepository.CrossoverPhaseRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 交叉实验阶段业务：
 * 同一参与者按盲码完成两个阶段，第一阶段处理沿用席位隐藏代码，第二阶段为其对侧代码；
 * 阶段状态由时间戳派生（FIRST_RUNNING/WASHOUT/SECOND_RUNNING/DONE），每阶段至多开始、结束各一次；
 * 第一阶段结束后满洗脱时长（服务端 UTC 时钟）才可开始第二阶段；
 * 阶段操作与退组、关闭共用实验行锁串行裁决；关闭禁止新开始但已开始阶段可结束；
 * 退组后一切阶段操作返回 410。普通阶段视图不含处理代码与席位号。
 */
@Service
public class CrossoverService {

    private static final long MILLIS_PER_MINUTE = 60_000L;

    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final CrossoverPhaseRepository crossoverPhaseRepository;
    private final Clock clock;

    public CrossoverService(ExperimentRepository experimentRepository,
                            AllocationRepository allocationRepository,
                            CrossoverPhaseRepository crossoverPhaseRepository,
                            Clock clock) {
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.crossoverPhaseRepository = crossoverPhaseRepository;
        this.clock = clock;
    }

    /**
     * 开始指定阶段（1 或 2）。关闭的实验禁止新开始；退组参与者 410；
     * 第二阶段要求第一阶段已结束且洗脱期满，否则分别 409 / 422。
     */
    @Transactional
    public PhaseView startPhase(String experimentId, String participantId, int phase,
                                Integer expectedVersion) {
        ExperimentRow experiment = lockCrossoverExperiment(experimentId);
        AllocationRow allocation = mustFindAllocation(experimentId, participantId);
        requireActive(allocation);
        if ("CLOSED".equals(experiment.status())) {
            throw ApiException.conflict("实验已关闭，禁止新阶段开始");
        }
        CrossoverPhaseRow row = mustFindPhase(allocation.id());
        requireVersion(row, expectedVersion);
        long now = clock.nowMillis();
        if (phase == 1) {
            if (row.firstStartedAt() != null) {
                throw ApiException.conflict("第一阶段已开始，不能重复开始");
            }
            crossoverPhaseRepository.startFirst(allocation.id(), now);
        } else {
            if (row.firstEndedAt() == null) {
                throw ApiException.conflict("第一阶段尚未结束，不能开始第二阶段");
            }
            if (row.secondStartedAt() != null) {
                throw ApiException.conflict("第二阶段已开始，不能重复开始");
            }
            long earliest = row.firstEndedAt() + experiment.washoutMinutes() * MILLIS_PER_MINUTE;
            if (now < earliest) {
                throw ApiException.unprocessable("洗脱期未满，第二阶段最早可于 " + earliest
                        + "（Unix 毫秒 UTC）开始");
            }
            crossoverPhaseRepository.startSecond(allocation.id(), now);
        }
        return toView(experiment, allocation, mustFindPhase(allocation.id()));
    }

    /**
     * 结束指定阶段（1 或 2）。未开始不能结束（409）；已开始的阶段在实验关闭后仍可结束；
     * 退组参与者 410。
     */
    @Transactional
    public PhaseView endPhase(String experimentId, String participantId, int phase,
                              Integer expectedVersion) {
        ExperimentRow experiment = lockCrossoverExperiment(experimentId);
        AllocationRow allocation = mustFindAllocation(experimentId, participantId);
        requireActive(allocation);
        CrossoverPhaseRow row = mustFindPhase(allocation.id());
        requireVersion(row, expectedVersion);
        long now = clock.nowMillis();
        if (phase == 1) {
            if (row.firstStartedAt() == null) {
                throw ApiException.conflict("第一阶段未开始，不能结束");
            }
            if (row.firstEndedAt() != null) {
                throw ApiException.conflict("第一阶段已结束，不能重复结束");
            }
            crossoverPhaseRepository.endFirst(allocation.id(), now);
        } else {
            if (row.secondStartedAt() == null) {
                throw ApiException.conflict("第二阶段未开始，不能结束");
            }
            if (row.secondEndedAt() != null) {
                throw ApiException.conflict("第二阶段已结束，不能重复结束");
            }
            crossoverPhaseRepository.endSecond(allocation.id(), now);
        }
        return toView(experiment, allocation, mustFindPhase(allocation.id()));
    }

    /**
     * 普通阶段查询（历史）：只返回盲码、派生状态与各阶段时刻，不含处理代码。
     */
    public PhaseView getPhases(String experimentId, String participantId) {
        ExperimentRow experiment = experimentRepository.findById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        if (experiment.washoutMinutes() == null) {
            throw ApiException.conflict("非交叉实验，无阶段信息");
        }
        AllocationRow allocation = mustFindAllocation(experimentId, participantId);
        return toView(experiment, allocation, mustFindPhase(allocation.id()));
    }

    /**
     * 行级锁定实验并确认其为交叉实验；与退组、关闭共用同一把锁串行裁决。
     */
    private ExperimentRow lockCrossoverExperiment(String experimentId) {
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        if (experiment.washoutMinutes() == null) {
            throw ApiException.conflict("非交叉实验，不支持阶段操作");
        }
        return experiment;
    }

    private AllocationRow mustFindAllocation(String experimentId, String participantId) {
        AllocationRow allocation =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        return allocation;
    }

    /** 退组后一切阶段操作（开始与结束）均拒绝，返回 410。 */
    private void requireActive(AllocationRow allocation) {
        if ("WITHDRAWN".equals(allocation.status())) {
            throw ApiException.gone("参与者已退组，拒绝阶段操作");
        }
    }

    private CrossoverPhaseRow mustFindPhase(long allocationId) {
        CrossoverPhaseRow row = crossoverPhaseRepository.findByAllocationId(allocationId);
        if (row == null) {
            throw new IllegalStateException("交叉阶段记录缺失，数据不一致");
        }
        return row;
    }

    private void requireVersion(CrossoverPhaseRow row, Integer expectedVersion) {
        if (expectedVersion != null && expectedVersion != row.version()) {
            throw ApiException.conflict("期望版本不匹配：当前版本 " + row.version());
        }
    }

    private PhaseView toView(ExperimentRow experiment, AllocationRow allocation,
                             CrossoverPhaseRow row) {
        return new PhaseView(experiment.id(), allocation.participantId(), allocation.blindCode(),
                stateOf(row), experiment.washoutMinutes(), row.firstStartedAt(), row.firstEndedAt(),
                row.secondStartedAt(), row.secondEndedAt(), row.version());
    }

    /** 由时间戳派生阶段状态，避免状态字段与时间戳不一致。 */
    private String stateOf(CrossoverPhaseRow row) {
        if (row.firstStartedAt() == null) {
            return "NOT_STARTED";
        }
        if (row.firstEndedAt() == null) {
            return "FIRST_RUNNING";
        }
        if (row.secondStartedAt() == null) {
            return "WASHOUT";
        }
        if (row.secondEndedAt() == null) {
            return "SECOND_RUNNING";
        }
        return "DONE";
    }
}
