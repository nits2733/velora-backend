package com.velora.backend.service.booking;

import com.velora.backend.dto.booking.BookingCountsResponse;
import com.velora.backend.dto.booking.BookingRequest;
import com.velora.backend.dto.booking.BookingResponse;
import com.velora.backend.entity.professional.AvailabilityStatus;
import com.velora.backend.entity.booking.Booking;
import com.velora.backend.entity.booking.BookingStatus;
import com.velora.backend.entity.portfolio.Category;
import com.velora.backend.entity.professional.ProfessionalProfile;
import com.velora.backend.entity.quotation.Quotation;
import com.velora.backend.entity.quotation.QuotationStatus;
import com.velora.backend.entity.booking.RequestType;
import com.velora.backend.entity.user.Role;
import com.velora.backend.entity.portfolio.ServiceGroup;
import com.velora.backend.entity.user.User;
import com.velora.backend.exception.InvalidStateTransitionException;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.mapper.booking.BookingMapper;
import com.velora.backend.mapper.portfolio.PortfolioItemMapper;
import com.velora.backend.mapper.professional.ProfessionalMapper;
import com.velora.backend.repository.booking.BookingInspirationImageRepository;
import com.velora.backend.repository.booking.BookingRepository;
import com.velora.backend.repository.booking.BookingTimelineEventRepository;
import com.velora.backend.repository.portfolio.CategoryRepository;
import com.velora.backend.repository.quotation.QuotationRepository;
import com.velora.backend.repository.review.ReviewRepository;
import com.velora.backend.repository.user.UserRepository;
import com.velora.backend.util.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private BookingInspirationImageRepository inspirationImageRepository;
    @Mock
    private BookingTimelineEventRepository timelineEventRepository;
    @Mock
    private BookingEventRecorder eventRecorder;
    @Mock
    private QuotationRepository quotationRepository;
    @Mock
    private ReviewRepository reviewRepository;

    private final BookingMapper bookingMapper = new BookingMapper(new ProfessionalMapper(), new PortfolioItemMapper());

    private BookingService bookingService;

    private User customer;
    private User professional;

    @BeforeEach
    void setUp() {
        bookingService = new BookingService(bookingRepository, userRepository,
                categoryRepository, inspirationImageRepository, timelineEventRepository, bookingMapper, eventRecorder,
                quotationRepository, reviewRepository);

        customer = User.builder().id(1L).email("customer@velora.test").fullName("Cust").role(Role.CUSTOMER).build();
        professional = User.builder().id(2L).email("professional@velora.test").fullName("Pro").role(Role.PROFESSIONAL).build();
        professional.setProfessionalProfile(ProfessionalProfile.builder()
                .id(1L).user(professional).availabilityStatus(AvailabilityStatus.AVAILABLE).ratingCount(0).build());
    }

    private BookingRequest requestFor(RequestType requestType, String notes, Long categoryId, String location) {
        return new BookingRequest(requestType,
                Instant.now().plus(1, ChronoUnit.DAYS), notes, categoryId, null, null, null, null, location, null);
    }

    @Test
    void cancelRejectsNonOwnerCustomer() {
        Booking booking = Booking.builder()
                .id(5L).customer(customer).professional(professional).requestType(RequestType.FULL_HOME_PROJECT)
                .status(BookingStatus.PENDING).scheduledAt(Instant.now()).build();
        when(bookingRepository.findById(5L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancel(999L, 5L, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Booking not found: 5");
        verify(bookingRepository, never()).save(any());
    }

    private Booking bookingOf5() {
        Booking booking = Booking.builder()
                .id(5L).customer(customer).professional(professional).requestType(RequestType.FULL_HOME_PROJECT)
                .status(BookingStatus.PENDING).scheduledAt(Instant.now()).build();
        when(bookingRepository.findById(5L)).thenReturn(Optional.of(booking));
        return booking;
    }

    @Test
    void getByIdHidesBookingFromNonParticipantWithTheSameErrorAsAMissingBooking() {
        bookingOf5();
        when(bookingRepository.findById(77L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.getById(999L, Role.CUSTOMER, 5L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Booking not found: 5");
        assertThatThrownBy(() -> bookingService.getById(999L, Role.CUSTOMER, 77L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Booking not found: 77");
    }

    @Test
    void getByIdStillReturnsTheBookingToItsCustomerProfessionalAndAdmin() {
        bookingOf5();

        assertThat(bookingService.getById(1L, Role.CUSTOMER, 5L).id()).isEqualTo(5L);
        assertThat(bookingService.getById(2L, Role.PROFESSIONAL, 5L).id()).isEqualTo(5L);
        assertThat(bookingService.getById(999L, Role.ADMIN, 5L).id()).isEqualTo(5L);
    }

    @Test
    void getTimelineHidesBookingFromNonParticipant() {
        bookingOf5();

        assertThatThrownBy(() -> bookingService.getTimeline(999L, Role.CUSTOMER, 5L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Booking not found: 5");
    }

    @Test
    void updateStatusHidesBookingFromAProfessionalWhoIsNotAssigned() {
        bookingOf5();

        assertThatThrownBy(() -> bookingService.updateStatus(999L, 5L, BookingStatus.CONFIRMED))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Booking not found: 5");
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void addInspirationImageHidesBookingFromNonOwner() {
        bookingOf5();

        assertThatThrownBy(() -> bookingService.addInspirationImage(999L, 5L, "https://img.test/a.jpg"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Booking not found: 5");
        verify(inspirationImageRepository, never()).save(any());
    }

    private Quotation quotationOn(Booking booking, QuotationStatus status, String total) {
        Quotation quotation = Quotation.builder()
                .id(90L).booking(booking).status(status).totalAmount(new BigDecimal(total)).build();
        when(quotationRepository.findByBookingIdIn(List.of(booking.getId()))).thenReturn(List.of(quotation));
        return quotation;
    }

    @Test
    void aSentQuotationShowsItsStatusAndTotalOnTheBooking() {
        Booking booking = bookingOf5();
        quotationOn(booking, QuotationStatus.SENT, "85000.00");

        BookingResponse response = bookingService.getById(1L, Role.CUSTOMER, 5L);

        assertThat(response.quotationStatus()).isEqualTo(QuotationStatus.SENT);
        assertThat(response.quotationTotal()).isEqualByComparingTo("85000");
    }

    @Test
    void aDraftQuotationIsHiddenFromTheCustomerButNotTheProfessional() {
        Booking booking = bookingOf5();
        quotationOn(booking, QuotationStatus.DRAFT, "1000");

        BookingResponse seenByCustomer = bookingService.getById(1L, Role.CUSTOMER, 5L);
        assertThat(seenByCustomer.quotationStatus()).isNull();
        assertThat(seenByCustomer.quotationTotal()).isNull();

        BookingResponse seenByProfessional = bookingService.getById(2L, Role.PROFESSIONAL, 5L);
        assertThat(seenByProfessional.quotationStatus()).isEqualTo(QuotationStatus.DRAFT);
    }

    @Test
    void theBookingReportsWhetherItHasBeenReviewed() {
        bookingOf5();
        when(reviewRepository.findReviewedBookingIds(List.of(5L))).thenReturn(List.of(5L));

        assertThat(bookingService.getById(1L, Role.CUSTOMER, 5L).reviewed()).isTrue();
    }

    @Test
    void aBookingWithNoQuotationOrReviewHasNeither() {
        bookingOf5();

        BookingResponse response = bookingService.getById(1L, Role.CUSTOMER, 5L);

        assertThat(response.quotationStatus()).isNull();
        assertThat(response.reviewed()).isFalse();
    }

    @Test
    void aPageOfBookingsLoadsQuotationsAndReviewsInOneQueryEach() {
        Booking a = Booking.builder().id(5L).customer(customer).requestType(RequestType.FULL_HOME_PROJECT)
                .status(BookingStatus.PENDING_ASSIGNMENT).scheduledAt(Instant.now()).build();
        Booking b = Booking.builder().id(6L).customer(customer).requestType(RequestType.FULL_HOME_PROJECT)
                .status(BookingStatus.PENDING_ASSIGNMENT).scheduledAt(Instant.now()).build();
        Pageable pageable = PageRequest.of(0, 20);
        when(bookingRepository.findByCustomerId(1L, pageable)).thenReturn(new PageImpl<>(List.of(a, b), pageable, 2));

        bookingService.getBookingsForUser(1L, Role.CUSTOMER, null, false, pageable);

        verify(quotationRepository).findByBookingIdIn(List.of(5L, 6L));
        verify(reviewRepository).findReviewedBookingIds(List.of(5L, 6L));
        verify(quotationRepository, never()).findByBookingId(any());
    }

    @Test
    void listWithoutAFilterReturnsEveryStatus() {
        Pageable pageable = PageRequest.of(0, 20);
        when(bookingRepository.findByCustomerId(1L, pageable)).thenReturn(new PageImpl<>(List.of()));

        bookingService.getBookingsForUser(1L, Role.CUSTOMER, List.of(), false, pageable);

        verify(bookingRepository).findByCustomerId(1L, pageable);
        verify(bookingRepository, never()).findByCustomerIdAndStatusIn(any(), any(), any());
    }

    @Test
    void listFiltersCustomerBookingsByStatus() {
        Pageable pageable = PageRequest.of(0, 20);
        List<BookingStatus> statuses = List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED);
        when(bookingRepository.findByCustomerIdAndStatusIn(1L, statuses, pageable)).thenReturn(new PageImpl<>(List.of()));

        bookingService.getBookingsForUser(1L, Role.CUSTOMER, statuses, false, pageable);

        verify(bookingRepository).findByCustomerIdAndStatusIn(1L, statuses, pageable);
    }

    @Test
    void listFiltersProfessionalBookingsByStatus() {
        Pageable pageable = PageRequest.of(0, 20);
        List<BookingStatus> statuses = List.of(BookingStatus.CONFIRMED);
        when(bookingRepository.findByProfessionalIdAndStatusIn(2L, statuses, pageable)).thenReturn(new PageImpl<>(List.of()));

        bookingService.getBookingsForUser(2L, Role.PROFESSIONAL, statuses, false, pageable);

        verify(bookingRepository).findByProfessionalIdAndStatusIn(2L, statuses, pageable);
    }

    @Test
    void actionNeededCoversEveryActiveStatusWhenNoStatusIsGiven() {
        Pageable pageable = PageRequest.of(0, 20);
        Set<BookingStatus> active = Set.of(BookingStatus.PENDING_ASSIGNMENT, BookingStatus.PENDING, BookingStatus.CONFIRMED);
        when(bookingRepository.findAwaitingQuotationResponse(1L, active, QuotationStatus.SENT, pageable))
                .thenReturn(new PageImpl<>(List.of()));

        bookingService.getBookingsForUser(1L, Role.CUSTOMER, null, true, pageable);

        verify(bookingRepository).findAwaitingQuotationResponse(1L, active, QuotationStatus.SENT, pageable);
    }

    @Test
    void actionNeededIsNarrowedByTheStatusFilterAndNeverIncludesFinishedBookings() {
        Pageable pageable = PageRequest.of(0, 20);
        when(bookingRepository.findAwaitingQuotationResponse(1L, Set.of(BookingStatus.PENDING), QuotationStatus.SENT, pageable))
                .thenReturn(new PageImpl<>(List.of()));

        bookingService.getBookingsForUser(1L, Role.CUSTOMER,
                List.of(BookingStatus.COMPLETED, BookingStatus.PENDING), true, pageable);

        verify(bookingRepository).findAwaitingQuotationResponse(1L, Set.of(BookingStatus.PENDING), QuotationStatus.SENT, pageable);
    }

    @Test
    void actionNeededWithOnlyFinishedStatusesIsEmptyWithoutQueryingTheDatabase() {
        Pageable pageable = PageRequest.of(0, 20);

        PageResponse<BookingResponse> result = bookingService.getBookingsForUser(1L, Role.CUSTOMER,
                List.of(BookingStatus.COMPLETED, BookingStatus.CANCELLED), true, pageable);

        assertThat(result.content()).isEmpty();
        verify(bookingRepository, never()).findAwaitingQuotationResponse(any(), any(), any(), any());
    }

    @Test
    void actionNeededIsNotAvailableToProfessionals() {
        assertThatThrownBy(() -> bookingService.getBookingsForUser(2L, Role.PROFESSIONAL, null, true, PageRequest.of(0, 20)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BookingRepository.StatusCount row(BookingStatus status, long total) {
        return new BookingRepository.StatusCount() {
            @Override
            public BookingStatus getStatus() {
                return status;
            }

            @Override
            public long getTotal() {
                return total;
            }
        };
    }

    @Test
    void countsAlwaysIncludeEveryStatusWithZeros() {
        when(bookingRepository.countByCustomerGroupedByStatus(1L))
                .thenReturn(List.of(row(BookingStatus.PENDING, 2), row(BookingStatus.COMPLETED, 3)));
        when(quotationRepository.countForCustomer(eq(1L), eq(QuotationStatus.SENT), any())).thenReturn(1L);

        BookingCountsResponse counts = bookingService.getCounts(1L, Role.CUSTOMER);

        assertThat(counts.byStatus()).containsOnlyKeys(BookingStatus.values());
        assertThat(counts.byStatus()).containsEntry(BookingStatus.PENDING, 2L)
                .containsEntry(BookingStatus.COMPLETED, 3L)
                .containsEntry(BookingStatus.CANCELLED, 0L)
                .containsEntry(BookingStatus.PENDING_ASSIGNMENT, 0L);
        assertThat(counts.awaitingQuotationResponse()).isEqualTo(1L);
    }

    @Test
    void professionalCountsUseTheirAssignedBookingsAndHaveNoQuotationsAwaiting() {
        when(bookingRepository.countByProfessionalGroupedByStatus(2L)).thenReturn(List.of(row(BookingStatus.CONFIRMED, 4)));

        BookingCountsResponse counts = bookingService.getCounts(2L, Role.PROFESSIONAL);

        assertThat(counts.byStatus()).containsEntry(BookingStatus.CONFIRMED, 4L);
        assertThat(counts.awaitingQuotationResponse()).isZero();
        verify(bookingRepository, never()).countByCustomerGroupedByStatus(any());
        verify(quotationRepository, never()).countForCustomer(any(), any(), any());
    }

    @Test
    void cancelRejectsAlreadyCompletedBooking() {
        Booking booking = Booking.builder()
                .id(6L).customer(customer).professional(professional).requestType(RequestType.FULL_HOME_PROJECT)
                .status(BookingStatus.COMPLETED).scheduledAt(Instant.now()).build();
        when(bookingRepository.findById(6L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancel(1L, 6L, null))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void updateStatusRejectsInvalidTransitionFromCancelled() {
        Booking booking = Booking.builder()
                .id(7L).customer(customer).professional(professional).requestType(RequestType.FULL_HOME_PROJECT)
                .status(BookingStatus.CANCELLED).scheduledAt(Instant.now()).build();
        when(bookingRepository.findById(7L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.updateStatus(2L, 7L, BookingStatus.CONFIRMED))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void updateStatusAllowsPendingToConfirmed() {
        Booking booking = Booking.builder()
                .id(8L).customer(customer).professional(professional).requestType(RequestType.FULL_HOME_PROJECT)
                .status(BookingStatus.PENDING).scheduledAt(Instant.now()).build();
        when(bookingRepository.findById(8L)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        BookingResponse response = bookingService.updateStatus(2L, 8L, BookingStatus.CONFIRMED);

        assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void createBookingAlwaysStartsAwaitingAssignment() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(101L);
            b.setCreatedAt(Instant.now());
            return b;
        });

        BookingRequest request = requestFor(RequestType.FULL_HOME_PROJECT, null, null, null);

        BookingResponse response = bookingService.createBooking(1L, request);

        assertThat(response.status()).isEqualTo(BookingStatus.PENDING_ASSIGNMENT);
        assertThat(response.professional()).isNull();
    }

    @Test
    void createBookingRejectsMismatchedBudgetRange() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(customer));

        BookingRequest request = new BookingRequest(RequestType.FULL_HOME_PROJECT,
                Instant.now().plus(1, ChronoUnit.DAYS), null, null, null,
                new java.math.BigDecimal("500000"), new java.math.BigDecimal("100000"), null, null, null);

        assertThatThrownBy(() -> bookingService.createBooking(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budgetMin cannot be greater than budgetMax");
    }

    @Test
    void assignProfessionalSucceedsForValidTarget() {
        Booking booking = Booking.builder()
                .id(9L).customer(customer).professional(null).requestType(RequestType.FULL_HOME_PROJECT)
                .status(BookingStatus.PENDING_ASSIGNMENT).scheduledAt(Instant.now()).build();
        when(bookingRepository.findById(9L)).thenReturn(Optional.of(booking));
        when(userRepository.findById(2L)).thenReturn(Optional.of(professional));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        BookingResponse response = bookingService.assignProfessional(9L, 2L);

        assertThat(response.status()).isEqualTo(BookingStatus.PENDING);
        assertThat(response.professional().id()).isEqualTo(2L);
    }

    @Test
    void assignProfessionalRejectsNonProfessionalTarget() {
        Booking booking = Booking.builder()
                .id(10L).customer(customer).professional(null).requestType(RequestType.FULL_HOME_PROJECT)
                .status(BookingStatus.PENDING_ASSIGNMENT).scheduledAt(Instant.now()).build();
        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        when(userRepository.findById(1L)).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> bookingService.assignProfessional(10L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assignProfessionalRejectsAlreadyAssignedBooking() {
        Booking booking = Booking.builder()
                .id(11L).customer(customer).professional(professional).requestType(RequestType.FULL_HOME_PROJECT)
                .status(BookingStatus.PENDING).scheduledAt(Instant.now()).build();
        when(bookingRepository.findById(11L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.assignProfessional(11L, 2L))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void cancelAllowsPendingAssignmentBooking() {
        Booking booking = Booking.builder()
                .id(12L).customer(customer).professional(null).requestType(RequestType.FULL_HOME_PROJECT)
                .status(BookingStatus.PENDING_ASSIGNMENT).scheduledAt(Instant.now()).build();
        when(bookingRepository.findById(12L)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        BookingResponse response = bookingService.cancel(1L, 12L, null);

        assertThat(response.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(response.cancellationReason()).isNull();
    }

    private Booking pendingBookingOf21() {
        Booking booking = Booking.builder()
                .id(21L).customer(customer).professional(professional).requestType(RequestType.FULL_HOME_PROJECT)
                .status(BookingStatus.PENDING).scheduledAt(Instant.now()).build();
        when(bookingRepository.findById(21L)).thenReturn(Optional.of(booking));
        return booking;
    }

    @Test
    void cancelStoresTheReasonTrimmed() {
        Booking booking = pendingBookingOf21();
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        BookingResponse response = bookingService.cancel(1L, 21L, "  Changed my plans  ");

        assertThat(booking.getCancellationReason()).isEqualTo("Changed my plans");
        assertThat(response.cancellationReason()).isEqualTo("Changed my plans");
    }

    @Test
    void cancelTreatsABlankReasonAsNoReason() {
        Booking booking = pendingBookingOf21();
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        bookingService.cancel(1L, 21L, "   ");

        assertThat(booking.getCancellationReason()).isNull();
    }

    @Test
    void theCancellationReasonIsShownToTheCustomerAndAdminsButNotTheAssignedProfessional() {
        Booking booking = pendingBookingOf21();
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancellationReason("Found another provider");

        assertThat(bookingService.getById(1L, Role.CUSTOMER, 21L).cancellationReason()).isEqualTo("Found another provider");
        assertThat(bookingService.getById(999L, Role.ADMIN, 21L).cancellationReason()).isEqualTo("Found another provider");
        assertThat(bookingService.getById(2L, Role.PROFESSIONAL, 21L).cancellationReason()).isNull();
    }

    @Test
    void theQuotationValidityDateFollowsTheQuotationsVisibilityToTheCustomer() {
        Booking booking = pendingBookingOf21();
        Instant until = Instant.now().plus(7, ChronoUnit.DAYS);
        Quotation sent = Quotation.builder().id(91L).booking(booking).status(QuotationStatus.SENT)
                .totalAmount(new BigDecimal("500")).validUntil(until).build();
        when(quotationRepository.findByBookingIdIn(List.of(21L))).thenReturn(List.of(sent));

        assertThat(bookingService.getById(1L, Role.CUSTOMER, 21L).quotationValidUntil()).isEqualTo(until);

        sent.setStatus(QuotationStatus.DRAFT);
        assertThat(bookingService.getById(1L, Role.CUSTOMER, 21L).quotationValidUntil()).isNull();
        assertThat(bookingService.getById(2L, Role.PROFESSIONAL, 21L).quotationValidUntil()).isEqualTo(until);
    }

    @Test
    void createBookingRejectsIndividualServiceWithoutCategory() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(customer));

        BookingRequest request = requestFor(RequestType.INDIVIDUAL_SERVICE, null, null, null);

        assertThatThrownBy(() -> bookingService.createBooking(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must specify a category");
    }

    @Test
    void createBookingRejectsMismatchedCategoryServiceGroup() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(customer));
        Category homeCategory = Category.builder().id(4L).name("Kitchen").serviceGroup(ServiceGroup.HOME_PROJECT).build();
        when(categoryRepository.findById(4L)).thenReturn(Optional.of(homeCategory));

        BookingRequest request = requestFor(RequestType.INDIVIDUAL_SERVICE, null, 4L, null);

        assertThatThrownBy(() -> bookingService.createBooking(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match the request type");
    }

    @Test
    void createBookingAcceptsValidIndividualServiceRequest() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(customer));
        Category tradeCategory = Category.builder().id(5L).name("Plumbing").serviceGroup(ServiceGroup.INDIVIDUAL_SERVICE).build();
        when(categoryRepository.findById(5L)).thenReturn(Optional.of(tradeCategory));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(102L);
            b.setCreatedAt(Instant.now());
            return b;
        });

        BookingRequest request = requestFor(RequestType.INDIVIDUAL_SERVICE, "leaking tap", 5L, "Mumbai");

        BookingResponse response = bookingService.createBooking(1L, request);

        assertThat(response.status()).isEqualTo(BookingStatus.PENDING_ASSIGNMENT);
        assertThat(response.requestType()).isEqualTo(RequestType.INDIVIDUAL_SERVICE);
    }

    @Test
    void awaitingAssignmentQueueReturnsOnlyPendingAssignmentBookings() {
        Pageable pageable = PageRequest.of(0, 20);
        Booking waiting = Booking.builder()
                .id(200L)
                .customer(customer)
                .requestType(RequestType.INDIVIDUAL_SERVICE)
                .status(BookingStatus.PENDING_ASSIGNMENT)
                .scheduledAt(Instant.now().plus(2, ChronoUnit.DAYS))
                .createdAt(Instant.now())
                .build();
        when(bookingRepository.findByStatus(BookingStatus.PENDING_ASSIGNMENT, pageable))
                .thenReturn(new PageImpl<>(List.of(waiting), pageable, 1));

        PageResponse<BookingResponse> page = bookingService.getBookingsAwaitingAssignment(pageable);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).singleElement()
                .satisfies(booking -> {
                    assertThat(booking.id()).isEqualTo(200L);
                    assertThat(booking.status()).isEqualTo(BookingStatus.PENDING_ASSIGNMENT);
                    assertThat(booking.professional()).isNull();
                });
    }

    @Test
    void awaitingAssignmentQueueIsEmptyWhenNothingIsWaiting() {
        Pageable pageable = PageRequest.of(0, 20);
        when(bookingRepository.findByStatus(BookingStatus.PENDING_ASSIGNMENT, pageable))
                .thenReturn(Page.empty(pageable));

        PageResponse<BookingResponse> page = bookingService.getBookingsAwaitingAssignment(pageable);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }
    @Test
    void createBookingAcceptsSubcategoryWhoseRootMatchesRequestType() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(customer));
        Category root = Category.builder().id(10L).name("Kitchen").serviceGroup(ServiceGroup.HOME_PROJECT).build();
        // child serviceGroup deliberately null: the check must read the ROOT group
        Category child = Category.builder().id(11L).name("Modular").parent(root).build();
        when(categoryRepository.findById(11L)).thenReturn(Optional.of(child));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        BookingResponse response = bookingService.createBooking(1L,
                requestFor(RequestType.FULL_HOME_PROJECT, "notes", 11L, "Mumbai"));

        assertThat(response).isNotNull();
    }

    @Test
    void createBookingAcceptsTopLevelCategoryAsBefore() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(customer));
        Category root = Category.builder().id(10L).name("Kitchen").serviceGroup(ServiceGroup.HOME_PROJECT).build();
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(root));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(bookingService.createBooking(1L,
                requestFor(RequestType.FULL_HOME_PROJECT, "notes", 10L, "Mumbai"))).isNotNull();
    }

    @Test
    void createBookingRejectsSubcategoryWhoseRootGroupMismatchesRequestType() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(customer));
        Category root = Category.builder().id(10L).name("Kitchen").serviceGroup(ServiceGroup.HOME_PROJECT).build();
        // child claims the matching group itself, but the root decides
        Category child = Category.builder().id(11L).name("Modular")
                .serviceGroup(ServiceGroup.INDIVIDUAL_SERVICE).parent(root).build();
        when(categoryRepository.findById(11L)).thenReturn(Optional.of(child));

        assertThatThrownBy(() -> bookingService.createBooking(1L,
                requestFor(RequestType.INDIVIDUAL_SERVICE, "notes", 11L, "Mumbai")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
