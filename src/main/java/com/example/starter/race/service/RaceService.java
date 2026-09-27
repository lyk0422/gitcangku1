package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.MedicalDiagnosticsResponse;
import com.example.starter.race.api.MedicalHoldHistoryResponse;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RecordFinishTimingRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResumeMedicalHoldRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
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
     * 提交时选手处于生效医疗暂停区间内，则记录正常保存但标记 MEDICAL_HOLD，不参与排名。
     */
    ServiceResult submitTiming(String raceId, String bib, SubmitTimingRequest request);

    /** 登记不可变终点（完赛）计时事件，用于医疗暂停恢复时的区间终点计时裁定。 */
    ServiceResult recordFinishTiming(String raceId, String bib, RecordFinishTimingRequest request);

    /**
     * 登记选手退赛（不可变）。已退赛/已取消资格/已封榜不可退赛；
     * 退赛后不参与排名，且不可再开始或恢复医疗暂停。
     */
    ServiceResult withdrawRunner(String raceId, String bib, WithdrawRunnerRequest request);

    /**
     * 开始医疗暂停（UTC，区间左闭右开）。同一选手同时仅一条生效暂停，重复开始409；
     * 已退赛/已取消资格/已封榜选手不可开始暂停。
     */
    ServiceResult startMedicalHold(String raceId, String bib, StartMedicalHoldRequest request);

    /**
     * 由与登记角色不同的医疗角色确认适赛并恢复；endAt 必须严格晚于 startAt。
     * 若暂停区间内存在终点计时，返回422，要求先按既有规则裁定完赛状态。
     * 恢复后仅暂停结束后的新计时参与排名，区间内被排除计时与暂停前原始计时均保留。
     */
    ServiceResult resumeMedicalHold(String raceId, String holdId, ResumeMedicalHoldRequest request);

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

    /** 查询选手全部医疗暂停/恢复不可变历史（按开始时刻升序）及当前资格；只读。 */
    MedicalHoldHistoryResponse getMedicalHoldHistory(String raceId, String bib);

    /** 查询选手医疗暂停诊断：资格状态、生效暂停与被排除计时明细；只读，不改变状态。 */
    MedicalDiagnosticsResponse getMedicalDiagnostics(String raceId, String bib);
}
