package com.medirecords.support;

import java.util.concurrent.ThreadLocalRandom;

/** Helpers for test data that must not collide with rows left by other tests in the shared database. */
public final class TestData {

    private TestData() {
    }

    /** Random lower-case letters; letters only, so the value is also a valid name. */
    public static String randomLetters(int length) {
        StringBuilder letters = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            letters.append((char) ThreadLocalRandom.current().nextInt('a', 'z' + 1));
        }
        return letters.toString();
    }
}
