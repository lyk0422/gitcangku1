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
 * 参与者结果业务：
 * 首次提交版本为 1，修订追加新版本；仅实验 OPEN 且参与者未退组时可提交或修订。
 * 实验行级锁使结果提交与关闭、退组串行，决定分析所见的最终集合。
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
     * 提交或修订结果；返回写入的版本视图。结果值本身不是盲底，可返回给提交方。
     */
    @Transactional
    public ResultView submit(String experimentId, String participantId, int value, String actorId) {
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
        ResultRow latest = resultRepository.findLatest(experimentId, participantId);
        int version = latest == null ? 1 : latest.version() + 1;
        long now = clock.nowMillis();
        resultRepository.insert(
                new ResultRow(experimentId, participantId, version, value, actorId, now));
        return new ResultView(experimentId, participantId, version, value, now);
    }
}
