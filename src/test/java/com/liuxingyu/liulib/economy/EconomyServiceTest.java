package com.liuxingyu.liulib.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyServiceTest {

    @Test
    void unavailableWithoutVaultIsFailSoft() {
        EconomyService economy = new EconomyService(null);
        assertFalse(economy.available());
        assertEquals(0.0D, economy.balance(null));
        assertFalse(economy.has(null, 10));
        assertFalse(economy.withdraw(null, 10).success());
        assertFalse(economy.deposit(null, 10).success());
        assertEquals("货币", economy.currencyNameSingular());
        assertEquals("货币", economy.currencyNamePlural());
        assertEquals("1.50", economy.format(1.5));
    }

    @Test
    void rejectsInvalidAmounts() {
        EconomyService economy = new EconomyService(null);
        assertFalse(economy.has(null, -1));
        EconomyService.Result result = economy.withdraw(null, Double.NaN);
        assertFalse(result.success());
        assertTrue(result.error().contains("金额") || result.error().contains("玩家"));
    }
}
