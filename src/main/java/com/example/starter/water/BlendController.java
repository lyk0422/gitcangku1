package com.example.starter.water;

import com.example.starter.water.dto.Dtos.BlendApplicationResponse;
import com.example.starter.water.dto.Dtos.BlendSourceRequest;
import com.example.starter.water.dto.Dtos.BlendSourceResponse;
import com.example.starter.water.dto.Dtos.BlendSubmitRequest;
import com.example.starter.water.dto.Dtos.CommandRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多来源水质混配 REST 接口。申请提交/取消通过 X-Actor-Id 识别操作人；
 * 提交以 requestId 幂等，建来源与取消携带 commandKey。
 */
@RestController
@RequestMapping("/api/blend")
public class BlendController {

    private final BlendService service;

    public BlendController(BlendService service) {
        this.service = service;
    }

    /** 创建水质来源（固定配额池与水质指标）。 */
    @PostMapping("/sources")
    public BlendSourceResponse createSource(@RequestBody BlendSourceRequest request) {
        return service.createSource(request.commandKey(), request.sourceKey(), request.name(),
                request.quotaVolume(), request.qualityIndex());
    }

    /** 查询来源配额、指标与预占/可用体积。 */
    @GetMapping("/sources/{sourceKey}")
    public BlendSourceResponse getSource(@PathVariable String sourceKey) {
        return service.getSource(sourceKey);
    }

    /** 提交多来源水质混配申请：全部来源一次性原子预占。 */
    @PostMapping("/applications")
    public BlendApplicationResponse submitBlend(@RequestBody BlendSubmitRequest request,
                                                @RequestHeader("X-Actor-Id") String actor) {
        return service.submitBlend(request.requestId(), request.userId(), request.purpose(),
                request.qualityThreshold(), request.sources(), actor);
    }

    /** 查询混配申请：每种来源、体积、指标、加权贡献值与预占状态。 */
    @GetMapping("/applications/{requestId}")
    public BlendApplicationResponse getApplication(@PathVariable String requestId) {
        return service.getApplication(requestId);
    }

    /** 取消混配申请（仅申请人本人）：释放原预占量，历史计算依据保留不重算。 */
    @PostMapping("/applications/{requestId}/cancel")
    public BlendApplicationResponse cancelBlend(@PathVariable String requestId,
                                                @RequestBody CommandRequest request,
                                                @RequestHeader("X-Actor-Id") String actor) {
        return service.cancelBlend(request.commandKey(), requestId, actor);
    }
}
