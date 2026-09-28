package dev.iyanz.sourbycraft.update;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class SourbyUpdaterLifecycleTest {

    @Test
    void stopCancelsOnlyItsOwnChecksOnce() {
        SourbyUpdater updater = new SourbyUpdater();
        ScheduledTask daily = mock(ScheduledTask.class);
        ScheduledTask interval = mock(ScheduledTask.class);
        ScheduledTask unrelated = mock(ScheduledTask.class);

        updater.track(daily);
        updater.track(interval);
        updater.stop();
        updater.stop();

        verify(daily).cancel();
        verify(interval).cancel();
        verifyNoMoreInteractions(daily, interval);
        verifyNoInteractions(unrelated);
    }
}
