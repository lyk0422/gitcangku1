package com.example.starter.firmware;

import com.example.starter.firmware.domain.ModuleVersions;
import com.example.starter.firmware.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 点分数字版本比较器单元测试：数值比较、缺段补零、前导零、非法格式 422。
 */
class ModuleVersionsTest {

    @Test
    void 比较_按段数值与缺段补零() {
        assertThat(ModuleVersions.compare("1.2.0", "1.2")).isZero();
        assertThat(ModuleVersions.compare("1.2.1", "1.2.0")).isPositive();
        assertThat(ModuleVersions.compare("1.10.0", "1.9.9")).isPositive();
        assertThat(ModuleVersions.compare("2", "10")).isNegative();
        assertThat(ModuleVersions.compare("0.9", "1.0")).isNegative();
        assertThat(ModuleVersions.compare("1.0.0.1", "1.0.0")).isPositive();
    }

    @Test
    void 比较_前导零不影响数值_超长段不溢出() {
        assertThat(ModuleVersions.compare("01.002", "1.2")).isZero();
        assertThat(ModuleVersions.compare("99999999999999999999", "1")).isPositive();
        assertThat(ModuleVersions.compare("1.000", "1.0.0")).isZero();
    }

    @Test
    void 满足_设备版本大于等于最低要求() {
        assertThat(ModuleVersions.satisfies("1.2.0", "1.2.0")).isTrue();
        assertThat(ModuleVersions.satisfies("1.2.1", "1.2.0")).isTrue();
        assertThat(ModuleVersions.satisfies("1.2", "1.2.0")).isTrue();
        assertThat(ModuleVersions.satisfies("1.1.9", "1.2.0")).isFalse();
    }

    @Test
    void 非法格式_抛出422() {
        assertInvalid(null);
        assertInvalid("");
        assertInvalid("1..2");
        assertInvalid("1.");
        assertInvalid(".1");
        assertInvalid("1.2.x");
        assertInvalid("-1.2");
        assertInvalid("1.2-beta");
        assertInvalid("1,2");
        assertInvalid(" 1.2");
    }

    private static void assertInvalid(String version) {
        assertThatThrownBy(() -> ModuleVersions.validate(version))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.code()).isEqualTo("INVALID_VERSION_FORMAT");
                });
    }
}
