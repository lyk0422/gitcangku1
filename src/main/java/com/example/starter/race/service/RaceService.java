package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.MedicalHoldHistoryResponse;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResumeMedicalHoldRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RunnerMedicalDiagnosisResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StartMedicalHoldRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;

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
     * 提交时选手处于生效医疗暂停区间的，记录保存但标记 MEDICAL_HOLD，不参与排名。
     */
    ServiceResult submitTiming(String raceId, String bib, SubmitTimingRequest request);

    /**
     * 选手退赛：退赛后成绩状态 WITHDRAWN，不参与排名；
     * 已退赛/已取消资格/已封榜选手重复或非法退赛返回409；证据不删除。
     */
    ServiceResult withdrawRunner(String raceId, String bib, WithdrawRunnerRequest request);

    /**
     * 登记医疗暂停开始（UTC，左闭端点）：
     * 同一选手同时仅一条生效暂停，重复开始409；
     * 已退赛/已取消资格/已封榜选手不可开始；holdId 重复409。
     */
    ServiceResult startMedicalHold(String raceId, String bib, StartMedicalHoldRequest request);

    /**
     * 由不同医疗角色确认适赛并恢复暂停（endAt 晚于 startAt，右开端点）：
     * 已退赛/已取消资格/已封榜/暂停不存在或已恢复均409；同角色确认422；endAt 不晚于开始422；
     * 暂停期间存在终点计时事件时422，要求先按既有规则裁定完赛状态。
     * 恢复后仅暂停结束后的新计时参与排名，区间内计时保持 MEDICAL_HOLD。
     */
    ServiceResult resumeMedicalHold(
            String raceId, String holdId, ResumeMedicalHoldRequest request);

    /** 封榜：校验版本并原子保存只读成绩快照（含每名选手分段明细、资格状态与排除原因）。 */
    ServiceResult sealRace(String raceId, SealRaceRequest request);

    /** 查询即时成绩（OPEN 实时计算；SEALED 返回封榜快照）。 */
    StandingResponse getResults(String raceId);

    /** 查询封榜只读快照；未封榜为404。 */
    StandingResponse getSnapshot(String raceId);

    /** 查询单个选手的分段明细，按检查点顺序稳定返回；只读，不修改版本。 */
    RunnerTimingResponse getRunnerTimings(String raceId, String bib);

    /** 查询赛事全部选手缺失检查点汇总，按参赛号与检查点顺序稳定返回；只读。 */
    MissingCheckpointsResponse getMissingCheckpoints(String raceId);

    /** 查询赛事下全部医疗暂停历史（含生效中与已恢复），只读。 */
    MedicalHoldHistoryResponse getMedicalHoldHistory(String raceId);

    /** 查询单个选手医疗状态诊断（资格状态、生效暂停、被排除计时、暂停期间终点计时），只读。 */
    RunnerMedicalDiagnosisResponse getRunnerMedicalDiagnosis(String raceId, String bib);
}
