package com.velora.backend.scheduler;

import com.velora.backend.config.VisionProperties;
import com.velora.backend.entity.vision.VisionJob;
import com.velora.backend.entity.vision.VisionJobStatus;
import com.velora.backend.repository.vision.VisionJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisionJobSweeperTest {

    private static final String SAFE_ERROR = "We couldn't generate your design right now. Please try again.";

    @Mock private VisionJobRepository visionJobRepository;

    private VisionJobSweeper sweeper;

    @BeforeEach
    void setUp() {
        VisionProperties properties = new VisionProperties();
        properties.setStaleAfter(Duration.ofMinutes(10));
        sweeper = new VisionJobSweeper(visionJobRepository, properties);
    }

    private VisionJob stale() {
        return VisionJob.builder().id(UUID.randomUUID()).status(VisionJobStatus.PROCESSING)
                .createdAt(Instant.now().minus(Duration.ofMinutes(30))).build();
    }

    @Test
    void failStaleJobs_usesCutoffOfNowMinusStaleAfter() {
        when(visionJobRepository.findByStatusAndCreatedAtBefore(eq(VisionJobStatus.PROCESSING), any()))
                .thenReturn(List.of());
        Instant before = Instant.now();

        sweeper.failStaleJobs();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(visionJobRepository).findByStatusAndCreatedAtBefore(eq(VisionJobStatus.PROCESSING), cutoff.capture());
        assertThat(cutoff.getValue()).isBetween(
                before.minus(Duration.ofMinutes(10)).minusSeconds(5),
                Instant.now().minus(Duration.ofMinutes(10)).plusSeconds(5));
    }

    @Test
    void failStaleJobs_marksEachStaleJobFailedWithSafeMessageAndSavesIt() {
        VisionJob a = stale();
        VisionJob b = stale();
        when(visionJobRepository.findByStatusAndCreatedAtBefore(eq(VisionJobStatus.PROCESSING), any()))
                .thenReturn(List.of(a, b));

        sweeper.failStaleJobs();

        for (VisionJob j : List.of(a, b)) {
            assertThat(j.getStatus()).isEqualTo(VisionJobStatus.FAILED);
            assertThat(j.getError()).isEqualTo(SAFE_ERROR);
            assertThat(j.getCompletedAt()).isNotNull();
            verify(visionJobRepository).save(j);
        }
    }

    @Test
    void failStaleJobs_optimisticLockFailureOnOneJob_skipsItAndContinues() {
        VisionJob a = stale();
        VisionJob b = stale();
        when(visionJobRepository.findByStatusAndCreatedAtBefore(eq(VisionJobStatus.PROCESSING), any()))
                .thenReturn(List.of(a, b));
        when(visionJobRepository.save(a))
                .thenThrow(new ObjectOptimisticLockingFailureException(VisionJob.class, a.getId()));

        sweeper.failStaleJobs();

        verify(visionJobRepository).save(b);
        assertThat(b.getStatus()).isEqualTo(VisionJobStatus.FAILED);
    }

    @Test
    void failStaleJobs_noStaleJobs_savesNothing() {
        when(visionJobRepository.findByStatusAndCreatedAtBefore(eq(VisionJobStatus.PROCESSING), any()))
                .thenReturn(List.of());

        sweeper.failStaleJobs();

        verify(visionJobRepository, never()).save(any());
    }
}
