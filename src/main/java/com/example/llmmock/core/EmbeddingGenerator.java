package com.example.llmmock.core;

import java.util.Random;

import org.springframework.stereotype.Component;

/**
 * Produces deterministic unit-length pseudo embeddings. The same input always yields the
 * same vector, so tests can assert on similarity without a real model.
 */
@Component
public class EmbeddingGenerator {

    public double[] embed(String input, int dimensions) {
        int size = Math.max(1, dimensions);
        double[] vector = new double[size];
        Random random = new Random(input == null ? 0L : input.hashCode());
        double norm = 0.0;
        for (int i = 0; i < size; i++) {
            vector[i] = random.nextGaussian();
            norm += vector[i] * vector[i];
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0) {
            norm = 1.0;
        }
        for (int i = 0; i < size; i++) {
            // Round so the JSON payload stays compact and byte-for-byte reproducible.
            vector[i] = Math.round(vector[i] / norm * 1_000_000d) / 1_000_000d;
        }
        return vector;
    }
}
