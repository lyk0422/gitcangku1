package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.AnalystSampleView;
import com.example.starter.blind.dto.SampleCoordinatorView;
import com.example.starter.blind.dto.SampleCreateView;
import com.example.starter.blind.dto.SampleReceiveView;
import com.example.starter.blind.dto.SampleResultView;
import com.example.starter.blind.dto.SampleSummaryView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.SampleRepository;
import com.example.starter.blind.repo.SampleRepository.SampleRow;
import com.example.starter.blind.repo.SampleRepository.SummaryRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 二级盲码交接业务：
 * 协调员为未退组参与者创建唯一样本并取得一级码；分析员凭一级码接收获得独立二级码，
 * 仅本人可用二级码录入一个整数结果。CLOSED 拒绝新增样本、接收与结果；
 * 接收与退组竞争时按提交顺序判定，退组先提交则拒绝接收；
 * 退组样本的结果保留但不进闭组汇总。一级码/二级码均为无含义随机码，不编码处理或席位。
 */
@Service
public class SampleService {

    /** 闭组汇总所需的最少有效结果份数。 */
    static final int MIN_SUMMARY_RESULTS = 3;

    private final SampleRepository sampleRepository;
    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final BlindCodeGenerator codeGenerator;
    private final Clock clock;

    public SampleService(SampleRepository sampleRepository,
                         ExperimentRepository experimentRepository,
                         AllocationRepository allocationRepository,
                         BlindCodeGenerator codeGenerator,
                         Clock clock) {
        this.sampleRepository = sampleRepository;
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.codeGenerator = codeGenerator;
        this.clock = clock;
    }

    /**
     * 协调员为未退组参与者创建唯一样本，返回一级码；实验须 OPEN。
     */
    @Transactional
    public SampleCreateView createSample(String experimentId, String participantId, String actorId) {
        ExperimentRow experiment = lockOpenExperiment(experimentId, "实验已关闭，拒绝新增样本");
        AllocationRow allocation =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        if ("WITHDRAWN".equals(allocation.status())) {
            throw ApiException.conflict("参与者已退组，不能创建样本");
        }
        if (sampleRepository.findByAllocationId(allocation.id()) != null) {
            throw ApiException.conflict("该参与者已存在样本，一个分配仅可创建一个样本");
        }
        long now = clock.nowMillis();
        // 一级码随机冲突概率极低，仍由唯一索引兜底并重试。
        for (int attempt = 0; attempt < 5; attempt++) {
            String primaryCode = codeGenerator.nextCode();
            SampleRow row = new SampleRow(0L, experiment.id(), allocation.id(), primaryCode,
                    null, null, "CREATED", null, 1, actorId, now, null, null);
            try {
                sampleRepository.insert(row);
                return new SampleCreateView(experiment.id(), participantId, primaryCode,
                        "CREATED", now);
            } catch (DuplicateKeyException e) {
                if (sampleRepository.isDuplicatePrimaryCode(e)) {
                    continue;
                }
                if (sampleRepository.isDuplicateAllocation(e)) {
                    // 并发创建同一样本：唯一约束兜底，按冲突处理，事务随幂等占位一并回滚。
                    throw ApiException.conflict("该参与者已存在样本，一个分配仅可创建一个样本");
                }
                throw e;
            }
        }
        throw ApiException.conflict("一级码生成冲突，请重试");
    }

    /**
     * 分析员凭一级码接收样本，获得独立二级码；一样本仅绑定一名分析员。
     * 接收与退组竞争：锁定分配行判定，退组先提交则拒绝接收。
     */
    @Transactional
    public SampleReceiveView receive(String primaryCode, String analystActor) {
        SampleRow sample = sampleRepository.lockByPrimaryCode(primaryCode);
        if (sample == null) {
            throw ApiException.notFound("样本不存在");
        }
        lockOpenExperiment(sample.experimentId(), "实验已关闭，拒绝接收样本");
        AllocationRow allocation = allocationRepository.lockById(sample.allocationId());
        if (allocation == null) {
            throw new IllegalStateException("样本对应分配缺失，数据不一致");
        }
        if ("WITHDRAWN".equals(allocation.status())) {
            throw ApiException.conflict("参与者已退组，拒绝接收样本");
        }
        if (!"CREATED".equals(sample.status())) {
            throw ApiException.conflict("样本已被接收，一样本仅绑定一名分析员");
        }
        long now = clock.nowMillis();
        // 二级码随机冲突概率极低，仍由唯一索引兜底并重试。
        for (int attempt = 0; attempt < 5; attempt++) {
            String secondaryCode = codeGenerator.nextCode();
            try {
                sampleRepository.markReceived(sample.id(), secondaryCode, analystActor, now);
                return new SampleReceiveView(secondaryCode, "RECEIVED", now);
            } catch (DuplicateKeyException e) {
                if (sampleRepository.isDuplicateSecondaryCode(e)) {
                    continue;
                }
                throw e;
            }
        }
        throw ApiException.conflict("二级码生成冲突，请重试");
    }

