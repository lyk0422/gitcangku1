package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.PhaseStatus;
import com.example.starter.blind.dto.PhaseView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.PhaseStateRepository;
import com.example.starter.blind.repo.PhaseStateRepository.PhaseStateRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 交叉实验阶段业务：第一阶段开始/结束、洗脱、第二阶段开始/结束。
 * <ul>
 *   <li>阶段操作、退组、关闭共用实验行锁串行裁决；两个阶段至多各开始结束一次；</li>
 *   <li>第一阶段处理沿用席位原隐藏代码，第二阶段为另一代码；处理代码不出现在任何阶段响应；</li>
 *   <li>第一阶段结束满洗脱时长（UTC 服务端时钟）才允许开始第二阶段，不足返回 422；</li>
 *   <li>未开始的阶段不能结束（409）；退组后新阶段操作 410；实验关闭禁止新开始但不自动完成。</li>
 * </ul>
 */
@Service
public class PhaseService {

    private static final long MILLIS_PER_MINUTE = 60_000L;

    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final PhaseStateRepository phaseStateRepository;
    private final Clock clock;

    public PhaseService(ExperimentRepository experimentRepository,
                        AllocationRepository allocationRepository,
                        PhaseStateRepository phaseStateRepository,
                        Clock clock) {
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.phaseStateRepository = phaseStateRepository;
        this.clock = clock;
    }

    /** 第一阶段开始；仅 NOT_STARTED 可开始，实验关闭或已退组时拒绝。 */
    @Transactional
    public PhaseView startFirst(String experimentId, String participantId, Long expectedVersion) {
        PhaseContext ctx = loadForWrite(experimentId, participantId, true);
        checkVersion(ctx.state(), expectedVersion);
        if (!PhaseStatus.NOT_STARTED.name().equals(ctx.state().status())) {
            throw ApiException.conflict("第一阶段只能开始一次");
        }
        long now = clock.nowMillis();
        if (phaseStateRepository.markFirstStarted(ctx.allocation().id(), now) == 0) {
            throw ApiException.conflict("阶段状态已变化，请重试");
        }
        return getPhases(experimentId, participantId);
    }

    /** 第一阶段结束并进入洗脱；未开始的阶段不能结束。 */
    @Transactional
    public PhaseView endFirst(String experimentId, String participantId, Long expectedVersion) {
        PhaseContext ctx = loadForWrite(experimentId, participantId, false);
        checkVersion(ctx.state(), expectedVersion);
        if (!PhaseStatus.FIRST_RUNNING.name().equals(ctx.state().status())) {
            throw ApiException.conflict("第一阶段未在进行中，不能结束");
        }
        long now = clock.nowMillis();
        if (phaseStateRepository.markFirstEnded(ctx.allocation().id(), now) == 0) {
            throw ApiException.conflict("阶段状态已变化，请重试");
        }
        return getPhases(experimentId, participantId);
    }

    /** 第二阶段开始；须洗脱期满，不足 422。 */
    @Transactional
    public PhaseView startSecond(String experimentId, String participantId, Long expectedVersion) {
        PhaseContext ctx = loadForWrite(experimentId, participantId, true);
        checkVersion(ctx.state(), expectedVersion);
        String status = ctx.state().status();
        if (PhaseStatus.NOT_STARTED.name().equals(status)
                || PhaseStatus.FIRST_RUNNING.name().equals(status)) {
            throw ApiException.conflict("第一阶段尚未结束，不能开始第二阶段");
        }
        if (!PhaseStatus.WASHOUT.name().equals(status)) {
            throw ApiException.conflict("第二阶段只能开始一次");
        }
        long now = clock.nowMillis();
        long earliest = ctx.state().firstEndedAt()
                + ctx.experiment().washoutMinutes() * MILLIS_PER_MINUTE;
        if (now < earliest) {
            throw ApiException.unprocessable("洗脱期未满，第二阶段暂不能开始");
        }
        if (phaseStateRepository.markSecondStarted(ctx.allocation().id(), now) == 0) {
            throw ApiException.conflict("阶段状态已变化，请重试");
        }
        return getPhases(experimentId, participantId);
    }

    /** 第二阶段结束并完成；未开始的阶段不能结束。 */
    @Transactional
    public PhaseView endSecond(String experimentId, String participantId, Long expectedVersion) {
        PhaseContext ctx = loadForWrite(experimentId, participantId, false);
        checkVersion(ctx.state(), expectedVersion);
        if (!PhaseStatus.SECOND_RUNNING.name().equals(ctx.state().status())) {
            throw ApiException.conflict("第二阶段未在进行中，不能结束");
        }
        long now = clock.nowMillis();
        if (phaseStateRepository.markSecondEnded(ctx.allocation().id(), now) == 0) {
            throw ApiException.conflict("阶段状态已变化，请重试");
        }
        return getPhases(experimentId, participantId);
    }

    /** 普通阶段查询/历史：只含盲码、状态与时刻，不含处理代码。 */
    public PhaseView getPhases(String experimentId, String participantId) {
        ExperimentRow experiment = experimentRepository.findById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        AllocationRow allocation =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        requireCrossover(experiment);
        PhaseStateRow state = phaseStateRepository.findByAllocationId(allocation.id());
        if (state == null) {
            throw new IllegalStateException("交叉实验缺少阶段状态行，数据不一致");
        }
        return toView(experiment, allocation, state);
    }

    /**
     * 载入写操作上下文并做公共校验：实验行锁串行化 → 参与者存在 → 交叉模式 →
     * 退组 410 → 开始类操作在实验关闭时 409。
     */
    private PhaseContext loadForWrite(String experimentId, String participantId, boolean isStart) {
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        AllocationRow allocation =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        requireCrossover(experiment);
        if ("WITHDRAWN".equals(allocation.status())) {
            throw ApiException.gone("参与者已退组，拒绝新阶段操作");
        }
        if (isStart && "CLOSED".equals(experiment.status())) {
            throw ApiException.conflict("实验已关闭，禁止新阶段开始");
        }
        PhaseStateRow state = phaseStateRepository.lockByAllocationId(allocation.id());
        if (state == null) {
            throw new IllegalStateException("交叉实验缺少阶段状态行，数据不一致");
        }
        return new PhaseContext(experiment, allocation, state);
    }

    private void requireCrossover(ExperimentRow experiment) {
        if (!ExperimentService.MODE_CROSSOVER.equals(experiment.mode())) {
            throw ApiException.conflict("该实验非交叉模式，无阶段操作");
        }
    }

    private void checkVersion(PhaseStateRow state, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion.longValue() != state.version()) {
            throw ApiException.conflict("期望版本与当前阶段状态版本不一致");
        }
    }

    private PhaseView toView(ExperimentRow experiment, AllocationRow allocation,
                             PhaseStateRow state) {
        return new PhaseView(state.experimentId(), state.participantId(), allocation.blindCode(),
                state.status(), state.version(),
                experiment.washoutMinutes() == null ? 0 : experiment.washoutMinutes(),
                state.firstStartedAt(), state.firstEndedAt(),
                state.secondStartedAt(), state.secondEndedAt());
    }

    /** 一次阶段写操作的事务上下文。 */
    private record PhaseContext(ExperimentRow experiment, AllocationRow allocation,
                                PhaseStateRow state) {
    }
}
