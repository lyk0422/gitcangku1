package com.example.starter.firmware;

import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.support.ModuleVersion;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 点分数字版本比较单元测试：按段数值比较、缺段视为0；非法格式统一 422。
 */
class ModuleVersionTest {

    @Test
    void 合法版本按段数值比较_缺段视为零() {
        assertThat(ModuleVersion.parse("1").compareTo(ModuleVersion.parse("1.0"))).isZero();
        assertThat(ModuleVersion.parse("1.2").compareTo(ModuleVersion.parse("1.2.0"))).isZero();
        assertThat(ModuleVersion.parse("1.10")).isGreaterThan(ModuleVersion.parse("1.9"));
        assertThat(ModuleVersion.parse("2.0.0")).isGreaterThan(ModuleVersion.parse("1.99.99"));
        assertThat(ModuleVersion.parse("1.2.3")).isLessThan(ModuleVersion.parse("1.2.4"));
        assertThat(ModuleVersion.parse("10")).isGreaterThan(ModuleVersion.parse("9"));
        assertThat(ModuleVersion.parse("0.0.1")).isGreaterThan(ModuleVersion.parse("0.0"));
        assertThat(ModuleVersion.parse("1.2.3").raw()).isEqualTo("1.2.3");
    }

    @Test
    void 非法版本返回422_错误码稳定() {
        for (String invalid : List.of("", " ", "1.", ".1", "1..2", "1.a", "v1.2", "1.2-rc",
                "01.2", "1.02", "-1.2", "1.2.3.4.5.6.7.8.9.10.11.12.13.14.15.16.17")) {
            assertThatThrownBy(() -> ModuleVersion.parse(invalid))
                    .as("非法版本: '%s'", invalid)
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.code()).isEqualTo("INVALID_VERSION_FORMAT");
                    });
        }
        assertThatThrownBy(() -> ModuleVersion.parse(null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("INVALID_VERSION_FORMAT"));
    }

    @Test
    void 等版本equals与hashCode按数值而非字符串() {
        assertThat(ModuleVersion.parse("1.2.0")).isEqualTo(ModuleVersion.parse("1.2"));
        assertThat(ModuleVersion.parse("1.2.0").hashCode()).isEqualTo(ModuleVersion.parse("1.2").hashCode());
    }
}
