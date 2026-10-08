package com.example.starter.race.service;

import com.example.starter.race.api.ConfigureStagesRequest;
import com.example.starter.race.api.ReviseStageTimingRequest;
import com.example.starter.race.api.StageResultsResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitStageTimingRequest;
import com.example.starter.race.persistence.RaceRow;

/**
 * 阶段（STAGES）赛事应用服务：多日赛事每一阶段都须在关门比例 11F/10 内才有总成绩。
 * 阶段计时更正、处罚撤销与封榜共用赛事版本；全部 F 与资格随当前数据重算，不沿用旧最快值。
 */
public interface StageService {

    /**
     * 一次性固定 2~5 个阶段及其有序检查点；仅 OPEN、尚无单日检查点/任何计时数据、
     * 且尚未配置阶段的赛事可配置，成功后版本加一且不可修改。
     */
    ServiceResult configureStages(String raceId, ConfigureStagesRequest request);

    /**
     * 提交选手阶段检查点通过记录（阶段从零计时，累计毫秒）；
     * 乱序允许但按检查点顺序耗时须严格递增，违反返回422且不写入。
     */
    ServiceResult submitStageTiming(
            String raceId, int stageNo, String bib, SubmitStageTimingRequest request);

    /** 更正阶段分段计时；更正后重算全部阶段F与资格。 */
    ServiceResult reviseStageTiming(
            String raceId, int stageNo, String timingId, ReviseStageTimingRequest request);

    /** 查询各阶段成绩（OPEN 实时计算；SEALED 返回封榜固化的阶段快照）。 */
    StageResultsResponse getStageResults(String raceId);

    /**
     * 阶段赛事封榜：在 {@code RaceServiceImpl.sealRace} 的同一事务与幂等包装内调用；
     * 任一阶段无完整选手返回422，否则条件转 SEALED 并固化每阶段原始点、F、资格及总成绩。
     */
    ServiceResult sealStages(RaceRow race, int expectedVersion);

    /** OPEN 阶段赛事的实时总榜（由 {@code RaceServiceImpl.getResults} 分派）。 */
    StandingResponse liveOverallStanding(RaceRow race);
}
