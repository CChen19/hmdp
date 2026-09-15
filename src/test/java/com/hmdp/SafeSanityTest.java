package com.hmdp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Trivial default-suite test: no Spring context, MySQL, or Redis.
 */
class SafeSanityTest {

    @Test
    void addition_works() {
        assertEquals(2, 1 + 1);
    }
}
