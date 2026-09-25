package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ClaimRecordRequest;
import com.example.starter.race.api.CourseRecordResponse;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RecordHistoryResponse;
import com.example.starter.race.api.RegisterCourseRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;

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

    /** 封榜：校验版本并原子保存只读成绩快照。 */
    ServiceResult sealRace(String raceId, SealRaceRequest request);

    /** 查询即时成绩（OPEN 实时计算；SEALED 返回封榜快照）。 */
    StandingResponse getResults(String raceId);

    /** 查询封榜只读快照；未封榜为404。 */
    StandingResponse getSnapshot(String raceId);

    /** 登记赛道（初始无纪录）；赛道标识重复返回409。 */
    ServiceResult registerCourse(RegisterCourseRequest request);

    /**
     * 赛道纪录认定：单事务内重新校验赛事已封榜、选手在封榜快照中已完赛且未取消资格、
     * 快照最终计时严格优于当前纪录；通过后原子切换纪录指针并把旧纪录保留在不可变历史链中。
     * 计时不优于当前纪录时抛出 {@link RecordNotBetterException}（422，携带实际当前纪录）；
     * 同一 recordClaimKey 重复申请幂等返回首次结果。
     */
    ServiceResult claimRecord(String courseKey, ClaimRecordRequest request);

    /** 查询赛道当前纪录（只读，不触发认定）；赛道或纪录不存在为404。 */
    CourseRecordResponse getCurrentRecord(String courseKey);

    /** 查询赛道完整历史纪录链（只读，按认定先后升序）；赛道不存在为404。 */
    RecordHistoryResponse getRecordHistory(String courseKey);
}
