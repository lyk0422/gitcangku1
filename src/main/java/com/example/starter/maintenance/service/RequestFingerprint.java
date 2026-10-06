package com.example.starter.maintenance.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.SetMeterRangeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等身份指纹：以结构化 JSON（操作者、资源、全部参数、期望版本）序列化生成，
 * 避免分隔符拼接造成的参数碰撞；操作类型由幂等记录单独字段承载并比较。
 */
@Component
public class RequestFingerprint {

    private final ObjectMapper objectMapper;

    public RequestFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String register(RegisterEquipmentRequest req) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("resource", req.equipmentId());
        identity.put("maintenancePeriodMinutes", req.maintenancePeriodMinutes());
        return write(identity);
    }

    public String setMeterRange(String equipmentId, SetMeterRangeRequest req) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operator", req.operator());
        identity.put("resource", equipmentId);
        identity.put("meterRangeMinutes", req.meterRangeMinutes());
        identity.put("expectedVersion", req.expectedVersion());
        return write(identity);
    }

    public String addReading(String equipmentId, AddReadingRequest req) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operator", req.operator());
        identity.put("resource", equipmentId);
        identity.put("readingId", req.readingId());
        identity.put("sampledAt", req.sampledAt() == null ? null : req.sampledAt().toString());
        identity.put("cumulativeMinutes", req.cumulativeMinutes());
        identity.put("raw", req.raw());
        identity.put("wrapCount", req.wrapCount());
        identity.put("expectedVersion", req.expectedVersion());
        return write(identity);
    }

    public String reviseReading(String equipmentId, String readingId, ReviseReadingRequest req) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operator", req.operator());
        identity.put("resource", equipmentId);
        identity.put("readingId", readingId);
        identity.put("readingRevision", req.readingRevision());
        identity.put("cumulativeMinutes", req.cumulativeMinutes());
        identity.put("raw", req.raw());
        identity.put("wrapCount", req.wrapCount());
        identity.put("expectedVersion", req.expectedVersion());
        return write(identity);
    }

    public String completeMaintenance(String equipmentId, CompleteMaintenanceRequest req) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("resource", equipmentId);
        identity.put("readingId", req.readingId());
        identity.put("anchorRevisionNo", req.anchorRevisionNo());
        identity.put("expectedVersion", req.expectedVersion());
        return write(identity);
    }

    private String write(Map<String, Object> identity) {
        try {
            return objectMapper.writeValueAsString(identity);
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
    }
}
