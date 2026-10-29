package com.velora.backend.repository.booking;

import com.velora.backend.config.JpaAuditingConfig;
import com.velora.backend.entity.booking.Booking;
import com.velora.backend.entity.booking.BookingStatus;
import com.velora.backend.entity.booking.RequestType;
import com.velora.backend.entity.quotation.Quotation;
import com.velora.backend.entity.quotation.QuotationStatus;
import com.velora.backend.entity.review.Review;
import com.velora.backend.entity.user.AuthProvider;
import com.velora.backend.entity.user.Role;
import com.velora.backend.entity.user.User;
import com.velora.backend.repository.quotation.QuotationRepository;
import com.velora.backend.repository.review.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the hand-written and derived queries behind the Requests tab against a real (H2) schema, since
 * the service tests mock the repositories and so cannot catch a query that does not parse or filter.
 */
@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@Import(JpaAuditingConfig.class)
class BookingQueriesJpaTest {

    private static final Set<BookingStatus> ACTIVE =
            Set.of(BookingStatus.PENDING_ASSIGNMENT, BookingStatus.PENDING, BookingStatus.CONFIRMED);

    @Autowired
    private TestEntityManager em;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private QuotationRepository quotationRepository;
    @Autowired
    private ReviewRepository reviewRepository;

    private User customer;
    private User otherCustomer;
    private User professional;

    @BeforeEach
    void setUp() {
        customer = user("customer@velora.test", Role.CUSTOMER);
        otherCustomer = user("other@velora.test", Role.CUSTOMER);
        professional = user("pro@velora.test", Role.PROFESSIONAL);
    }

    private User user(String email, Role role) {
        return em.persist(User.builder().email(email).passwordHash("x").fullName(email).role(role)
                .authProvider(AuthProvider.LOCAL).build());
    }

    private Booking booking(User owner, BookingStatus status) {
        return em.persist(Booking.builder().customer(owner).professional(professional)
                .requestType(RequestType.FULL_HOME_PROJECT).status(status).scheduledAt(Instant.now()).build());
    }

    private Quotation quotation(Booking booking, QuotationStatus status) {
        return em.persist(Quotation.builder().booking(booking).status(status).totalAmount(new BigDecimal("1000")).build());
    }

