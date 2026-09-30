package com.example.starter.salinity.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.example.starter.salinity.command.AdoptSampleCommand;
import com.example.starter.salinity.command.SealVoyageCommand;
import com.example.starter.salinity.exception.BusinessRuleException;
import com.example.starter.salinity.exception.ConflictException;
import com.example.starter.salinity.exception.ResourceNotFoundException;
import com.example.starter.salinity.service.SalinityService;
import com.example.starter.salinity.view.AdoptedSampleView;
import com.example.starter.salinity.view.FrozenProfilePointView;
import com.example.starter.salinity.view.SampleView;
import com.example.starter.salinity.view.VoyageDataView;

/**
 * HTTP 入口切片测试：状态码映射、校验、JSON 序列化顺序。
 */
@WebMvcTest(SalinityController.class)
class SalinityControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SalinityService service;

    private static final String VALID_BODY = """
            {
              "voyageCode": "V1",
              "stationCode": "S",
              "depthM": 50,
              "salinityPsu": 34.800,
              "collectedUtc": "2026-09-15T02:30:00Z",
              "sampleNo": "N-50",
              "requestId": "REQ-1"
            }
            """;

    @Test
    void submitValidReturns201AndBody() throws Exception {
        when(service.submit(any())).thenReturn(new SampleView(
                "N-50", "S", 50, new BigDecimal("34.800"),
                Instant.parse("2026-09-15T02:30:00Z"), false, null, "REQ-1"));

        mockMvc.perform(post("/api/salinity/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sampleNo").value("N-50"))
                .andExpect(jsonPath("$.depthM").value(50))
                .andExpect(jsonPath("$.adopted").value(false));
    }

    @Test
    void submitDepthOutOfRangeReturns422() throws Exception {
        String body = VALID_BODY.replace("\"depthM\": 50", "\"depthM\": 501");
        mockMvc.perform(post("/api/salinity/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void businessExceptionMapsTo422() throws Exception {
        when(service.adopt(any(AdoptSampleCommand.class)))
                .thenThrow(new BusinessRuleException("跨航次质控：样本属于其他航次"));
        mockMvc.perform(post("/api/salinity/adoptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"voyageCode": "V2", "sampleNo": "N-50"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("跨航次质控：样本属于其他航次"));
    }

    @Test
    void conflictExceptionMapsTo409() throws Exception {
        when(service.submit(any()))
                .thenThrow(new ConflictException("采样编号已存在且内容不同: N-50"));
        mockMvc.perform(post("/api/salinity/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("采样编号已存在且内容不同: N-50"));
    }

    @Test
    void notFoundMapsTo404() throws Exception {
        when(service.query("NOPE")).thenThrow(new ResourceNotFoundException("航次不存在: NOPE"));
        mockMvc.perform(get("/api/salinity/voyages/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("航次不存在: NOPE"));
    }

    @Test
    void sealMissingDepthReturns422() throws Exception {
        org.mockito.Mockito.doThrow(new BusinessRuleException("封存条件不满足: 站位 S 缺 50 米采纳样本"))
                .when(service).seal(any(SealVoyageCommand.class));
        mockMvc.perform(post("/api/salinity/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"voyageCode": "V1"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("50")));
    }

    @Test
    void sealSuccessReturns204() throws Exception {
        org.mockito.Mockito.doNothing().when(service).seal(eq(new SealVoyageCommand("V1")));
        mockMvc.perform(post("/api/salinity/seals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"voyageCode": "V1"}
                                """))
                .andExpect(status().isNoContent());
        verify(service).seal(eq(new SealVoyageCommand("V1")));
    }

    @Test
    void queryReturnsSamplesAdoptedAndFrozenProfileInDepthOrder() throws Exception {
        Instant sealedAt = Instant.parse("2026-09-15T09:00:00Z");
        VoyageDataView view = new VoyageDataView(
                "V1", true, sealedAt,
                List.of(new SampleView("N-0", "S", 0, new BigDecimal("35.1"),
                        Instant.parse("2026-09-15T02:00:00Z"), true, null, "REQ-0")),
                List.of(new AdoptedSampleView("N-0", "S", 0, new BigDecimal("35.1"),
                        Instant.parse("2026-09-15T02:00:00Z"))),
                List.of(
                        new FrozenProfilePointView("S", 0, "N-0", new BigDecimal("35.1"),
                                Instant.parse("2026-09-15T02:00:00Z")),
                        new FrozenProfilePointView("S", 50, "N-50", new BigDecimal("34.8"),
                                Instant.parse("2026-09-15T02:30:00Z")),
                        new FrozenProfilePointView("S", 100, "N-100", new BigDecimal("34.0"),
                                Instant.parse("2026-09-15T03:00:00Z"))),
                List.of());
        when(service.query("V1")).thenReturn(view);

        mockMvc.perform(get("/api/salinity/voyages/V1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sealed").value(true))
                .andExpect(jsonPath("$.frozenProfile[0].depthM").value(0))
                .andExpect(jsonPath("$.frozenProfile[1].depthM").value(50))
                .andExpect(jsonPath("$.frozenProfile[2].depthM").value(100));
    }
}
