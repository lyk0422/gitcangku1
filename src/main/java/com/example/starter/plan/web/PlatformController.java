package com.example.starter.plan.web;

import com.example.starter.plan.service.PlatformService;
import com.example.starter.plan.web.dto.PlatformBookingView;
import com.example.starter.plan.web.dto.PlatformCapabilityRequest;
import com.example.starter.plan.web.dto.PlatformCapabilityView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站台管理方 API：版本化有效能力登记与本站台生效到发占用查询。
 */
@RestController
@RequestMapping("/api/v1/platforms")
public class PlatformController {

    private final PlatformService service;

    public PlatformController(PlatformService service) {
        this.service = service;
    }

    /**
     * 登记站台新一版有效能力（仅追加，历史版本保留），按 requestKey 幂等。
     */
    @PostMapping("/{platformId}/capabilities")
    @ResponseStatus(HttpStatus.CREATED)
    public PlatformCapabilityView registerCapability(@PathVariable String platformId,
                                                     @Valid @RequestBody PlatformCapabilityRequest request) {
        if (!platformId.equals(request.platformId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                    "路径站台 ID 与请求体不一致: " + platformId + " != " + request.platformId());
        }
        return service.registerCapability(request);
    }

    /**
     * 查询站台当前有效能力（最大版本）。
     */
    @GetMapping("/{platformId}/capability")
    public PlatformCapabilityView getCapability(@PathVariable String platformId) {
        return service.getCapability(platformId);
    }

    /**
     * 查询站台当前生效的到发占用（来源计划已取消的登记不返回，窗口已释放）。
     */
    @GetMapping("/{platformId}/bookings")
    public List<PlatformBookingView> getBookings(@PathVariable String platformId) {
        return service.getActiveBookings(platformId);
    }
}
