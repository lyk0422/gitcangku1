package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AdjudicateEvidenceRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.EvidenceRulingResponse;
import com.example.starter.race.api.FinishEvidenceResponse;
import com.example.starter.race.api.RegisterEvidenceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokeEvidenceRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;

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

    /**
     * 选手退赛：退赛后不参与排名，且成为“失效候选人”；
     * 引用该选手的 PENDING 证据再裁决时返回422。已退赛重复提交返回409。
     */
    ServiceResult withdrawRunner(String raceId, String bib, WithdrawRunnerRequest request);

    /**
     * 登记冲线证据：仅 OPEN 赛事可登记；相同计时的候选人建议顺序不得遗漏或重复，
     * 候选人须为该计时（原始完赛耗时相等）的有效选手；evidenceId 全局唯一。
     */
    ServiceResult registerEvidence(String raceId, RegisterEvidenceRequest request);

    /**
     * 批量裁决一组同计时证据：先校验赛事未封榜、证据均可裁决、全部候选人仍有效、
     * 裁决顺序恰好覆盖全部候选人且无重复；任一失效返回422且无部分变更。
     * 成功后重排该计时组并写入不可变裁决快照。
     */
    ServiceResult adjudicateEvidence(String raceId, AdjudicateEvidenceRequest request);

    /** 撤回未裁决证据：保留撤回记录；已裁决证据不可撤回（409）。 */
    ServiceResult revokeEvidence(
            String raceId, String evidenceId, RevokeEvidenceRequest request);

    /** 查询赛事冲线证据列表（含各状态），按计时组、登记时间稳定返回；只读。 */
    List<FinishEvidenceResponse> getEvidences(String raceId);

    /** 查询单条冲线证据（含裁决批次与撤回状态）；不存在为404。 */
    FinishEvidenceResponse getEvidence(String raceId, String evidenceId);

    /** 查询赛事全部证据裁决快照（不可变），按裁决时间稳定返回；只读。 */
    List<EvidenceRulingResponse> getRulings(String raceId);

    /** 按裁决批次ID查询不可变裁决快照；不存在为404。 */
    EvidenceRulingResponse getRuling(String raceId, String rulingId);

    /** 查询即时成绩（OPEN 实时计算；SEALED 返回封榜快照）。 */
    StandingResponse getResults(String raceId);

    /** 查询封榜只读快照；未封榜为404。 */
    StandingResponse getSnapshot(String raceId);

    /** 查询单个选手的分段明细，按检查点顺序稳定返回；只读，不修改版本。 */
    RunnerTimingResponse getRunnerTimings(String raceId, String bib);

    /** 查询赛事全部选手缺失检查点汇总，按参赛号与检查点顺序稳定返回；只读。 */
    MissingCheckpointsResponse getMissingCheckpoints(String raceId);
}
