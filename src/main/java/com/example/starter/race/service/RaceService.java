package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RegisterWithdrawalRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RevokeWithdrawalRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RunnerStatusResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawalListResponse;

/**
 * 赛事成绩封榜应用服务；每个写方法在单个数据库事务内完成
 * “业务校验变更 + 版本推进 + 幂等记录”原子提交。
 */
public interface RaceService {

    /** 新建赛事（初始版本1、OPEN）。 */
    ServiceResult createRace(CreateRaceRequest request);

    /** 登记选手；版本不匹配或赛事已封榜返回409。 */
    ServiceResult registerRunner(String raceId, RegisterRunnerRequest request);

    /** 修订原始完赛耗时；finishTimeMs 为 null 表示清除完赛计时回到 UNTIMED，既有分段记录保留。 */
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

    /**
     * 登记退赛（DNS 未出发 / DNF 中途退赛）。
     * DNS 要求该选手尚无分段记录与完赛计时；DNF 要求已有分段、尚无完赛计时，
     * 且 lastPassedCheckpoint 须为已有记录中顺序最大者（否则422）；
     * 已取消资格或已有完赛计时返回409；同状态重复登记按幂等返回首次结果，
     * DNS 与 DNF 相互改写一律409；成功后赛事版本加一。
     */
    ServiceResult registerWithdrawal(String raceId, RegisterWithdrawalRequest request);

    /**
     * 撤销退赛：携带同一 withdrawalKey 与当前赛事版本；撤销后选手恢复按计时计算
     * （UNTIMED 或 MISSING_CHECKPOINT）；已撤销的退赛再次撤销返回409；封榜后禁止撤销。
     */
    ServiceResult revokeWithdrawal(
            String raceId, String withdrawalKey, RevokeWithdrawalRequest request);

    /** 查询赛事退赛清单（含已撤销），按登记时间与登记键稳定返回；只读。 */
    WithdrawalListResponse getWithdrawals(String raceId);

    /** 查询单个选手的当前参赛状态与最近一次退赛登记；只读。 */
    RunnerStatusResponse getRunnerStatus(String raceId, String bib);

    /** 查询即时成绩（OPEN 实时计算；SEALED 返回封榜快照）。 */
    StandingResponse getResults(String raceId);

    /** 查询封榜只读快照；未封榜为404。 */
    StandingResponse getSnapshot(String raceId);

    /** 查询单个选手的分段明细，按检查点顺序稳定返回；只读，不修改版本。 */
    RunnerTimingResponse getRunnerTimings(String raceId, String bib);

    /** 查询赛事全部选手缺失检查点汇总，按参赛号与检查点顺序稳定返回；只读。 */
    MissingCheckpointsResponse getMissingCheckpoints(String raceId);
}
