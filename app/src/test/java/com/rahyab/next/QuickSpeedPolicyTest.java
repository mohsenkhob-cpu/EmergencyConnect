package com.rahyab.next;

import org.junit.Test;
import static org.junit.Assert.*;

public class QuickSpeedPolicyTest {
    @Test public void observedUsableAetherSpeedsDoNotStartReplacementSearch() {
        QuickSpeedPolicy policy = new QuickSpeedPolicy();
        assertEquals(QuickSpeedPolicy.Decision.ACCEPT, policy.measure("Aether", 1202, 1));
        assertEquals(QuickSpeedPolicy.Decision.ACCEPT, policy.measure("Aether", 1292, 2));
        assertFalse(policy.comparing());
    }

    @Test public void oneSlowOrUnavailableSampleDoesNotSwitchWorkingEngine() {
        QuickSpeedPolicy policy = new QuickSpeedPolicy();
        assertEquals(QuickSpeedPolicy.Decision.ACCEPT, policy.measure("Fluxcore", -1, 1));
        assertEquals(QuickSpeedPolicy.Decision.RECHECK, policy.measure("Fluxcore", 190, 2));
        assertEquals(QuickSpeedPolicy.Decision.ACCEPT, policy.measure("Fluxcore", 1550, 3));
        assertFalse(policy.comparing());
    }

    @Test public void keepsBestOfSlowAlternativesAndAvoidsRepeatedSwitches() {
        QuickSpeedPolicy policy = new QuickSpeedPolicy();
        assertEquals(QuickSpeedPolicy.Decision.RECHECK, policy.measure("Fluxcore", 350, 1));
        assertEquals(QuickSpeedPolicy.Decision.SWITCH, policy.measure("Fluxcore", 390, 2));
        policy.engineChanged();
        assertEquals(QuickSpeedPolicy.Decision.RECHECK, policy.measure("Aether", 160, 3));
        assertEquals(QuickSpeedPolicy.Decision.SWITCH, policy.measure("Aether", 180, 4));
        assertEquals("Fluxcore", policy.restoreBest(5));
        assertNull(policy.bestEngine());
        assertFalse(policy.canMeasure(6));
        assertTrue(policy.canMeasure(900_005));
    }

    @Test public void fastReplacementEndsComparison() {
        QuickSpeedPolicy policy = new QuickSpeedPolicy();
        policy.measure("Fluxcore", 150, 1);
        policy.measure("Fluxcore", 200, 2);
        policy.engineChanged();
        assertEquals(QuickSpeedPolicy.Decision.ACCEPT, policy.measure("Aether", 1789, 3));
        assertFalse(policy.comparing());
    }

    @Test public void missingAlternativeSamplesMayResumeComparisonButNeverBecomeBest() {
        QuickSpeedPolicy policy = new QuickSpeedPolicy();
        policy.measure("Fluxcore", 200, 1);
        policy.measure("Fluxcore", 300, 2);
        policy.engineChanged();
        assertEquals(QuickSpeedPolicy.Decision.RECHECK, policy.measure("Aether", -1, 3));
        assertEquals(QuickSpeedPolicy.Decision.SWITCH, policy.measure("Aether", -1, 4));
        assertEquals("Fluxcore", policy.bestEngine());
    }
}