    @Test
    void awaitingResponseKeepsOnlyActiveBookingsWithASentQuotationForThatCustomer() {
        Booking waiting = booking(customer, BookingStatus.PENDING);
        quotation(waiting, QuotationStatus.SENT);
        quotation(booking(customer, BookingStatus.CONFIRMED), QuotationStatus.DRAFT);
        quotation(booking(customer, BookingStatus.CONFIRMED), QuotationStatus.ACCEPTED);
        quotation(booking(customer, BookingStatus.COMPLETED), QuotationStatus.SENT);
        booking(customer, BookingStatus.PENDING_ASSIGNMENT);
        quotation(booking(otherCustomer, BookingStatus.PENDING), QuotationStatus.SENT);
        em.flush();

        var page = bookingRepository.findAwaitingQuotationResponse(
                customer.getId(), ACTIVE, QuotationStatus.SENT, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(Booking::getId).containsExactly(waiting.getId());
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void awaitingResponseHonoursANarrowedStatusSet() {
        quotation(booking(customer, BookingStatus.PENDING), QuotationStatus.SENT);
        Booking confirmed = booking(customer, BookingStatus.CONFIRMED);
        quotation(confirmed, QuotationStatus.SENT);
        em.flush();

        var page = bookingRepository.findAwaitingQuotationResponse(
                customer.getId(), Set.of(BookingStatus.CONFIRMED), QuotationStatus.SENT, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(Booking::getId).containsExactly(confirmed.getId());
    }

    @Test
    void sentQuotationsAwaitingTheCustomerAreCountedOnlyForActiveBookings() {
        quotation(booking(customer, BookingStatus.PENDING), QuotationStatus.SENT);
        quotation(booking(customer, BookingStatus.CONFIRMED), QuotationStatus.SENT);
        quotation(booking(customer, BookingStatus.COMPLETED), QuotationStatus.SENT);
        quotation(booking(customer, BookingStatus.CONFIRMED), QuotationStatus.DRAFT);
        quotation(booking(otherCustomer, BookingStatus.PENDING), QuotationStatus.SENT);
        em.flush();

        assertThat(quotationRepository.countForCustomer(customer.getId(), QuotationStatus.SENT, ACTIVE)).isEqualTo(2);
    }

    @Test
    void bookingsAreCountedPerStatusForOneCustomer() {
        booking(customer, BookingStatus.PENDING);
        booking(customer, BookingStatus.PENDING);
        booking(customer, BookingStatus.COMPLETED);
        booking(otherCustomer, BookingStatus.PENDING);
        em.flush();

        var rows = bookingRepository.countByCustomerGroupedByStatus(customer.getId());

        assertThat(rows).extracting(BookingRepository.StatusCount::getStatus)
                .containsExactlyInAnyOrder(BookingStatus.PENDING, BookingStatus.COMPLETED);
        assertThat(rows.stream().filter(r -> r.getStatus() == BookingStatus.PENDING).findFirst().orElseThrow().getTotal())
                .isEqualTo(2);
    }

    @Test
    void bookingsAreCountedPerStatusForOneProfessional() {
        booking(customer, BookingStatus.CONFIRMED);
        booking(otherCustomer, BookingStatus.CONFIRMED);
        em.flush();

        var rows = bookingRepository.countByProfessionalGroupedByStatus(professional.getId());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTotal()).isEqualTo(2);
    }

    @Test
    void reviewedBookingIdsReturnsOnlyTheReviewedOnes() {
        Booking reviewed = booking(customer, BookingStatus.COMPLETED);
        Booking notReviewed = booking(customer, BookingStatus.COMPLETED);
        em.persist(Review.builder().booking(reviewed).customer(customer).professional(professional).rating(5).build());
        em.flush();

        assertThat(reviewRepository.findReviewedBookingIds(List.of(reviewed.getId(), notReviewed.getId())))
                .containsExactly(reviewed.getId());
    }

    @Test
    void quotationsForAPageOfBookingsComeBackInOneQuery() {
        Booking a = booking(customer, BookingStatus.PENDING);
        Booking b = booking(customer, BookingStatus.PENDING);
        Booking c = booking(customer, BookingStatus.PENDING);
        quotation(a, QuotationStatus.SENT);
        quotation(b, QuotationStatus.DRAFT);
        em.flush();

        assertThat(quotationRepository.findByBookingIdIn(List.of(a.getId(), b.getId(), c.getId())))
                .extracting(q -> q.getBooking().getId())
                .containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    @Test
    void theCustomerQuotationListLeavesOutDrafts() {
        quotation(booking(customer, BookingStatus.PENDING), QuotationStatus.DRAFT);
        quotation(booking(customer, BookingStatus.PENDING), QuotationStatus.SENT);
        quotation(booking(customer, BookingStatus.CONFIRMED), QuotationStatus.ACCEPTED);
        em.flush();

        var page = quotationRepository.findByBooking_CustomerIdAndStatusNot(
                customer.getId(), QuotationStatus.DRAFT, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(Quotation::getStatus)
                .containsExactlyInAnyOrder(QuotationStatus.SENT, QuotationStatus.ACCEPTED);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void theStatusFilterAppliesToCustomersAndProfessionals() {
        booking(customer, BookingStatus.PENDING);
        booking(customer, BookingStatus.CONFIRMED);
        booking(customer, BookingStatus.COMPLETED);
        em.flush();
        var pageable = PageRequest.of(0, 20);
        var wanted = List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED);

        assertThat(bookingRepository.findByCustomerIdAndStatusIn(customer.getId(), wanted, pageable).getContent())
                .extracting(Booking::getStatus).containsExactlyInAnyOrder(BookingStatus.PENDING, BookingStatus.CONFIRMED);
        assertThat(bookingRepository.findByProfessionalIdAndStatusIn(professional.getId(), wanted, pageable).getContent())
                .hasSize(2);
    }

    @Test
    void theReasonAndValidityColumnsRoundTrip() {
        Booking booking = booking(customer, BookingStatus.CANCELLED);
        booking.setCancellationReason("Changed my plans");
        Quotation quotation = quotation(booking, QuotationStatus.REJECTED);
        Instant until = Instant.parse("2027-01-15T10:00:00Z");
        quotation.setRejectionReason("Over my budget");
        quotation.setValidUntil(until);
        em.flush();
        em.clear();

        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getCancellationReason())
                .isEqualTo("Changed my plans");
        Quotation reloaded = quotationRepository.findByBookingId(booking.getId()).orElseThrow();
        assertThat(reloaded.getRejectionReason()).isEqualTo("Over my budget");
        assertThat(reloaded.getValidUntil()).isEqualTo(until);
    }
}