    /**
     * 接收分析员本人凭二级码录入一个整数结果；实验须 OPEN。
     * 退组样本的结果允许保留，但不进闭组汇总。
     */
    @Transactional
    public SampleResultView recordResult(String secondaryCode, int value, Integer expectedVersion,
                                         String analystActor) {
        SampleRow sample = sampleRepository.lockBySecondaryCode(secondaryCode);
        if (sample == null) {
            throw ApiException.notFound("样本不存在");
        }
        if (!analystActor.equals(sample.analystActor())) {
            throw ApiException.forbidden("仅接收该样本的分析员本人可录入结果");
        }
        lockOpenExperiment(sample.experimentId(), "实验已关闭，拒绝录入结果");
        if ("RESULT".equals(sample.status())) {
            throw ApiException.conflict("该样本已录入结果，一样本仅可录入一个结果");
        }
        if (!"RECEIVED".equals(sample.status())) {
            throw ApiException.conflict("样本尚未接收，不能录入结果");
        }
        if (expectedVersion != null && expectedVersion.intValue() != sample.version()) {
            throw ApiException.conflict("样本版本已变化，请刷新后重试");
        }
        long now = clock.nowMillis();
        sampleRepository.recordResult(sample.id(), value, now);
        return new SampleResultView(secondaryCode, "RESULT", value, now);
    }

    /**
     * 协调员样本视图：仅一级码、接收标识与有结果标识，不给二级码或结果值。
     */
    public SampleCoordinatorView coordinatorView(String experimentId, String participantId) {
        mustFindExperiment(experimentId);
        AllocationRow allocation =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        SampleRow sample = sampleRepository.findByAllocationId(allocation.id());
        if (sample == null) {
            throw ApiException.notFound("该参与者尚未创建样本");
        }
        return new SampleCoordinatorView(experimentId, participantId, sample.primaryCode(),
                !"CREATED".equals(sample.status()), sample.resultValue() != null);
    }

    /**
     * 分析员本人样本视图：仅二级码、状态与结果，不给参与者、一级码或处理。
     */
    public AnalystSampleView analystView(String secondaryCode, String analystActor) {
        SampleRow sample = sampleRepository.findBySecondaryCode(secondaryCode);
        if (sample == null) {
            throw ApiException.notFound("样本不存在");
        }
        if (!analystActor.equals(sample.analystActor())) {
            throw ApiException.forbidden("仅接收该样本的分析员本人可查询");
        }
        return new AnalystSampleView(sample.secondaryCode(), sample.status(),
                sample.resultValue(), sample.receivedAt(), sample.resultAt());
    }

    /**
     * REVIEWER 闭组汇总：仅 CLOSED 后可用，返回有效结果份数与总和（排除退组样本），
     * 无逐样本关联；有效结果不足 3 份返回 422。
     */
    public SampleSummaryView summary(String experimentId) {
        ExperimentRow experiment = mustFindExperiment(experimentId);
        if (!"CLOSED".equals(experiment.status())) {
            throw ApiException.conflict("实验未关闭，结果汇总不可用");
        }
        SummaryRow summary = sampleRepository.summarize(experimentId);
        if (summary.count() < MIN_SUMMARY_RESULTS) {
            throw ApiException.unprocessable("有效结果不足 " + MIN_SUMMARY_RESULTS + " 份，无法汇总");
        }
        return new SampleSummaryView(experimentId, "CLOSED", summary.count(), summary.sum());
    }

    private ExperimentRow lockOpenExperiment(String experimentId, String closedMessage) {
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        if ("CLOSED".equals(experiment.status())) {
            throw ApiException.conflict(closedMessage);
        }
        return experiment;
    }

    private ExperimentRow mustFindExperiment(String experimentId) {
        ExperimentRow row = experimentRepository.findById(experimentId);
        if (row == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        return row;
    }
}
