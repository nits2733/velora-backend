package com.velora.backend.service.booking;

import com.velora.backend.dto.booking.BookingRequest;
import com.velora.backend.dto.booking.BookingResponse;
import com.velora.backend.dto.booking.BookingTimelineEventResponse;
import com.velora.backend.entity.booking.Booking;
import com.velora.backend.entity.booking.BookingInspirationImage;
import com.velora.backend.entity.booking.BookingStatus;
import com.velora.backend.entity.booking.BookingTimelineEvent;
import com.velora.backend.entity.portfolio.Category;
import com.velora.backend.entity.booking.RequestType;
import com.velora.backend.entity.user.Role;
import com.velora.backend.entity.portfolio.ServiceGroup;
import com.velora.backend.entity.user.User;
import com.velora.backend.exception.InvalidStateTransitionException;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.exception.UnauthorizedActionException;
import com.velora.backend.mapper.booking.BookingMapper;
import com.velora.backend.repository.booking.BookingInspirationImageRepository;
import com.velora.backend.repository.booking.BookingRepository;
import com.velora.backend.repository.booking.BookingTimelineEventRepository;
import com.velora.backend.repository.portfolio.CategoryRepository;
import com.velora.backend.repository.user.UserRepository;
import com.velora.backend.util.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BookingService {

    private static final Set<BookingStatus> CANCELLABLE_STATUSES =
            Set.of(BookingStatus.PENDING_ASSIGNMENT, BookingStatus.PENDING, BookingStatus.CONFIRMED);

    private static final Set<BookingStatus> EDITABLE_STATUSES =
            Set.of(BookingStatus.PENDING_ASSIGNMENT, BookingStatus.PENDING, BookingStatus.CONFIRMED);

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final BookingInspirationImageRepository inspirationImageRepository;
    private final BookingTimelineEventRepository timelineEventRepository;
    private final BookingMapper bookingMapper;
    private final BookingEventRecorder eventRecorder;

    @Transactional
    public BookingResponse createBooking(Long customerId, BookingRequest request) {
        User customer = userRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + customerId));

        if (request.requestType() == RequestType.INDIVIDUAL_SERVICE && request.categoryId() == null) {
            throw new IllegalArgumentException("Individual service requests must specify a category");
        }

        if (request.budgetMin() != null && request.budgetMax() != null
                && request.budgetMin().compareTo(request.budgetMax()) > 0) {
            throw new IllegalArgumentException("budgetMin cannot be greater than budgetMax");
        }

        Category category = null;
        if (request.categoryId() != null) {
            category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + request.categoryId()));

            ServiceGroup expectedGroup = request.requestType() == RequestType.INDIVIDUAL_SERVICE
                    ? ServiceGroup.INDIVIDUAL_SERVICE
                    : ServiceGroup.HOME_PROJECT;
            if (category.getServiceGroup() != expectedGroup) {
                throw new IllegalArgumentException("Selected category does not match the request type");
            }
        }

        Booking booking = Booking.builder()
                .customer(customer)
                .category(category)
                .requestType(request.requestType())
                .preferredStyle(request.preferredStyle())
                .budgetMin(request.budgetMin())
                .budgetMax(request.budgetMax())
                .preferredTimeline(request.preferredTimeline())
                .location(request.location())
                .scheduledAt(request.scheduledAt())
                .status(BookingStatus.PENDING_ASSIGNMENT)
                .notes(request.notes())
                .build();

        booking = bookingRepository.save(booking);
        final Booking savedBooking = booking;

        if (request.inspirationImageUrls() != null && !request.inspirationImageUrls().isEmpty()) {
            List<BookingInspirationImage> images = request.inspirationImageUrls().stream()
                    .map(imageUrl -> inspirationImageRepository.save(BookingInspirationImage.builder()
                            .booking(savedBooking)
                            .imageUrl(imageUrl)
                            .build()))
                    .toList();
            booking.setInspirationImages(images);
        }

        eventRecorder.recordSubmitted(booking);

        return bookingMapper.toResponse(booking);
    }

    @Transactional
    public BookingResponse addInspirationImage(Long customerId, Long bookingId, String imageUrl) {
        Booking booking = findBooking(bookingId);

        if (!booking.getCustomer().getId().equals(customerId)) {
            throw new UnauthorizedActionException("Only the customer who made this booking can add images to it");
        }
        if (!EDITABLE_STATUSES.contains(booking.getStatus())) {
            throw new InvalidStateTransitionException(
                    "Cannot add inspiration images to a booking with status " + booking.getStatus());
        }

        inspirationImageRepository.save(BookingInspirationImage.builder()
                .booking(booking)
                .imageUrl(imageUrl)
                .build());

        return bookingMapper.toResponse(findBooking(bookingId));
    }

    @Transactional
    public BookingResponse assignProfessional(Long bookingId, Long professionalId) {
        Booking booking = findBooking(bookingId);

        if (booking.getProfessional() != null || booking.getStatus() != BookingStatus.PENDING_ASSIGNMENT) {
            throw new InvalidStateTransitionException("This booking is not awaiting professional assignment");
        }

        User professional = userRepository.findById(professionalId)
                .orElseThrow(() -> new ResourceNotFoundException("Professional not found: " + professionalId));

        if (professional.getRole() != Role.PROFESSIONAL) {
            throw new IllegalArgumentException("Selected user is not a professional");
        }

        booking.setProfessional(professional);
        booking.setStatus(BookingStatus.PENDING);
        booking = bookingRepository.save(booking);
        eventRecorder.recordAssigned(booking);
        return bookingMapper.toResponse(booking);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> getBookingsForUser(Long userId, Role role, Pageable pageable) {
        Page<Booking> bookings = role == Role.PROFESSIONAL
                ? bookingRepository.findByProfessionalId(userId, pageable)
                : bookingRepository.findByCustomerId(userId, pageable);

        return PageResponse.from(bookings.map(bookingMapper::toResponse));
    }

    /**
     * The admin work queue: every booking still waiting for someone to be assigned to it.
     * Deliberately not folded into {@link #getBookingsForUser} - that one answers "which
     * bookings are mine?", scoped to the caller, and an admin is a participant in none of
     * them. This answers "which bookings need me?", which is a different question with a
     * different audience.
     */
    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> getBookingsAwaitingAssignment(Pageable pageable) {
        Page<Booking> bookings = bookingRepository.findByStatus(BookingStatus.PENDING_ASSIGNMENT, pageable);
        return PageResponse.from(bookings.map(bookingMapper::toResponse));
    }

    @Transactional(readOnly = true)
    public BookingResponse getById(Long userId, Role role, Long bookingId) {
        Booking booking = findBooking(bookingId);
        assertParticipantOrAdmin(userId, role, booking);
        return bookingMapper.toResponse(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingTimelineEventResponse> getTimeline(Long userId, Role role, Long bookingId) {
        Booking booking = findBooking(bookingId);
        assertParticipantOrAdmin(userId, role, booking);
        return timelineEventRepository.findByBookingIdOrderByCreatedAtAsc(bookingId).stream()
                .map(this::toTimelineResponse)
                .toList();
    }

    @Transactional
    public BookingResponse cancel(Long customerId, Long bookingId) {
        Booking booking = findBooking(bookingId);

        if (!booking.getCustomer().getId().equals(customerId)) {
            throw new UnauthorizedActionException("Only the customer who made this booking can cancel it");
        }

        if (!CANCELLABLE_STATUSES.contains(booking.getStatus())) {
            throw new InvalidStateTransitionException(
                    "Cannot cancel a booking with status " + booking.getStatus());
        }

        BookingStatus previousStatus = booking.getStatus();
        booking.setStatus(BookingStatus.CANCELLED);
        booking = bookingRepository.save(booking);
        eventRecorder.recordCancelled(booking, previousStatus);
        return bookingMapper.toResponse(booking);
    }

    @Transactional
    public BookingResponse updateStatus(Long professionalId, Long bookingId, BookingStatus newStatus) {
        Booking booking = findBooking(bookingId);

        if (booking.getProfessional() == null || !booking.getProfessional().getId().equals(professionalId)) {
            throw new UnauthorizedActionException("Only the assigned professional can update this booking's status");
        }

        validateTransition(booking.getStatus(), newStatus);

        BookingStatus previousStatus = booking.getStatus();
        booking.setStatus(newStatus);
        booking = bookingRepository.save(booking);
        eventRecorder.recordStatusChanged(booking, previousStatus, newStatus);
        return bookingMapper.toResponse(booking);
    }

    private void validateTransition(BookingStatus current, BookingStatus next) {
        boolean allowed = switch (current) {
            case PENDING -> next == BookingStatus.CONFIRMED || next == BookingStatus.CANCELLED;
            case CONFIRMED -> next == BookingStatus.COMPLETED || next == BookingStatus.CANCELLED;
            case PENDING_ASSIGNMENT, CANCELLED, COMPLETED -> false;
        };

        if (!allowed) {
            throw new InvalidStateTransitionException(
                    "Cannot transition booking from " + current + " to " + next);
        }
    }

    private void assertParticipantOrAdmin(Long userId, Role role, Booking booking) {
        if (role == Role.ADMIN) {
            return;
        }
        boolean isParticipant = booking.getCustomer().getId().equals(userId)
                || (booking.getProfessional() != null && booking.getProfessional().getId().equals(userId));
        if (!isParticipant) {
            throw new UnauthorizedActionException("You do not have access to this booking");
        }
    }

    private BookingTimelineEventResponse toTimelineResponse(BookingTimelineEvent event) {
        return new BookingTimelineEventResponse(
                event.getId(),
                event.getEventType(),
                event.getFromStatus(),
                event.getToStatus(),
                event.getNote(),
                event.getCreatedAt()
        );
    }

    private Booking findBooking(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
    }
}
