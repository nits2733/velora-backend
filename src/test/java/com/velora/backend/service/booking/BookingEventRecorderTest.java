package com.velora.backend.service.booking;

import com.velora.backend.entity.booking.Booking;
import com.velora.backend.entity.booking.BookingStatus;
import com.velora.backend.entity.booking.BookingTimelineEvent;
import com.velora.backend.entity.notification.NotificationType;
import com.velora.backend.entity.user.Role;
import com.velora.backend.entity.user.User;
import com.velora.backend.repository.booking.BookingTimelineEventRepository;
import com.velora.backend.service.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BookingEventRecorderTest {

    @Mock
    private BookingTimelineEventRepository timelineEventRepository;
    @Mock
    private NotificationService notificationService;

    private BookingEventRecorder recorder;
    private Booking booking;

    @BeforeEach
    void setUp() {
        recorder = new BookingEventRecorder(timelineEventRepository, notificationService);
        User customer = User.builder().id(1L).fullName("Cust").role(Role.CUSTOMER).build();
        booking = Booking.builder().id(10L).customer(customer).status(BookingStatus.PENDING).build();
    }

    private String timelineNote(BookingStatus from, BookingStatus to) {
        recorder.recordStatusChanged(booking, from, to);
        ArgumentCaptor<BookingTimelineEvent> captor = ArgumentCaptor.forClass(BookingTimelineEvent.class);
        verify(timelineEventRepository).save(captor.capture());
        return captor.getValue().getNote();
    }

    private String notificationBody(BookingStatus from, BookingStatus to) {
        recorder.recordStatusChanged(booking, from, to);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(eq(booking.getCustomer()), eq(NotificationType.BOOKING_STATUS_CHANGED),
                eq("Booking update"), body.capture(), eq(10L));
        return body.getValue();
    }

    @Test
    void confirmedReadsAsPlainEnglishInTheTimelineAndTheNotification() {
        assertThat(timelineNote(BookingStatus.PENDING, BookingStatus.CONFIRMED)).isEqualTo("Professional confirmed");
    }

    @Test
    void confirmedNotificationNamesNoRawEnum() {
        assertThat(notificationBody(BookingStatus.PENDING, BookingStatus.CONFIRMED))
                .isEqualTo("Your professional has confirmed your booking.");
    }

    @Test
    void completedNotificationNamesNoRawEnum() {
        assertThat(notificationBody(BookingStatus.CONFIRMED, BookingStatus.COMPLETED))
                .isEqualTo("Your booking has been marked as completed.");
    }

    @Test
    void aProfessionalCancellationDoesNotClaimWhoCancelled() {
        assertThat(notificationBody(BookingStatus.CONFIRMED, BookingStatus.CANCELLED))
                .isEqualTo("Your booking has been cancelled.");
    }
}
