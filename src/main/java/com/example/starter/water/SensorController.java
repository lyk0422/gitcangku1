package com.example.starter.water;

import com.example.starter.water.dto.Dtos.ChannelDiagnosticsResponse;
import com.example.starter.water.dto.Dtos.ChannelReadingsResponse;
import com.example.starter.water.dto.Dtos.ReadingBatchRequest;
import com.example.starter.water.dto.Dtos.ReadingBatchResponse;
import com.example.starter.water.dto.Dtos.ResolveAnomalyRequest;
import com.example.starter.water.dto.Dtos.AnomalyResponse;
import com.example.starter.water.dto.Dtos.SensorRuleRequest;
import com.example.starter.water.dto.Dtos.SensorRuleResponse;
import com.example.starter.water.dto.Dtos.WriteOffListResponse;
import com.example.starter.water.dto.Dtos.WriteOffRequest;
import com.example.starter.water.dto.Dtos.WriteOffResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配水计量传感异常与核销 REST 接口。写操作携带 commandKey 保证幂等；
 * 操作人通过 X-Actor-Id 识别，异常解除人必须不同于异常提交人。
 */
@RestController
@RequestMapping("/api")
public class SensorController {

    private final SensorService service;

    public SensorController(SensorService service) {
        this.service = service;
    }

    /** 配置/更新渠道传感规则，生成不可变新版本。 */
    @PostMapping("/channels/{channelId}/sensor-rules")
    public SensorRuleResponse configureRule(@PathVariable String channelId,
                                            @RequestBody SensorRuleRequest request,
                                            @RequestHeader("X-Actor-Id") String actor) {
        return service.configureRule(request.commandKey(), channelId, request.maxFlowRate(),
                request.maxGapMinutes(), actor);
    }

    /** 提交一批渠道累计读数。 */
    @PostMapping("/channels/{channelId}/readings")
    public ReadingBatchResponse submitReadings(@PathVariable String channelId,
                                               @RequestBody ReadingBatchRequest request,
                                               @RequestHeader("X-Actor-Id") String actor) {
        return service.submitReadings(request.commandKey(), request.batchKey(), channelId,
                request.readings(), actor);
    }

    /** 解除异常：不同操作人确认诊断说明与替代读数。 */
    @PostMapping("/anomalies/{anomalyId}/resolve")
    public AnomalyResponse resolveAnomaly(@PathVariable long anomalyId,
                                          @RequestBody ResolveAnomalyRequest request,
                                          @RequestHeader("X-Actor-Id") String actor) {
        return service.resolveAnomaly(request.commandKey(), anomalyId, request.diagnosisNote(),
                request.replacementCumulative(), actor);
    }

    /** 对已批准申请执行实际核销；渠道冻结期间被拦截。 */
    @PostMapping("/write-offs")
    public WriteOffResponse writeOff(@RequestBody WriteOffRequest request,
                                     @RequestHeader("X-Actor-Id") String actor) {
        return service.writeOff(request.commandKey(), request.writeOffKey(), request.allocationKey(),
                request.amount(), request.atUtc(), actor);
    }

    /** 查询申请核销明细。 */
    @GetMapping("/allocations/{allocationKey}/write-offs")
    public WriteOffListResponse getWriteOffs(@PathVariable String allocationKey) {
        return service.getWriteOffs(allocationKey);
    }

    /** 查询渠道读数历史。 */
    @GetMapping("/channels/{channelId}/readings")
    public ChannelReadingsResponse getReadings(@PathVariable String channelId) {
        return service.getChannelReadings(channelId);
    }

    /** 查询渠道诊断（规则、冻结、异常与替代读数）。 */
    @GetMapping("/channels/{channelId}/diagnostics")
    public ChannelDiagnosticsResponse getDiagnostics(@PathVariable String channelId) {
        return service.getDiagnostics(channelId);
    }
}
