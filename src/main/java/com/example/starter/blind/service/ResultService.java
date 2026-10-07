package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.ResultView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ResultRepository;
import com.example.starter.blind.repo.ResultRepository.ResultRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结果提交/修订业务：
 * COORDINATOR 为已登记参与者提交整数结果（0~100），首次提交版本为 1；
 * 仅实验 OPEN 且参与者未退组时可修订，每次修订版本加一，历史版本全部保留。
 * 通过实验行级锁与关闭、退组、分析串行，决定分析可见的最终集合。
 */
@Service
public class ResultService {

    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final ResultRepository resultRepository;
    private final Clock clock;

    public ResultService(ExperimentRepository experimentRepository,
                         AllocationRepository allocationRepository,
                         ResultRepository resultRepository,
                         Clock clock) {
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.resultRepository = resultRepository;
        this.clock = clock;
    }

    /**
     * 提交或修订结果；返回视图中的 version 为 1 表示首次提交，否则为修订。
     */
    @Transactional
    public ResultView submit(String experimentId, String participantId, int value, String actorId) {
        // 行级锁串行化：与关闭、退组、分析互斥，保证最终集合确定。
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        if (!"OPEN".equals(experiment.status())) {
            throw ApiException.conflict("实验已关闭，拒绝提交或修订结果");
        }
        AllocationRow allocation =
                allocationRepository.findByExperimentAndParticipant(experimentId, participantId);
        if (allocation == null) {
            throw ApiException.notFound("参与者尚未在该实验登记");
        }
        if ("WITHDRAWN".equals(allocation.status())) {
            throw ApiException.conflict("参与者已退组，拒绝提交或修订结果");
        }
        ResultRow current = resultRepository.findCurrent(experimentId, participantId);
        int version = current == null ? 1 : current.version() + 1;
        long now = clock.nowMillis();
        resultRepository.insert(new ResultRow(experimentId, participantId, version, value,
                actorId, now));
        return new ResultView(experimentId, participantId, value, version, now);
    }
}
