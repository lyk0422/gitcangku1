package com.example.starter.firmware;

import com.example.starter.firmware.domain.ModuleVersion;
import com.example.starter.firmware.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 点分数字模块版本解析与比较的纯单元测试，非法格式必须为 422。
 */
class ModuleVersionTest {

    @Test
    void 合法版本_逐段数值比较_缺失段按零补齐() {
        assertThat(ModuleVersion.parse("1.0.1", "t")).isGreaterThan(ModuleVersion.parse("1.0.0", "t"));
        assertThat(ModuleVersion.parse("1.2.0", "t")).isEqualByComparingTo(ModuleVersion.parse("1.2", "t"));
        assertThat(ModuleVersion.parse("10", "t")).isGreaterThan(ModuleVersion.parse("9", "t"));
        assertThat(ModuleVersion.parse("2.0.0", "t")).isGreaterThan(ModuleVersion.parse("1.99.99", "t"));
        assertThat(ModuleVersion.parse("1.0.0", "t")).isLessThan(ModuleVersion.parse("1.0.1", "t"));
        assertThat(ModuleVersion.parse("0", "t")).isLessThan(ModuleVersion.parse("0.0.1", "t"));
        assertThat(ModuleVersion.parse("1.10.0", "t")).isGreaterThan(ModuleVersion.parse("1.9.0", "t"));
        assertThat(ModuleVersion.parse("1", "t").raw()).isEqualTo("1");
    }

    @Test
    void 非法格式_一律422() {
        for (String invalid : new String[]{"", " ", "1.", ".1", "1..2", "1.a", "a.1", "-1",
                "+1", "1.2.3-rc", "v1.2", " 1.2", "1.2 ", "9999999999", "1.2.3.4.5.6.7.8.9.0.1.2.3.4.5.6.7"}) {
            assertThatThrownBy(() -> ModuleVersion.parse(invalid, "测试字段"))
                    .as("非法版本应被拒绝: " + invalid)
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.code()).isEqualTo("INVALID_MODULE_VERSION");
                    });
        }
    }

    @Test
    void null版本_422() {
        assertThatThrownBy(() -> ModuleVersion.parse(null, "测试字段"))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }
}
