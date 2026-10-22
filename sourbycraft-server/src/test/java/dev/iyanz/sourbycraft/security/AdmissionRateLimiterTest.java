package dev.iyanz.sourbycraft.security;

import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class AdmissionRateLimiterTest {
    private static byte[] address(int n) { return new byte[]{10, (byte)(n >>> 8), (byte)n, 1}; }

    @Test void connectionAndLoginWindowsAreIndependentAndAggregateAcrossAddresses() {
        var rates = new AdmissionRateLimiter(10);
        assertEquals(1, rates.record(address(1), false, 0).global());
        var second = rates.record(address(2), false, 0);
        assertEquals(2, second.global());
        assertEquals(1, second.address());
        var login = rates.record(address(1), true, 0);
        assertEquals(1, login.global());
        assertEquals(1, login.address());
        assertEquals(2, rates.record(address(1), false, 0).address());
        assertEquals(1, rates.record(address(1), false, 1_000_000_000L).global());
    }

    @Test void addressStorageIsBoundedAndUnknownPeersSkipAddressBudgetUntilSpaceExpires() {
        var rates = new AdmissionRateLimiter(2);
        rates.record(address(1), false, 0);
        rates.record(address(2), false, 0);
        assertEquals(-1, rates.record(address(3), false, 0).address());
        assertEquals(-1, rates.record(address(4), false, 0).address());
        assertEquals(2, rates.trackedAddresses());
        assertEquals(2, rates.record(address(1), false, 0).address()); // existing peer retains its budget
        assertEquals(1, rates.record(address(3), false, 1_000_000_000L).address());
        assertEquals(2, rates.trackedAddresses());
    }

    @Test void rotatingAddressesCannotGrowTheTableOrBypassGlobalCounting() {
        var rates = new AdmissionRateLimiter(2);
        rates.record(address(1), true, 0);
        rates.record(address(2), true, 0);
        AdmissionRateLimiter.Counts last = null;
        for (int i = 3; i < 4099; i++) last = rates.record(address(i), true, 0);
        assertEquals(2, rates.trackedAddresses());
        assertEquals(-1, last.address());
        assertEquals(4098, last.global());
    }

    @Test void delayedPriorWindowEventsCannotReplenishCurrentGlobalOrPeerBudget() {
        for (boolean login : new boolean[]{false, true}) {
            var rates = new AdmissionRateLimiter(2);
            assertEquals(new AdmissionRateLimiter.Counts(1, 1), rates.record(address(1), login, 1_000_000_000L));
            assertEquals(new AdmissionRateLimiter.Counts(2, 2), rates.record(address(1), login, 1_000_000_001L));
            assertEquals(new AdmissionRateLimiter.Counts(3, 3), rates.record(address(1), login, 999_999_999L));
            assertEquals(new AdmissionRateLimiter.Counts(4, 4), rates.record(address(1), login, 1_000_000_002L));
        }
    }

    @Test void delayedUnknownPeerCannotEvictActiveAddressOrReplenishGlobalBudget() {
        var rates = new AdmissionRateLimiter(2);
        rates.record(address(1), false, 1_000_000_000L);
        rates.record(address(2), false, 1_000_000_001L);
        assertEquals(new AdmissionRateLimiter.Counts(3, -1), rates.record(address(3), false, 1_000_000_002L));
        // The older sample belongs to an unknown peer: it must remain untracked,
        // rather than treating the full table's active eldest entry as expired.
        assertEquals(new AdmissionRateLimiter.Counts(4, -1), rates.record(address(4), false, 999_999_999L));
        assertEquals(new AdmissionRateLimiter.Counts(5, 2), rates.record(address(1), false, 1_000_000_003L));
        assertEquals(new AdmissionRateLimiter.Counts(6, -1), rates.record(address(5), false, 1_000_000_004L));
        assertEquals(2, rates.trackedAddresses());
    }

    @Test void sharedLatestEpochAppliesAcrossConnectionAndLoginEvents() {
        var rates = new AdmissionRateLimiter(2);
        rates.record(address(1), false, 0);
        rates.record(address(1), true, 1_000_000_000L);
        assertEquals(new AdmissionRateLimiter.Counts(1, 1), rates.record(address(1), false, 999_999_999L));
        assertEquals(new AdmissionRateLimiter.Counts(2, 2), rates.record(address(1), false, 1_000_000_001L));
        assertEquals(new AdmissionRateLimiter.Counts(2, 2), rates.record(address(1), true, 1_000_000_002L));
    }

    @Test void activeOldestPeerDoesNotPinExpiredEntries() {
        var rates = new AdmissionRateLimiter(2);
        rates.record(address(1), false, 0);
        rates.record(address(2), false, 0);
        rates.record(address(1), false, 1_000_000_000L); // recent access moves A behind expired B
        assertEquals(new AdmissionRateLimiter.Counts(2, 1), rates.record(address(3), false, 1_000_000_001L));
        assertEquals(new AdmissionRateLimiter.Counts(3, 2), rates.record(address(1), false, 1_000_000_002L));
        assertEquals(2, rates.trackedAddresses());
    }

    /**
     * reviewer-opus-2 M1: with insertion order, one peer that connects first and then keeps
     * connecting once per second pinned the eldest slot, so after the table filled every new
     * address went to the shared overflow bucket forever. Access order must let expired entries go.
     */
    @Test void oneActiveOldestPeerCannotPushEveryNewAddressIntoOverflow() {
        int capacity = 64;
        var rates = new AdmissionRateLimiter(capacity);
        long second = 1_000_000_000L;
        rates.record(address(0), false, 0); // attacker A connects first
        for (int i = 1; i < capacity; i++) rates.record(address(i), false, 0); // table now full
        int tracked = 0, untracked = 0;
        for (int t = 1; t <= 30; t++) {
            rates.record(address(0), false, t * second); // A stays active every second
            for (int j = 0; j < 30; j++) {
                var counts = rates.record(address(1000 + t * 30 + j), false, t * second + 1 + j);
                if (counts.address() >= 0) tracked++; else untracked++;
            }
        }
        // Each second only A plus that second's new peers are active; the table holds 64, so all 900 get tracked.
        assertEquals(900, tracked);
        assertEquals(0, untracked);
        assertEquals(capacity, rates.trackedAddresses());
    }

    @Test void perAddressOnlyObservationDoesNotCountGlobally() {
        var rates = new AdmissionRateLimiter(4);
        assertEquals(new AdmissionRateLimiter.Counts(1, -1), rates.record(address(1), true, 0, 64, true, true, 1, true));
        assertEquals(new AdmissionRateLimiter.Counts(-1, 1), rates.record(address(2), true, 0, 64, false, false, 1, true));
        assertEquals(new AdmissionRateLimiter.Counts(-1, 2), rates.record(address(2), true, 0, 64, false, false, 1, true));
        assertEquals(new AdmissionRateLimiter.Counts(2, -1), rates.record(address(1), true, 0, 64, true, true, 1, true));
    }

    @Test void saturatedActiveTableDoesNotAttributeUnrelatedPeersToOneAddress() {
        var rates = new AdmissionRateLimiter(1);
        assertEquals(new AdmissionRateLimiter.Counts(1, 1), rates.record(address(1), false, 0, 64, false, 1, true));
        assertEquals(new AdmissionRateLimiter.Counts(-1, 2), rates.record(address(1), false, 0, 64, false, 1, true));
        assertEquals(new AdmissionRateLimiter.Counts(2, -1), rates.record(address(2), false, 0, 64, false, 1, true));
        assertEquals(new AdmissionRateLimiter.Counts(3, -1), rates.record(address(3), false, 0, 64, false, 1, true));
        assertEquals(1, rates.trackedAddresses());
    }

    @Test void ipv6PrefixCanCrossTheLongBoundaryWithoutMergingOtherPrefixes() {
        var rates = new AdmissionRateLimiter(8);
        byte[] first = new byte[16]; first[0] = 0x20;
        byte[] same = first.clone(); same[8] = 0x01; same[15] = 2;
        byte[] different = first.clone(); different[8] = (byte)0x80;
        assertEquals(1, rates.record(first, false, 0, 65, false, 1, false).address());
        assertEquals(2, rates.record(same, false, 0, 65, false, 1, false).address());
        assertEquals(1, rates.record(different, false, 0, 65, false, 1, false).address());
        assertEquals("2000:0:0:0:0:0:0:0/65", AdmissionRateLimiter.addressLabel(same, 65));
    }

    @Test void ipv4MappedIpv6SharesTheIpv4BudgetAndKeysDoNotRetainMutableInput() {
        var rates = new AdmissionRateLimiter(10);
        byte[] ipv4 = new byte[]{127, 0, 0, 1};
        assertEquals(1, rates.record(ipv4, false, 0).address());
        ipv4[0] = 10;
        byte[] mapped = new byte[16];
        mapped[10] = mapped[11] = (byte)255;
        mapped[12] = 127; mapped[15] = 1;
        assertEquals(2, rates.record(mapped, false, 0).address());
        assertEquals(1, rates.trackedAddresses());
        assertThrows(IllegalArgumentException.class, () -> rates.record(new byte[3], false, 0));
    }

    @Test void concurrentNettyLoopsDoNotLoseGlobalOrAddressCounts() throws Exception {
        var rates = new AdmissionRateLimiter(16);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var work = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            var start = new java.util.concurrent.CyclicBarrier(4);
            for (int i = 0; i < 4; i++) work.add(pool.submit(() -> {
                start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                for (int j = 0; j < 100; j++) rates.record(address(1), false, 0);
                return null;
            }));
            for (var future : work) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        var result = rates.record(address(1), false, 0);
        assertEquals(401, result.global());
        assertEquals(401, result.address());
    }
}
