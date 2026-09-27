package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ApproveReclassRequest;
import com.example.starter.race.api.CategoryResponse;
import com.example.starter.race.api.CategoryStandingResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateCategoryRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.ReclassApplyRequest;
import com.example.starter.race.api.ReclassRecordResponse;
import com.example.starter.race.api.ReclassRequestResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RejectReclassRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealCategoryRequest;
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

    /** 查询即时成绩（OPEN 实时计算；SEALED 返回封榜快照）。 */
    StandingResponse getResults(String raceId);

    /** 查询封榜只读快照；未封榜为404。 */
    StandingResponse getSnapshot(String raceId);

    /** 查询单个选手的分段明细，按检查点顺序稳定返回；只读，不修改版本。 */
    RunnerTimingResponse getRunnerTimings(String raceId, String bib);

    /** 查询赛事全部选手缺失检查点汇总，按参赛号与检查点顺序稳定返回；只读。 */
    MissingCheckpointsResponse getMissingCheckpoints(String raceId);

    /** 新建组别（容量>=1）；仅 OPEN 赛事可建，成功后版本加一。 */
    ServiceResult createCategory(String raceId, CreateCategoryRequest request);

    /** 选手退赛；已退赛重复退赛返回409，退赛者不占组别容量、不参与组别排名。 */
    ServiceResult withdrawRunner(String raceId, String bib, WithdrawRunnerRequest request);

    /**
     * 提交组别重分申请：目标组别必须存在且未封榜，申请者须在赛且无待审批申请；
     * 申请固化提交时刻所属组别与赛事版本。
     */
    ServiceResult submitReclass(String raceId, ReclassApplyRequest request);

    /**
     * 批量批准重分申请：先整批校验（每参赛者仅一次、目标组别容量未超、
     * 不涉及已封榜目标组别），任一不合法整批422；参赛者已退赛或来源组别已封榜返回409。
     * 成功后在同一事务迁移全部参赛者组别、写入不可变重分记录（含迁移前名次与计时快照），
     * 并重算来源与目标组别排名一并返回。
     */
    ServiceResult approveReclass(String raceId, ApproveReclassRequest request);

    /** 拒绝重分申请：仅置申请状态，不改变任何成绩。 */
    ServiceResult rejectReclass(String raceId, String applicationId, RejectReclassRequest request);

    /** 组别封榜：仅固化封榜时刻所属该组别的在赛选手成绩快照，历史快照不追溯重写。 */
    ServiceResult sealCategory(String raceId, String categoryCode, SealCategoryRequest request);

    /** 查询赛事全部组别（含容量与在赛人数）；只读。 */
    List<CategoryResponse> getCategories(String raceId);

    /** 查询单个组别当前排名（OPEN 实时计算；SEALED 返回封榜快照）；只读。 */
    CategoryStandingResponse getCategoryResults(String raceId, String categoryCode);

    /** 查询组别封榜只读快照；未封榜为404。 */
    CategoryStandingResponse getCategorySnapshot(String raceId, String categoryCode);

    /** 查询赛事全部重分申请，按提交时间稳定排列；只读。 */
    List<ReclassRequestResponse> getReclassRequests(String raceId);

    /** 查询赛事全部不可变重分记录，按写入顺序排列；只读。 */
    List<ReclassRecordResponse> getReclassRecords(String raceId);
}
