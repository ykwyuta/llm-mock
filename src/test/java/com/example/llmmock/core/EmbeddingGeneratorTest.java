package com.example.llmmock.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EmbeddingGeneratorTest {

    private final EmbeddingGenerator generator = new EmbeddingGenerator();

    @Test
    void sameInputAlwaysProducesTheSameVector() {
        assertThat(generator.embed("hello", 16)).containsExactly(generator.embed("hello", 16));
    }

    @Test
    void differentInputsProduceDifferentVectors() {
        assertThat(generator.embed("hello", 16)).isNotEqualTo(generator.embed("goodbye", 16));
    }

    @Test
    void vectorsHaveTheRequestedDimensionAndUnitLength() {
        double[] vector = generator.embed("hello", 64);

        assertThat(vector).hasSize(64);
        double norm = 0.0;
        for (double value : vector) {
            norm += value * value;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(1e-4));
    }
}
