package com.hmdp.order;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static checks on seckill.lua without talking to Redis.
 * For a live redis-cli smoke check, see docs/SECKILL-RULES.md.
 */
class SeckillLuaScriptTest {

    @Test
    void luaEnforcesActivityWindowAndMissingMetadata() throws Exception {
        String lua = StreamUtils.copyToString(
                new ClassPathResource("seckill.lua").getInputStream(),
                StandardCharsets.UTF_8);

        assertTrue(lua.contains("seckill:begin:"), "begin key");
        assertTrue(lua.contains("seckill:end:"), "end key");
        assertTrue(lua.contains("ARGV[4]"), "server now epoch");
        assertTrue(lua.contains("return 3"), "reject outside window / missing meta");
        assertTrue(lua.contains("now < beginTime") || lua.contains("now < beginTime or now > endTime"),
                "inclusive window check");
    }
}
