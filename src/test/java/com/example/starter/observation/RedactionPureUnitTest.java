package com.example.starter.observation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不依赖 Spring 与数据库的纯单元测试：角色解析与受限展示摘要生成规则。
 */
class RedactionPureUnitTest {

    @Test
    void roleFromHeaderDefaultsToPublicAndRejectsUnknown() {
        assertThat(RequesterRole.fromHeader(null)).isEqualTo(RequesterRole.PUBLIC);
        assertThat(RequesterRole.fromHeader("")).isEqualTo(RequesterRole.PUBLIC);
        assertThat(RequesterRole.fromHeader("   ")).isEqualTo(RequesterRole.PUBLIC);
        assertThat(RequesterRole.fromHeader("public")).isEqualTo(RequesterRole.PUBLIC);
        assertThat(RequesterRole.fromHeader("VIEWER")).isEqualTo(RequesterRole.PUBLIC);
        assertThat(RequesterRole.fromHeader("redaction_officer")).isEqualTo(RequesterRole.REDACTION_OFFICER);
        assertThat(RequesterRole.fromHeader(" EVIDENCE_READER ")).isEqualTo(RequesterRole.EVIDENCE_READER);
        // 无法识别的角色返回 null，由控制器统一按未授权 403 处理
        assertThat(RequesterRole.fromHeader("ADMIN")).isNull();
        assertThat(RequesterRole.fromHeader("hacker")).isNull();
    }

    @Test
    void displaySummaryCarriesRuleVersionAndReplacementNote() {
        assertThat(RedactionService.buildDisplaySummary("rule-v1", "证件号替换为 ***"))
                .isEqualTo("[REDACTED rule=rule-v1] 证件号替换为 ***");
        assertThat(RedactionService.buildDisplaySummary("v2026.09", ""))
                .isEqualTo("[REDACTED rule=v2026.09] ");
    }

    @Test
    void apiExceptionExposesDistinctStatusCodes() {
        assertThat(ApiException.forbidden("x").status().value()).isEqualTo(403);
        assertThat(ApiException.notFound("x").status().value()).isEqualTo(404);
        assertThat(ApiException.conflict("x", null).status().value()).isEqualTo(409);
        assertThat(ApiException.unprocessable("x").status().value()).isEqualTo(422);
        assertThat(ApiException.gone("x").status().value()).isEqualTo(410);
    }
}
