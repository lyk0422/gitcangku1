package com.example.starter;

import com.example.starter.web.dto.Requests;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求 DTO 跨字段校验的纯单元测试。
 */
class RequestsTest {

    @Test
    void germinatedNotExceedSample_rejectsGerminatedGreaterThanSample() {
        assertFalse(new Requests.RegisterTestRequest("T-1", 100, 101, null)
                .germinatedNotExceedSample());
        assertTrue(new Requests.RegisterTestRequest("T-1", 100, 100, null)
                .germinatedNotExceedSample());
        assertTrue(new Requests.RegisterTestRequest("T-1", 400, 0, null)
                .germinatedNotExceedSample());
    }
}
