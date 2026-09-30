package com.example.starter.water;

import com.example.starter.water.dto.Dtos.BlendApplicationResponse;
import com.example.starter.water.dto.Dtos.BlendSourceResponse;
import com.example.starter.water.dto.Dtos.CommandRequest;
import com.example.starter.water.dto.Dtos.RegisterSourceRequest;
import com.example.starter.water.dto.Dtos.SubmitBlendRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多来源水质混配 REST 接口。
 * 申请提交以 requestId 幂等；注册来源与取消申请携带 commandKey，写操作通过 X-Actor-Id 识别操作人。
 */
@RestController
@RequestMapping("/api/blend")
public class BlendController {

    private final BlendService service;

    public BlendController(BlendService service) {
        this.service = service;
    }

    /** 注册水质来源配额账户。 */
    @PostMapping("/sources")
    public BlendSourceResponse registerSource(@RequestBody RegisterSourceRequest request,
                                              @RequestHeader("X-Actor-Id") String actor) {
        return service.registerSource(request.commandKey(), request.sourceKey(), request.name(),
                request.qualityIndex(), request.totalQuota(), actor);
    }

    /** 查询水质来源配额与预占情况。 */
    @GetMapping("/sources/{sourceKey}")
    public BlendSourceResponse getSource(@PathVariable String sourceKey) {
        return service.getSource(sourceKey);
    }

    /** 提交多来源水质混配申请（受理即一次性预占全部来源配额）。 */
    @PostMapping("/applications")
    public BlendApplicationResponse submitBlend(@RequestBody SubmitBlendRequest request,
                                                @RequestHeader("X-Actor-Id") String actor) {
        return service.submitBlend(request.requestId(), request.userId(), request.purpose(),
                request.threshold(), request.sources(), actor);
    }

    /** 查询混配申请：每种来源、体积、指标、加权值与预占状态。 */
    @GetMapping("/applications/{requestId}")
    public BlendApplicationResponse getBlend(@PathVariable String requestId) {
        return service.getBlend(requestId);
    }

    /** 取消混配申请：释放原预占量，历史计算依据保留。 */
    @PostMapping("/applications/{requestId}/cancel")
    public BlendApplicationResponse cancelBlend(@PathVariable String requestId,
                                                @RequestBody CommandRequest request,
                                                @RequestHeader("X-Actor-Id") String actor) {
        return service.cancelBlend(request.commandKey(), requestId, actor);
    }
}
