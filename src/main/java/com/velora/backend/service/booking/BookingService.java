package com.velora.backend.service.booking;

import com.velora.backend.dto.booking.BookingCountsResponse;
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
import com.velora.backend.entity.quotation.Quotation;
import com.velora.backend.entity.quotation.QuotationStatus;
import com.velora.backend.entity.user.User;
import com.velora.backend.exception.InvalidStateTransitionException;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.mapper.booking.BookingMapper;
import com.velora.backend.repository.booking.BookingInspirationImageRepository;
import com.velora.backend.repository.booking.BookingRepository;
import com.velora.backend.repository.booking.BookingTimelineEventRepository;
import com.velora.backend.repository.portfolio.CategoryRepository;
import com.velora.backend.repository.quotation.QuotationRepository;
import com.velora.backend.repository.review.ReviewRepository;
import com.velora.backend.repository.user.UserRepository;
import com.velora.backend.util.PageResponse;
import com.velora.backend.util.Text;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class BookingService {

    private static final Set<BookingStatus> CANCELLABLE_STATUSES =
            Set.of(BookingStatus.PENDING_ASSIGNMENT, BookingStatus.PENDING, BookingStatus.CONFIRMED);

    private static final Set<BookingStatus> EDITABLE_STATUSES =
            Set.of(BookingStatus.PENDING_ASSIGNMENT, BookingStatus.PENDING, BookingStatus.CONFIRMED);

    /** Everything that is not finished: what the customer sees under "Active". */
    private static final Set<BookingStatus> ACTIVE_STATUSES =
            Set.of(BookingStatus.PENDING_ASSIGNMENT, BookingStatus.PENDING, BookingStatus.CONFIRMED);

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final BookingInspirationImageRepository inspirationImageRepository;
    private final BookingTimelineEventRepository timelineEventRepository;
    private final BookingMapper bookingMapper;
    private final BookingEventRecorder eventRecorder;
    private final QuotationRepository quotationRepository;
    private final ReviewRepository reviewRepository;

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
            if (category.getRoot().getServiceGroup() != expectedGroup) {
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

        return toResponse(booking, customerId);
    }

    @Transactional
    public BookingResponse addInspirationImage(Long customerId, Long bookingId, String imageUrl) {
        Booking booking = findBooking(bookingId);

        if (!booking.getCustomer().getId().equals(customerId)) {
            throw bookingNotFound(bookingId);
        }
        if (!EDITABLE_STATUSES.contains(booking.getStatus())) {
            throw new InvalidStateTransitionException(
                    "Cannot add inspiration images to a booking with status " + booking.getStatus());
        }

        inspirationImageRepository.save(BookingInspirationImage.builder()
                .booking(booking)
                .imageUrl(imageUrl)
                .build());

        return toResponse(findBooking(bookingId), customerId);
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
        return toResponse(booking, null);
    }

    /**
     * @param statuses     optional filter; null or empty means every status
     * @param actionNeeded customers only: keep just the active bookings whose quotation is waiting
     *                     for their answer (combined with {@code statuses} when both are given)
     */
    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> getBookingsForUser(Long userId, Role role, List<BookingStatus> statuses,
                                                            boolean actionNeeded, Pageable pageable) {
        boolean filtered = statuses != null && !statuses.isEmpty();
        Page<Booking> bookings;
        if (role == Role.PROFESSIONAL) {
            if (actionNeeded) {
                throw new IllegalArgumentException("actionNeeded is only available to customers");
            }
            bookings = filtered
                    ? bookingRepository.findByProfessionalIdAndStatusIn(userId, statuses, pageable)
                    : bookingRepository.findByProfessionalId(userId, pageable);
        } else if (actionNeeded) {
            Set<BookingStatus> active = new HashSet<>(ACTIVE_STATUSES);
            if (filtered) {
                active.retainAll(statuses);
            }
            bookings = active.isEmpty()
                    ? Page.empty(pageable)
                    : bookingRepository.findAwaitingQuotationResponse(userId, active, QuotationStatus.SENT, pageable);
        } else {
            bookings = filtered
                    ? bookingRepository.findByCustomerIdAndStatusIn(userId, statuses, pageable)
                    : bookingRepository.findByCustomerId(userId, pageable);
        }

        return PageResponse.from(new PageImpl<>(
                toResponses(bookings.getContent(), userId), pageable, bookings.getTotalElements()));
    }

    @Transactional(readOnly = true)
    public BookingCountsResponse getCounts(Long userId, Role role) {
        boolean professional = role == Role.PROFESSIONAL;
        List<BookingRepository.StatusCount> rows = professional
                ? bookingRepository.countByProfessionalGroupedByStatus(userId)
                : bookingRepository.countByCustomerGroupedByStatus(userId);

        Map<BookingStatus, Long> byStatus = new EnumMap<>(BookingStatus.class);
        for (BookingStatus status : BookingStatus.values()) {
            byStatus.put(status, 0L);
        }
        rows.forEach(row -> byStatus.put(row.getStatus(), row.getTotal()));

        long awaiting = professional
                ? 0
                : quotationRepository.countForCustomer(userId, QuotationStatus.SENT, ACTIVE_STATUSES);
        return new BookingCountsResponse(byStatus, awaiting);
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
        return PageResponse.from(new PageImpl<>(
                toResponses(bookings.getContent(), null), pageable, bookings.getTotalElements()));
    }

    @Transactional(readOnly = true)
    public BookingResponse getById(Long userId, Role role, Long bookingId) {
        Booking booking = findBooking(bookingId);
        assertParticipantOrAdmin(userId, role, booking);
        return toResponse(booking, userId);
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
    public BookingResponse cancel(Long customerId, Long bookingId, String reason) {
        Booking booking = findBooking(bookingId);

        if (!booking.getCustomer().getId().equals(customerId)) {
            throw bookingNotFound(bookingId);
        }

        if (!CANCELLABLE_STATUSES.contains(booking.getStatus())) {
            throw new InvalidStateTransitionException(
                    "Cannot cancel a booking with status " + booking.getStatus());
        }

        BookingStatus previousStatus = booking.getStatus();
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancellationReason(Text.trimToNull(reason));
        booking = bookingRepository.save(booking);
        eventRecorder.recordCancelled(booking, previousStatus);
        return toResponse(booking, customerId);
    }

    @Transactional
    public BookingResponse updateStatus(Long professionalId, Long bookingId, BookingStatus newStatus) {
        Booking booking = findBooking(bookingId);

        if (booking.getProfessional() == null || !booking.getProfessional().getId().equals(professionalId)) {
            throw bookingNotFound(bookingId);
        }

        validateTransition(booking.getStatus(), newStatus);

        BookingStatus previousStatus = booking.getStatus();
        booking.setStatus(newStatus);
        booking = bookingRepository.save(booking);
        eventRecorder.recordStatusChanged(booking, previousStatus, newStatus);
        return toResponse(booking, professionalId);
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
            throw bookingNotFound(booking.getId());
        }
    }

    private BookingResponse toResponse(Booking booking, Long viewerId) {
        return toResponses(List.of(booking), viewerId).get(0);
    }

    /**
     * Maps a page of bookings with their quotation and review state fetched in two queries total,
     * not two per booking. {@code viewerId} is whoever is looking (null for the admin queue): a
     * customer does not see a {@code DRAFT} quotation, so it is reported as no quotation.
     */
    private List<BookingResponse> toResponses(List<Booking> bookings, Long viewerId) {
        if (bookings.isEmpty()) {
            return List.of();
        }
        List<Long> ids = bookings.stream().map(Booking::getId).toList();
        Map<Long, Quotation> quotations = new HashMap<>();
        quotationRepository.findByBookingIdIn(ids).forEach(q -> quotations.put(q.getBooking().getId(), q));
        Set<Long> reviewed = new HashSet<>(reviewRepository.findReviewedBookingIds(ids));

        return bookings.stream().map(booking -> {
            Quotation quotation = quotations.get(booking.getId());
            boolean hidden = quotation != null
                    && quotation.getStatus() == QuotationStatus.DRAFT
                    && booking.getCustomer().getId().equals(viewerId);
            boolean visible = quotation != null && !hidden;
            boolean viewerIsProfessional = booking.getProfessional() != null
                    && booking.getProfessional().getId().equals(viewerId);
            return bookingMapper.toResponse(booking,
                    visible ? quotation.getStatus() : null,
                    visible ? quotation.getTotalAmount() : null,
                    visible ? quotation.getValidUntil() : null,
                    reviewed.contains(booking.getId()),
                    viewerIsProfessional ? null : booking.getCancellationReason());
        }).toList();
    }

    /**
     * Also what a non-participant gets: a booking you have no part in is reported as missing, with
     * the same message, so sequential ids cannot be used to discover which bookings exist.
     */
    private ResourceNotFoundException bookingNotFound(Long bookingId) {
        return new ResourceNotFoundException("Booking not found: " + bookingId);
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
                .orElseThrow(() -> bookingNotFound(bookingId));
    }
}
