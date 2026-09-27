package com.example.starter.water;

import com.example.starter.water.dto.Dtos.AnomalyListResponse;
import com.example.starter.water.dto.Dtos.AnomalyResponse;
import com.example.starter.water.dto.Dtos.ChannelResponse;
import com.example.starter.water.dto.Dtos.ConfigureChannelRequest;
import com.example.starter.water.dto.Dtos.ReadingListResponse;
import com.example.starter.water.dto.Dtos.ResolveAnomalyRequest;
import com.example.starter.water.dto.Dtos.SubmitReadingsRequest;
import com.example.starter.water.dto.Dtos.SubmitReadingsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配水传感计量 REST 接口。写操作携带 commandKey 保证幂等；
 * 配置、读数提交与异常解除通过 X-Actor-Id 识别操作人（解除人必须不同于异常提交人）。
 */
@RestController
@RequestMapping("/api")
public class MeterController {

    private final MeterService service;

    public MeterController(MeterService service) {
        this.service = service;
    }

    /** 配置渠道异常规则（首次注册渠道并生成版本 1，之后只追加新版本）。 */
    @PostMapping("/channels/{channelId}/rule")
    public ChannelResponse configureChannel(@PathVariable String channelId,
                                            @RequestBody ConfigureChannelRequest request,
                                            @RequestHeader("X-Actor-Id") String actor) {
        return service.configureChannel(request.commandKey(), channelId, request.maxFlowRate(),
                request.maxIntervalMinutes(), actor);
    }

    /** 批量提交带 UTC 时刻的渠道累计读数；序列任一错误整批拒绝（422）。 */
    @PostMapping("/channels/{channelId}/readings")
    public SubmitReadingsResponse submitReadings(@PathVariable String channelId,
                                                 @RequestBody SubmitReadingsRequest request,
                                                 @RequestHeader("X-Actor-Id") String actor) {
        return service.submitReadings(request.commandKey(), channelId, request.readings(), actor);
    }

    /** 解除异常：不同操作人确认诊断说明与替代读数。 */
    @PostMapping("/anomalies/{anomalyId}/resolve")
    public AnomalyResponse resolveAnomaly(@PathVariable long anomalyId,
                                          @RequestBody ResolveAnomalyRequest request,
                                          @RequestHeader("X-Actor-Id") String actor) {
        return service.resolveAnomaly(request.commandKey(), anomalyId, actor, request.diagnosisNote(),
                request.replacementReadingUtc(), request.replacementCumulativeVolume());
    }

    /** 查询渠道当前状态（冻结派生、当前规则版本）。 */
    @GetMapping("/channels/{channelId}")
    public ChannelResponse getChannel(@PathVariable String channelId) {
        return service.getChannel(channelId);
    }

    /** 查询渠道读数明细（不可变，稳定排序）。 */
    @GetMapping("/channels/{channelId}/readings")
    public ReadingListResponse getReadings(@PathVariable String channelId) {
        return service.listReadings(channelId);
    }

    /** 查询渠道异常历史与诊断（不可变，稳定排序）。 */
    @GetMapping("/channels/{channelId}/anomalies")
    public AnomalyListResponse getAnomalies(@PathVariable String channelId) {
        return service.listAnomalies(channelId);
    }

    /** 查询单条异常证据及解除诊断。 */
    @GetMapping("/anomalies/{anomalyId}")
    public AnomalyResponse getAnomaly(@PathVariable long anomalyId) {
        return service.getAnomaly(anomalyId);
    }
}
