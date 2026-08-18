package com.velora.backend.service;

import com.velora.backend.dto.booking.BookingRequest;
import com.velora.backend.dto.booking.BookingResponse;
import com.velora.backend.entity.Booking;
import com.velora.backend.entity.BookingStatus;
import com.velora.backend.entity.Category;
import com.velora.backend.entity.PortfolioItem;
import com.velora.backend.entity.RequestType;
import com.velora.backend.entity.Role;
import com.velora.backend.entity.ServiceGroup;
import com.velora.backend.entity.User;
import com.velora.backend.exception.InvalidStateTransitionException;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.exception.UnauthorizedActionException;
import com.velora.backend.mapper.BookingMapper;
import com.velora.backend.repository.BookingRepository;
import com.velora.backend.repository.CategoryRepository;
import com.velora.backend.repository.PortfolioItemRepository;
import com.velora.backend.repository.UserRepository;
import com.velora.backend.util.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class BookingService {

    private static final Set<BookingStatus> CANCELLABLE_STATUSES =
            Set.of(BookingStatus.PENDING_ASSIGNMENT, BookingStatus.PENDING, BookingStatus.CONFIRMED);

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final PortfolioItemRepository portfolioItemRepository;
    private final CategoryRepository categoryRepository;
    private final BookingMapper bookingMapper;

    @Transactional
    public BookingResponse createBooking(Long customerId, BookingRequest request) {
        User customer = userRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + customerId));

        if (request.portfolioItemId() != null && request.professionalId() == null) {
            throw new IllegalArgumentException("portfolioItemId requires an explicit professionalId");
        }

        if (request.requestType() == RequestType.INDIVIDUAL_SERVICE) {
            if (request.professionalId() != null) {
                throw new IllegalArgumentException(
                        "Individual service requests are assigned by Velora, not chosen directly");
            }
            if (request.categoryId() == null) {
                throw new IllegalArgumentException("Individual service requests must specify a category");
            }
        }

        User professional = null;
        PortfolioItem portfolioItem = null;

        if (request.professionalId() != null) {
            professional = userRepository.findById(request.professionalId())
                    .orElseThrow(() -> new ResourceNotFoundException("Professional not found: " + request.professionalId()));

            if (professional.getRole() != Role.PROFESSIONAL) {
                throw new IllegalArgumentException("Selected user is not a professional");
            }

            if (request.portfolioItemId() != null) {
                portfolioItem = portfolioItemRepository.findById(request.portfolioItemId())
                        .orElseThrow(() -> new ResourceNotFoundException("Portfolio item not found: " + request.portfolioItemId()));

                if (!portfolioItem.getProfessional().getId().equals(professional.getId())) {
                    throw new IllegalArgumentException("The selected portfolio item does not belong to the selected professional");
                }
            }
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
                .professional(professional)
                .portfolioItem(portfolioItem)
                .category(category)
                .requestType(request.requestType())
                .preferredStyle(request.preferredStyle())
                .budget(request.budget())
                .location(request.location())
                .scheduledAt(request.scheduledAt())
                .status(professional != null ? BookingStatus.PENDING : BookingStatus.PENDING_ASSIGNMENT)
                .notes(request.notes())
                .build();

        return bookingMapper.toResponse(bookingRepository.save(booking));
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
        return bookingMapper.toResponse(bookingRepository.save(booking));
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

        booking.setStatus(BookingStatus.CANCELLED);
        return bookingMapper.toResponse(bookingRepository.save(booking));
    }

    @Transactional
    public BookingResponse updateStatus(Long professionalId, Long bookingId, BookingStatus newStatus) {
        Booking booking = findBooking(bookingId);

        if (booking.getProfessional() == null || !booking.getProfessional().getId().equals(professionalId)) {
            throw new UnauthorizedActionException("Only the assigned professional can update this booking's status");
        }

        validateTransition(booking.getStatus(), newStatus);

        booking.setStatus(newStatus);
        return bookingMapper.toResponse(bookingRepository.save(booking));
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

    private Booking findBooking(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
    }
}
