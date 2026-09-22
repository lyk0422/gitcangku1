package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RecordSplitRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RunnerSplitsResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;

/**
 * 赛事成绩封榜应用服务；每个写方法在单个数据库事务内完成
 * “业务校验变更 + 版本推进 + 幂等记录”原子提交。
 */
public interface RaceService {

    /** 新建赛事（初始版本1、OPEN）。 */
    ServiceResult createRace(CreateRaceRequest request);

    /** 登记选手；版本不匹配或赛事已封榜返回409。 */
    ServiceResult registerRunner(String raceId, RegisterRunnerRequest request);

    /** 修订原始完赛耗时。 */
    ServiceResult reviseTime(String raceId, ReviseTimeRequest request);

    /** 新增处罚（加时或取消资格）。 */
    ServiceResult addPenalty(String raceId, AddPenaltyRequest request);

    /** 撤销处罚。 */
    ServiceResult revokePenalty(String raceId, String penaltyId, RevokePenaltyRequest request);

    /** 封榜：校验版本并原子保存只读成绩快照。 */
    ServiceResult sealRace(String raceId, SealRaceRequest request);

    /**
     * 一次性配置1~20个有序检查点（checkpointCode 赛事内唯一，顺序从1连续递增）；
     * 仅 OPEN 且尚无任何分段记录时可用，成功版本加一，配置后不可修改。
     */
    ServiceResult configureCheckpoints(String raceId, ConfigureCheckpointsRequest request);

    /**
     * 提交选手检查点通过记录；允许乱序到达，但按检查点顺序耗时必须严格递增，
     * 违反相邻约束或不小于原始完赛耗时返回422且不写入；timingId 同参重放原结果、异参409。
     */
    ServiceResult recordSplit(String raceId, String bib, RecordSplitRequest request);

    /** 查询即时成绩（OPEN 实时计算；SEALED 返回封榜快照）。 */
    StandingResponse getResults(String raceId);

    /** 查询封榜只读快照；未封榜为404。 */
    StandingResponse getSnapshot(String raceId);

    /** 查询单个选手的分段明细，按检查点顺序排列（缺失检查点 elapsedMillis 为 null）；只读不改版本。 */
    RunnerSplitsResponse getRunnerSplits(String raceId, String bib);

    /** 赛事缺失检查点汇总：已完赛但未覆盖全部检查点的选手及其漏点，顺序稳定；只读不改版本。 */
    MissingCheckpointsResponse getMissingCheckpoints(String raceId);
}
