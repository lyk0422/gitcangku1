package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AdjudicatePursuitRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.ConfigurePursuitRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.PursuitSnapshotResponse;
import com.example.starter.race.api.PursuitStateResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.SubmitTrajectoryRequest;

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

    /**
     * 一次性配置检查点（1~20个、代码赛事内唯一、顺序从1连续递增）；
     * 仅 OPEN 且尚无任何分段记录时可配置，成功后版本加一且不可修改。
     */
    ServiceResult configureCheckpoints(String raceId, ConfigureCheckpointsRequest request);

    /**
     * 提交选手检查点通过记录；乱序允许，但相邻检查点耗时须严格递增，
     * 且 elapsedMillis 必须小于该选手原始完赛耗时；违反返回422且不写入。
     */
    ServiceResult submitTiming(String raceId, String bib, SubmitTimingRequest request);

    /** 封榜：校验版本并原子保存只读成绩快照（含每名选手分段明细与缺失检查点）。 */
    ServiceResult sealRace(String raceId, SealRaceRequest request);

    /** 查询即时成绩（OPEN 实时计算；SEALED 返回封榜快照）。 */
    StandingResponse getResults(String raceId);

    /** 查询封榜只读快照；未封榜为404。 */
    StandingResponse getSnapshot(String raceId);

    /** 查询单个选手的分段明细，按检查点顺序稳定返回；只读，不修改版本。 */
    RunnerTimingResponse getRunnerTimings(String raceId, String bib);

    /** 查询赛事全部选手缺失检查点汇总，按参赛号与检查点顺序稳定返回；只读。 */
    MissingCheckpointsResponse getMissingCheckpoints(String raceId);

    /**
     * 计时前启用追逐赛：一次性固定 1~5 个追逐对及每人非负整数起始距离（毫米）；
     * 已有任何完赛耗时或分段记录返回409，启用后禁止直接录入完赛耗时。
     */
    ServiceResult configurePursuit(String raceId, ConfigurePursuitRequest request);

    /**
     * 提交/更正选手轨迹采样（整体替换，1~10点，时刻严格递增、距离不减）；
     * 校验赛事版本与轨迹版本，成功后轨迹版本加一并使涉及该选手的旧裁定失效。
     */
    ServiceResult submitTrajectory(String raceId, String bib, SubmitTrajectoryRequest request);

    /**
     * 裁定追逐对：显式指定双方当前轨迹版本，在公共区间内精确求最早交会时刻；
     * 未交会返回422且不写入，版本不匹配返回409。
     */
    ServiceResult adjudicatePursuit(String raceId, AdjudicatePursuitRequest request);

    /** 查询追逐赛当前状态（配置、轨迹版本与当前裁定）；只读。 */
    PursuitStateResponse getPursuitState(String raceId);

    /** 查询追逐赛封榜冻结快照（完整轨迹点与交会分数）；未封榜或非追逐赛为404。 */
    PursuitSnapshotResponse getPursuitSnapshot(String raceId);
}
