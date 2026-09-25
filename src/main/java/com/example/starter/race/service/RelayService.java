package com.example.starter.race.service;

import com.example.starter.race.api.ConfigureRelayRequest;
import com.example.starter.race.api.FoulResponse;
import com.example.starter.race.api.HandoffResponse;
import com.example.starter.race.api.RegisterRelayTeamRequest;
import com.example.starter.race.api.RelayConfigResponse;
import com.example.starter.race.api.RelayStandingResponse;
import com.example.starter.race.api.RelayTeamDetailResponse;
import com.example.starter.race.api.RelayTeamResponse;

import java.util.List;

/**
 * 接力赛应用服务；配置、登记队伍、交接提交均在单个数据库事务内完成
 * “业务校验变更 + 版本推进 + 幂等记录”原子提交，封榜复用 {@link RaceService#sealRace}。
 */
public interface RelayService {

    /** 将 OPEN 赛事配置为接力赛；成功后版本加一，配置不可再修改。 */
    ServiceResult configureRelay(String raceId, ConfigureRelayRequest request);

    /** 登记一支接力队伍（固定棒次选手）。 */
    ServiceResult registerTeam(String raceId, RegisterRelayTeamRequest request);

    /** 提交一次交接；超时判犯规但仍推进，末棒完成自动生成完赛记录。 */
    ServiceResult submitHandoff(String raceId, com.example.starter.race.api.SubmitHandoffRequest request);

    /** 查询某支队伍逐棒明细与该队犯规清单。 */
    RelayTeamDetailResponse getTeamDetail(String raceId, String teamKey);

    /** 查询赛事下全部队伍的犯规清单（按队伍、棒次升序）。 */
    List<FoulResponse> getFouls(String raceId);

    /** 查询接力即时排名（SEALED 返回封榜只读排名）。 */
    RelayStandingResponse getStanding(String raceId);

    /** 配置响应组装（供封榜等内部场景复用）。 */
    RelayConfigResponse toConfigResponse(String raceId);
}
