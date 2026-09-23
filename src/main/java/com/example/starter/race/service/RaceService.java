package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AppealOpinionRequest;
import com.example.starter.race.api.AppealResponse;
import com.example.starter.race.api.AppealsResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitAppealRequest;
import com.example.starter.race.api.SubmitTimingRequest;

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
     * 提交处罚申诉：当事人针对自己一条已生效且未被申诉的处罚，在 finishAt 后30分钟内提交。
     * 受理冻结处罚、原始/净成绩、分段判定与榜单版本，状态 PENDING；榜单版本不变、
     * 公开榜单仍按原处罚计算但标记该选手“申诉中”。
     */
    ServiceResult submitAppeal(
            String raceId, String penaltyId, SubmitAppealRequest request);

    /**
     * 赛事干事提交裁决意见：第一人提交 UPHOLD/REMOVE/REPLACE 建议；
     * 第二人只能确认完全相同的建议或驳回。确认时在一个事务内重读版本、变更处罚、
     * 重算完整排名并只生成一个新 leaderboardVersion。
     */
    ServiceResult submitAppealOpinion(
            String raceId, String appealKey, AppealOpinionRequest request);

    /** 查询单个申诉证据（受理冻结、两人意见、重算前后榜单快照），只读。 */
    AppealResponse getAppeal(String raceId, String appealKey);

    /** 查询赛事全部申诉证据，按受理时间与申诉键稳定排序；只读。 */
    AppealsResponse getAppeals(String raceId);
}
