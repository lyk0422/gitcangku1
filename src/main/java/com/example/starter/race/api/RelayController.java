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

import java.util.List;

/**
 * 接力赛配置、队伍登记、交接提交与逐棒/犯规/排名查询 HTTP 接口。
 */
@RestController
@RequestMapping("/api/races/{raceId}/relay")
public class RelayController {

    private final RelayService relayService;

    public RelayController(RelayService relayService) {
        this.relayService = relayService;
    }

    /** 将 OPEN 赛事配置为接力赛。 */
    @PostMapping("/config")
    public ResponseEntity<Object> configureRelay(
            @PathVariable String raceId,
            @Valid @RequestBody ConfigureRelayRequest request) {
        return toResponse(relayService.configureRelay(raceId, request));
    }

    /** 登记接力队伍。 */
    @PostMapping("/teams")
    public ResponseEntity<Object> registerTeam(
            @PathVariable String raceId,
            @Valid @RequestBody RegisterRelayTeamRequest request) {
        return toResponse(relayService.registerTeam(raceId, request));
    }

    /** 提交一次交接。 */
    @PostMapping("/handoffs")
    public ResponseEntity<Object> submitHandoff(
            @PathVariable String raceId,
            @Valid @RequestBody SubmitHandoffRequest request) {
        return toResponse(relayService.submitHandoff(raceId, request));
    }

    /** 查询某队逐棒明细与犯规清单。 */
    @GetMapping("/teams/{teamKey}")
    public RelayTeamDetailResponse getTeamDetail(
            @PathVariable String raceId,
            @PathVariable String teamKey) {
        return relayService.getTeamDetail(raceId, teamKey);
    }

    /** 查询赛事下全部犯规记录。 */
    @GetMapping("/fouls")
    public List<FoulResponse> getFouls(@PathVariable String raceId) {
        return relayService.getFouls(raceId);
    }

    /** 查询接力即时排名（封榜后返回只读排名）。 */
    @GetMapping("/standing")
    public RelayStandingResponse getStanding(@PathVariable String raceId) {
        return relayService.getStanding(raceId);
    }

    private ResponseEntity<Object> toResponse(ServiceResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
