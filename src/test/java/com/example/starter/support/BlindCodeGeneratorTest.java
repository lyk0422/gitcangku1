package com.example.starter.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BlindCodeGeneratorTest {

    private final BlindCodeGenerator generator = new BlindCodeGenerator();

    @Test
    void generatesTwentyFourHexCharsWithoutStructuralMeaning() {
        String code = generator.next();
        assertThat(code).matches("[0-9a-f]{24}");
    }

    @Test
    void generatedCodesAreUniqueAndVaried() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            codes.add(generator.next());
        }
        assertThat(codes).hasSize(2000);
    }
}
