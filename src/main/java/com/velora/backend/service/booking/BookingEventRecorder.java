package com.velora.backend.service.booking;

import com.velora.backend.service.notification.NotificationService;

import com.velora.backend.entity.booking.Booking;
import com.velora.backend.entity.booking.BookingStatus;
import com.velora.backend.entity.booking.BookingTimelineEvent;
import com.velora.backend.entity.notification.NotificationType;
import com.velora.backend.entity.booking.TimelineEventType;
import com.velora.backend.entity.user.User;
import com.velora.backend.repository.booking.BookingTimelineEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Side-effect helper called from {@link BookingService} and {@link QuotationService}
 * at the exact points those services already mutate booking/quotation state. Writes
 * both a {@link BookingTimelineEvent} row (so the client can render a real status
 * timeline) and, where relevant, an in-app {@link com.velora.backend.entity.notification.Notification}
 * - one call site, both read-models, always kept in sync. No new transactional
 * boundary: every method here runs inside the caller's existing {@code @Transactional}
 * method.
 */
@Service
@RequiredArgsConstructor
public class BookingEventRecorder {

    private final BookingTimelineEventRepository timelineEventRepository;
    private final NotificationService notificationService;

    @Transactional
    public void recordSubmitted(Booking booking) {
        record(booking, TimelineEventType.SUBMITTED, null, booking.getStatus(), "Request submitted");
    }

    @Transactional
    public void recordAssigned(Booking booking) {
        record(booking, TimelineEventType.ASSIGNED, BookingStatus.PENDING_ASSIGNMENT, BookingStatus.PENDING,
                "Assigned to " + booking.getProfessional().getFullName());
        notificationService.create(booking.getCustomer(), NotificationType.BOOKING_ASSIGNED,
                "Professional assigned",
                booking.getProfessional().getFullName() + " has been assigned to your project.",
                booking.getId());
        notificationService.create(booking.getProfessional(), NotificationType.BOOKING_ASSIGNED,
                "New booking assigned",
                "You've been assigned a new booking from " + booking.getCustomer().getFullName() + ".",
                booking.getId());
    }

    @Transactional
    public void recordStatusChanged(Booking booking, BookingStatus from, BookingStatus to) {
        TimelineEventType eventType = to == BookingStatus.COMPLETED ? TimelineEventType.COMPLETED
                : TimelineEventType.STATUS_CHANGED;
        record(booking, eventType, from, to, "Status changed to " + to);
        notificationService.create(booking.getCustomer(), NotificationType.BOOKING_STATUS_CHANGED,
                "Booking update",
                "Your booking is now " + to + ".",
                booking.getId());
    }

    @Transactional
    public void recordCancelled(Booking booking, BookingStatus from) {
        record(booking, TimelineEventType.CANCELLED, from, BookingStatus.CANCELLED, "Booking cancelled");
        User professional = booking.getProfessional();
        if (professional != null) {
            notificationService.create(professional, NotificationType.BOOKING_CANCELLED,
                    "Booking cancelled",
                    "A booking assigned to you was cancelled by the customer.",
                    booking.getId());
        }
    }

    @Transactional
    public void recordQuotationSent(Booking booking) {
        record(booking, TimelineEventType.QUOTATION_SENT, booking.getStatus(), booking.getStatus(), "Quotation sent");
        notificationService.create(booking.getCustomer(), NotificationType.QUOTATION_SENT,
                "Quotation received",
                "You've received a quotation for your booking.",
                booking.getId());
    }

    @Transactional
    public void recordQuotationAccepted(Booking booking) {
        record(booking, TimelineEventType.QUOTATION_ACCEPTED, booking.getStatus(), booking.getStatus(), "Quotation accepted");
        notifyProfessional(booking, NotificationType.QUOTATION_ACCEPTED, "Quotation accepted",
                "The customer accepted your quotation.");
    }

    @Transactional
    public void recordQuotationRejected(Booking booking) {
        record(booking, TimelineEventType.QUOTATION_REJECTED, booking.getStatus(), booking.getStatus(), "Quotation rejected");
        notifyProfessional(booking, NotificationType.QUOTATION_REJECTED, "Quotation rejected",
                "The customer rejected your quotation.");
    }

    private void notifyProfessional(Booking booking, NotificationType type, String title, String body) {
        User professional = booking.getProfessional();
        if (professional != null) {
            notificationService.create(professional, type, title, body, booking.getId());
        }
    }

    private void record(Booking booking, TimelineEventType eventType, BookingStatus from, BookingStatus to, String note) {
        timelineEventRepository.save(BookingTimelineEvent.builder()
                .booking(booking)
                .eventType(eventType)
                .fromStatus(from)
                .toStatus(to)
                .note(note)
                .build());
    }
}
