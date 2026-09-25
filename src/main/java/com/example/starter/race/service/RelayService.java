package com.example.starter.race.service;

import com.example.starter.race.api.RelayConfigRequest;
import com.example.starter.race.api.RelayConfigResponse;
import com.example.starter.race.api.RelayFoulListResponse;
import com.example.starter.race.api.RelayHandoffRequest;
import com.example.starter.race.api.RelayHandoffResponse;
import com.example.starter.race.api.RelayStandingResponse;
import com.example.starter.race.api.RelayTeamDetailResponse;

/**
 * 接力赛应用服务；每个写方法在单个数据库事务内完成
 * “业务校验变更 + 版本推进 + 幂等记录”原子提交。
 */
public interface RelayService {

    /** 配置接力模式：OPEN 赛事可配置一次，成功后版本加一，配置不可修改。 */
    ServiceResult configureRelay(String raceId, RelayConfigRequest request);

    /** 提交一次交接：校验时序、判犯规、推进计时，末棒自动生成队伍完赛记录。 */
    ServiceResult submitHandoff(String raceId, RelayHandoffRequest request);

    /** 查询接力即时排名（封榜后返回固化快照）。 */
    RelayStandingResponse getRelayStanding(String raceId);

    /** 查询接力封榜只读快照；未封榜为404。 */
    RelayStandingResponse getRelaySnapshot(String raceId);

    /** 查询一支队伍的逐棒明细。 */
    RelayTeamDetailResponse getTeamDetail(String raceId, String teamKey);

    /** 查询赛事全部犯规记录清单。 */
    RelayFoulListResponse getFouls(String raceId);
}
