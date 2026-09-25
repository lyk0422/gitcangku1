package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AdjudicateFinishEvidenceRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.FinishAdjudicationResponse;
import com.example.starter.race.api.FinishEvidenceResponse;
import com.example.starter.race.api.RegisterFinishEvidenceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawFinishEvidenceRequest;

import java.util.List;

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
     * 登记冲线证据：仅 OPEN 赛事；建议顺序必须是该计时组全部候选人的全排列；
     * finishKey 指纹相同重放原结果，失败不占键；赛事已封榜返回409。
     */
    ServiceResult registerFinishEvidence(String raceId, RegisterFinishEvidenceRequest request);

    /** 撤回未裁决证据并保留撤回记录；已裁决证据不可撤回返回409。 */
    ServiceResult withdrawFinishEvidence(
            String raceId, String evidenceId, WithdrawFinishEvidenceRequest request);

    /**
     * 批量裁决一组证据：先校验赛事未封榜、全部候选人仍有效、裁决后名次无重复；
     * 任一失效422且不留半成品；成功后重排该计时组并写入不可变裁决快照。
     */
    ServiceResult adjudicateFinishEvidence(String raceId, AdjudicateFinishEvidenceRequest request);

    /** 查询赛事全部冲线证据（含已裁决、已撤回），按登记顺序稳定返回；只读。 */
    List<FinishEvidenceResponse> listFinishEvidence(String raceId);

    /** 查询赛事全部证据裁决快照（不可变），按裁决生效顺序稳定返回；只读。 */
    List<FinishAdjudicationResponse> listFinishAdjudications(String raceId);
}
