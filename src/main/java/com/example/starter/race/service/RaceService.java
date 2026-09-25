package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.EquipmentBindingsResponse;
import com.example.starter.race.api.InspectionHistoryResponse;
import com.example.starter.race.api.InspectionStatusResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StartRunnerRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitInspectionRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;

/**
 * 赛事成绩封榜应用服务；每个写方法在单个数据库事务内完成
 * “业务校验变更 + 版本推进 + 幂等记录”原子提交。
 */
public interface RaceService {

    /** 新建赛事（初始版本1、OPEN）；可同时配置是否强制检录及 PASS 有效分钟数（1~1440）。 */
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

    /**
     * 提交器材检录/复检：追加不可变历史；同选手同赛事只保留最近一条为“当前”，
     * PASS 建立/替换器材绑定，FAIL 立即阻断起跑；同器材赛事内同时绑定两个未完赛选手时409。
     */
    ServiceResult submitInspection(String raceId, String bib, SubmitInspectionRequest request);

    /**
     * 选手起跑：强制检录赛事按可注入时钟校验存在未过期 PASS（否则422且不写计时/起跑），
     * 非强制赛事不受门禁影响；同选手同赛事最多一次成功起跑。
     */
    ServiceResult startRunner(String raceId, String bib, StartRunnerRequest request);

    /** 选手退赛（终态）；退赛后释放器材绑定，且不能再起跑/检录。 */
    ServiceResult withdrawRunner(String raceId, String bib, WithdrawRunnerRequest request);

    /** 查询选手检录历史（不可变，按 inspectedAt 升序）。 */
    InspectionHistoryResponse getInspectionHistory(String raceId, String bib);

    /** 查询选手当前有效检录状态（按可注入时钟实时判定 PASS 是否未过期）。 */
    InspectionStatusResponse getInspectionStatus(String raceId, String bib);

    /** 查询赛事器材当前绑定清单（按器材序列号字典序）。 */
    EquipmentBindingsResponse getEquipmentBindings(String raceId);


    /** 查询即时成绩（OPEN 实时计算；SEALED 返回封榜快照）。 */
    StandingResponse getResults(String raceId);

    /** 查询封榜只读快照；未封榜为404。 */
    StandingResponse getSnapshot(String raceId);

    /** 查询单个选手的分段明细，按检查点顺序稳定返回；只读，不修改版本。 */
    RunnerTimingResponse getRunnerTimings(String raceId, String bib);

    /** 查询赛事全部选手缺失检查点汇总，按参赛号与检查点顺序稳定返回；只读。 */
    MissingCheckpointsResponse getMissingCheckpoints(String raceId);
}
