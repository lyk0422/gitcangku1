package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.ConfigureInspectionRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.EquipmentBindingsResponse;
import com.example.starter.race.api.InspectionHistoryResponse;
import com.example.starter.race.api.InspectionStatusResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RunnerRaceStateResponse;
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
     * 配置赛事器材检录（是否强制及有效分钟数 1~1440）；仅 OPEN 赛事可重复配置，
     * 历史检录 PASS 按提交时快照的 valid_minutes 计算有效期，不溯及既往。
     */
    ServiceResult configureInspection(String raceId, ConfigureInspectionRequest request);

    /**
     * 提交器材检录/复检：追加不可变历史记录；PASS 建立器材绑定，
     * 同器材在赛事内已绑定另一名未完赛选手时返回409；FAIL 立即阻断起跑但可被复检PASS替换。
     */
    ServiceResult submitInspection(String raceId, String bib, SubmitInspectionRequest request);

    /** 显式起跑：强制检录赛事按可注入时钟校验未过期PASS，不满足422且不写入。 */
    ServiceResult startRunner(String raceId, String bib, StartRunnerRequest request);

    /** 退赛：终态，成功后释放该选手活跃器材绑定。 */
    ServiceResult withdrawRunner(String raceId, String bib, WithdrawRunnerRequest request);

    /** 查询选手全部检录历史（PASS/FAIL均含），按时间升序；只读。 */
    InspectionHistoryResponse getInspectionHistory(String raceId, String bib);

    /** 查询选手当前有效状态（PASS_VALID/FAIL/EXPIRED/NONE/NOT_REQUIRED）与最近记录；只读。 */
    InspectionStatusResponse getInspectionStatus(String raceId, String bib);

    /** 查询赛事当前活跃器材绑定清单（退赛/取消资格/完赛后释放）；只读。 */
    EquipmentBindingsResponse getEquipmentBindings(String raceId);

    /** 查询选手起跑/退赛状态；只读。 */
    RunnerRaceStateResponse getRunnerState(String raceId, String bib);
}
