package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AppealListResponse;
import com.example.starter.race.api.AppealResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.ConfirmAppealRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RecommendAppealRequest;
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
     * 提交处罚申诉：仅本人已生效且未申诉的处罚、finishAt（完赛耗时落库时间）后30分钟内可受理；
     * 受理时冻结处罚、原始/净成绩、分段判定与榜单版本，状态 PENDING，不推进赛事版本。
     */
    ServiceResult submitAppeal(String raceId, SubmitAppealRequest request);

    /** 第一名赛事干事提交裁决建议（UPHOLD/REMOVE/REPLACE）；不推进赛事版本。 */
    ServiceResult recommendAppeal(String raceId, String appealKey, RecommendAppealRequest request);

    /**
     * 第二名赛事干事确认与第一人完全相同的建议或驳回；
     * 确认时在同一事务内重读版本、应用裁决并重算完整榜单，只生成一个新榜单版本。
     */
    ServiceResult confirmAppeal(String raceId, String appealKey, ConfirmAppealRequest request);

    /** 查询赛事全部申诉证据，按受理时间与申诉键稳定排序；只读。 */
    AppealListResponse listAppeals(String raceId);

    /** 查询单个申诉证据；不存在为404；只读。 */
    AppealResponse getAppeal(String raceId, String appealKey);
}
