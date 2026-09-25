package com.example.starter.race.api;

import com.example.starter.race.service.RelayService;
import com.example.starter.race.service.ServiceResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接力赛交接区计时与犯规判定 HTTP 接口。
 */
@RestController
@RequestMapping("/api/races")
public class RelayController {

    private final RelayService relayService;

    public RelayController(RelayService relayService) {
        this.relayService = relayService;
    }

    /** 配置接力模式（棒次数、交接区上限、各队棒次选手）。 */
    @PostMapping("/{raceId}/relay-config")
    public ResponseEntity<Object> configureRelay(
            @PathVariable String raceId,
            @Valid @RequestBody RelayConfigRequest request) {
        return toResponse(relayService.configureRelay(raceId, request));
    }

    /** 提交一次交接（含交接区用时，超上限判犯规但仍推进计时）。 */
    @PostMapping("/{raceId}/relay-handoffs")
    public ResponseEntity<Object> submitHandoff(
            @PathVariable String raceId,
            @Valid @RequestBody RelayHandoffRequest request) {
        return toResponse(relayService.submitHandoff(raceId, request));
    }

    /** 查询接力即时排名（封榜后返回固化快照内容）。 */
    @GetMapping("/{raceId}/relay-standing")
    public RelayStandingResponse getRelayStanding(@PathVariable String raceId) {
        return relayService.getRelayStanding(raceId);
    }

    /** 查询接力封榜只读快照。 */
    @GetMapping("/{raceId}/relay-snapshot")
    public RelayStandingResponse getRelaySnapshot(@PathVariable String raceId) {
        return relayService.getRelaySnapshot(raceId);
    }

    /** 查询队伍逐棒明细。 */
    @GetMapping("/{raceId}/relay-teams/{teamKey}")
    public RelayTeamDetailResponse getTeamDetail(
            @PathVariable String raceId, @PathVariable String teamKey) {
        return relayService.getTeamDetail(raceId, teamKey);
    }

    /** 查询赛事犯规清单。 */
    @GetMapping("/{raceId}/relay-fouls")
    public RelayFoulListResponse getFouls(@PathVariable String raceId) {
        return relayService.getFouls(raceId);
    }

    private ResponseEntity<Object> toResponse(ServiceResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
