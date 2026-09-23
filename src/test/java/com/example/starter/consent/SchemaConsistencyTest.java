package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * DDL 一致性守护：测试库执行的 schema.sql 必须与主 schema.sql 完全一致，
 * 保证 H2（MODE=MySQL）验证过的就是默认内存库启动时执行的同一份建表语句。
 */
class SchemaConsistencyTest {

    @Test
    void testSchemaIsIdenticalToMainSchema() throws IOException {
        String main = Files.readString(Path.of("src/main/resources/schema.sql"), StandardCharsets.UTF_8);
        String test = Files.readString(Path.of("src/test/resources/schema.sql"), StandardCharsets.UTF_8);
        assertThat(test).isEqualTo(main);
    }
}
