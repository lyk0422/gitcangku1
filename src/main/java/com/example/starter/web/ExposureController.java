package com.example.starter.web;

import com.example.starter.service.ExposureService;
import com.example.starter.service.ExposureService.RawJson;
import com.example.starter.service.ServiceResult;
import com.example.starter.web.dto.ApplyExposureRequest;
import com.example.starter.web.dto.CreateCampaignRequest;
import com.example.starter.web.dto.QuotaResponse;
import com.example.starter.web.dto.ReservationActionRequest;
import com.example.starter.web.dto.ReservationResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公告曝光频控 REST API。
 */
@RestController
@RequestMapping("/api")
public class ExposureController {

    private final ExposureService service;

    public ExposureController(ExposureService service) {
        this.service = service;
    }

    /** 创建公告（额度创建后固定）。 */
    @PostMapping("/campaigns")
    public ResponseEntity<Object> createCampaign(@Valid @RequestBody CreateCampaignRequest request) {
        return toResponseEntity(service.createCampaign(request));
    }

    /** 申请曝光：返回 201 与 60 秒有效的预占；额度已满返回 429。 */
    @PostMapping("/exposures")
    public ResponseEntity<Object> apply(@Valid @RequestBody ApplyExposureRequest request) {
        return toResponseEntity(service.apply(request));
    }

    /** 确认预占；重复确认返回原状态，已取消/过期返回 409。 */
    @PostMapping("/reservations/{reservationId}/confirm")
    public ResponseEntity<Object> confirm(@PathVariable String reservationId,
                                          @Valid @RequestBody ReservationActionRequest request) {
        return toResponseEntity(service.confirm(reservationId, request.getRequestId()));
    }

    /** 取消预占；仅 RESERVED 可取消，重复取消返回原状态，已确认/过期返回 409。 */
    @PostMapping("/reservations/{reservationId}/cancel")
    public ResponseEntity<Object> cancel(@PathVariable String reservationId,
                                         @Valid @RequestBody ReservationActionRequest request) {
        return toResponseEntity(service.cancel(reservationId, request.getRequestId()));
    }

    /** 预占明细（惰性结算过期状态）。 */
    @GetMapping("/reservations/{reservationId}")
    public ReservationResponse getReservation(@PathVariable String reservationId) {
        return service.getReservation(reservationId);
    }

    /**
     * 按公告/访客/UTC 日查询额度；utcDate 缺省取当前 UTC 日，visitorId 缺省返回全部访客账目。
     */
    @GetMapping("/campaigns/{campaignId}/quota")
    public QuotaResponse getQuota(@PathVariable String campaignId,
                                  @RequestParam(required = false) String visitorId,
                                  @RequestParam(required = false) String utcDate) {
        return service.getQuota(campaignId, visitorId, utcDate);
    }

    private static ResponseEntity<Object> toResponseEntity(ServiceResult<Object> result) {
        Object body = result.body();
        if (body instanceof RawJson raw) {
            return ResponseEntity.status(HttpStatus.valueOf(result.status()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(raw.json());
        }
        return ResponseEntity.status(HttpStatus.valueOf(result.status())).body(body);
    }
}
