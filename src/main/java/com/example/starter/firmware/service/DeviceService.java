package com.example.starter.firmware.service;

import com.example.starter.firmware.dto.DeviceRegisterRequest;
import com.example.starter.firmware.dto.DeviceResponse;
import com.example.starter.firmware.error.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 设备登记与查询。型号与分桶号登记后不可修改，不提供变更入口。
 */
@Service
public class DeviceService {

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;

    public DeviceService(JdbcTemplate jdbc, IdempotencyService idempotency) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
    }

    /**
     * 登记设备；deviceId 已存在时返回 409。
     */
    @Transactional
    public DeviceResponse register(DeviceRegisterRequest req) {
        String hash = String.join("|", req.deviceId(), req.model(), req.firmwareVersion(),
                String.valueOf(req.bucket()));
        return idempotency.execute(req.requestId(), "DEVICE_REGISTER", hash, DeviceResponse.class, () -> {
            try {
                jdbc.update("INSERT INTO device(device_id, model, current_version, bucket) VALUES (?,?,?,?)",
                        req.deviceId(), req.model(), req.firmwareVersion(), req.bucket());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_EXISTS", "device already registered: " + req.deviceId());
            }
            return new DeviceResponse(req.deviceId(), req.model(), req.firmwareVersion(), req.bucket());
        });
    }

    /**
     * 查询设备；不存在时返回 404。
     */
    @Transactional(readOnly = true)
    public DeviceResponse get(String deviceId) {
        List<DeviceResponse> rows = jdbc.query(
                "SELECT device_id, model, current_version, bucket FROM device WHERE device_id=?",
                (rs, i) -> new DeviceResponse(rs.getString("device_id"), rs.getString("model"),
                        rs.getString("current_version"), rs.getInt("bucket")),
                deviceId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("DEVICE_NOT_FOUND", "device not found: " + deviceId);
        }
        return rows.get(0);
    }
}
