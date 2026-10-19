package com.liuxingyu.liulib.cooldown;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CooldownsTest {

    @Test
    void triggerIsAtomicAndExpires() {
        long[] now = {0L};
        Cooldowns cooldowns = new Cooldowns(null, () -> now[0]);
        UUID player = UUID.randomUUID();

        assertTrue(cooldowns.check("shop.buy", player));
        assertTrue(cooldowns.trigger("shop.buy", player, 10, TimeUnit.SECONDS));
        assertTrue(cooldowns.isCooling("shop.buy", player));
        assertEquals(10_000L, cooldowns.remainingMillis("shop.buy", player));
        assertEquals(10L, cooldowns.remainingSeconds("shop.buy", player));
        assertFalse(cooldowns.trigger("shop.buy", player, 10, TimeUnit.SECONDS));

        now[0] = TimeUnit.SECONDS.toNanos(10);
        assertFalse(cooldowns.isCooling("shop.buy", player));
        assertTrue(cooldowns.trigger("shop.buy", player, 1, TimeUnit.MINUTES));
    }

    @Test
    void actionsAndPlayersAreIsolated() {
        long[] now = {0L};
        Cooldowns cooldowns = new Cooldowns(null, () -> now[0]);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(cooldowns.trigger("a", first, 1, TimeUnit.HOURS));
        assertFalse(cooldowns.isCooling("b", first));
        assertFalse(cooldowns.isCooling("a", second));

        cooldowns.clear(first);
        assertFalse(cooldowns.isCooling("a", first));
        assertEquals(0, cooldowns.size());
    }

    @Test
    void setClearAndPrune() {
        long[] now = {1_000_000_000L};
        Cooldowns cooldowns = new Cooldowns(null, () -> now[0]);
        UUID player = UUID.randomUUID();

        cooldowns.set("teleport", player, 5, TimeUnit.SECONDS);
        assertTrue(cooldowns.isCooling("teleport", player));
        cooldowns.clearAction("teleport");
        assertFalse(cooldowns.isCooling("teleport", player));

        cooldowns.set("x", player, 1, TimeUnit.SECONDS);
        cooldowns.set("y", player, 2, TimeUnit.SECONDS);
        now[0] += TimeUnit.SECONDS.toNanos(2);
        assertEquals(2, cooldowns.prune());
        assertEquals(0, cooldowns.size());
    }

    @Test
    void invalidInputIsIgnored() {
        Cooldowns cooldowns = new Cooldowns(null, System::nanoTime);
        UUID player = UUID.randomUUID();
        assertFalse(cooldowns.trigger(null, player, 1, TimeUnit.SECONDS));
        assertFalse(cooldowns.trigger(" ", player, 1, TimeUnit.SECONDS));
        assertFalse(cooldowns.trigger("a", (UUID) null, 1, TimeUnit.SECONDS));
        assertEquals(0L, cooldowns.remainingMillis(null, player));
        assertEquals(0, cooldowns.size());
    }
}
