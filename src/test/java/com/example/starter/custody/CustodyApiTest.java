package com.example.starter.custody;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web层测试:完整业务链的HTTP状态码与响应内容,含题干100拆60+40成功、60+50整单422场景。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:timber-api-test;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa"})
@AutoConfigureMockMvc
class CustodyApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotency_key");
        jdbc.update("DELETE FROM transfer");
        jdbc.update("DELETE FROM batch");
    }

    @Test
    void fullChainOverHttp() throws Exception {
        mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON).content("""
                {"voucherNo":"VCH-100","species":"松木","weightKg":100,"batchCode":"SRC-100","holder":"加工点A"}
                """)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 100公斤来源拆为60与40公斤成功
        mvc.perform(post("/api/batches/SRC-100/splits").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"req-api-1","children":[{"batchCode":"SUB-60","weightKg":60},{"batchCode":"SUB-40","weightKg":40}]}
                        """)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.children", hasSize(2)))
                .andExpect(jsonPath("$.children[0].voucherNo").value("VCH-100"));

        // 60与50公斤不守恒,整单422且原批次仍可用
        mvc.perform(post("/api/batches/SUB-60/splits").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"req-api-2","children":[{"batchCode":"G-60","weightKg":60},{"batchCode":"G-50","weightKg":50}]}
                        """)).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/batches/SUB-60/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weightKg").value(60))
                .andExpect(jsonPath("$.currentHolder").value("加工点A"))
                .andExpect(jsonPath("$.chain", hasSize(2)))
                .andExpect(jsonPath("$.chain[0].batchCode").value("SRC-100"));

        // 移交并签收后持有人变更
        String transfer = mvc.perform(post("/api/batches/SUB-60/transfers")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"fromHolder":"加工点A","toHolder":"下游B"}
                        """)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long transferId = Long.parseLong(transfer.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mvc.perform(post("/api/transfers/" + transferId + "/signoff")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"requestId":"req-api-3","decision":"ACCEPT"}
                        """)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(get("/api/batches/SUB-60/trace"))
                .andExpect(jsonPath("$.currentHolder").value("下游B"));

        // 已拆分父批次不得直接移交
        mvc.perform(post("/api/batches/SRC-100/transfers").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromHolder":"加工点A","toHolder":"下游B"}
                        """)).andExpect(status().isConflict());

        // 无权移交返回422
        mvc.perform(post("/api/batches/SUB-40/transfers").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromHolder":"无关人员","toHolder":"下游B"}
                        """)).andExpect(status().isUnprocessableEntity());

        // 签收requestId异参返回409
        mvc.perform(post("/api/transfers/" + transferId + "/signoff")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"requestId":"req-api-3","decision":"REJECT","rejectReason":"改主意"}
                        """)).andExpect(status().isConflict());

        // 不存在的批次返回404
        mvc.perform(get("/api/batches/NOPE/trace")).andExpect(status().isNotFound());
    }
}