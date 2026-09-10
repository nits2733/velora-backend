package com.velora.backend.service.quotation;

import com.velora.backend.dto.quotation.QuotationLineItemRequest;
import com.velora.backend.dto.quotation.QuotationResponse;
import com.velora.backend.dto.quotation.SaveQuotationRequest;
import com.velora.backend.entity.booking.Booking;
import com.velora.backend.entity.booking.BookingStatus;
import com.velora.backend.entity.quotation.Quotation;
import com.velora.backend.entity.quotation.QuotationStatus;
import com.velora.backend.entity.user.Role;
import com.velora.backend.entity.user.User;
import com.velora.backend.exception.InvalidStateTransitionException;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.exception.UnauthorizedActionException;
import com.velora.backend.mapper.quotation.QuotationMapper;
import com.velora.backend.repository.booking.BookingRepository;
import com.velora.backend.service.booking.BookingEventRecorder;
import com.velora.backend.repository.quotation.QuotationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuotationServiceTest {

    @Mock
    private QuotationRepository quotationRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingEventRecorder eventRecorder;

    private final QuotationMapper quotationMapper = new QuotationMapper();

    private QuotationService quotationService;

    private User customer;
    private User professional;
    private Booking booking;

    @BeforeEach
    void setUp() {
        quotationService = new QuotationService(quotationRepository, bookingRepository, quotationMapper, eventRecorder);

        customer = User.builder().id(1L).email("customer@velora.test").fullName("Cust").role(Role.CUSTOMER).build();
        professional = User.builder().id(2L).email("professional@velora.test").fullName("Pro").role(Role.PROFESSIONAL).build();
        booking = Booking.builder()
                .id(10L).customer(customer).professional(professional)
                .status(BookingStatus.CONFIRMED).scheduledAt(Instant.now()).build();
    }

    @Test
    void saveDraftRejectsNonAssignedProfessional() {
        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));

        SaveQuotationRequest request = new SaveQuotationRequest(null, List.of());

        assertThatThrownBy(() -> quotationService.saveDraft(999L, 10L, request))
                .isInstanceOf(UnauthorizedActionException.class);
    }

    @Test
    void saveDraftComputesTotalFromLineItems() {
        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        when(quotationRepository.findByBookingId(10L)).thenReturn(Optional.empty());
        when(quotationRepository.save(any(Quotation.class))).thenAnswer(inv -> {
            Quotation q = inv.getArgument(0);
            q.setId(50L);
            q.setCreatedAt(Instant.now());
            q.setUpdatedAt(Instant.now());
            return q;
        });

        SaveQuotationRequest request = new SaveQuotationRequest("Scope notes", List.of(
                new QuotationLineItemRequest("Modular kitchen", null, null, null, new BigDecimal("50000.00")),
                new QuotationLineItemRequest("Painting", new BigDecimal("500"), "sqft", new BigDecimal("20.00"), new BigDecimal("10000.00"))
        ));

        QuotationResponse response = quotationService.saveDraft(2L, 10L, request);

        assertThat(response.status()).isEqualTo(QuotationStatus.DRAFT);
        assertThat(response.totalAmount()).isEqualByComparingTo("60000.00");
        assertThat(response.lineItems()).hasSize(2);
    }

    @Test
    void saveDraftRejectsEditingAlreadySentQuotation() {
        Quotation sent = Quotation.builder()
                .id(51L).booking(booking).status(QuotationStatus.SENT).totalAmount(BigDecimal.ZERO).build();
        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        when(quotationRepository.findByBookingId(10L)).thenReturn(Optional.of(sent));

        SaveQuotationRequest request = new SaveQuotationRequest(null, List.of());

        assertThatThrownBy(() -> quotationService.saveDraft(2L, 10L, request))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void sendRejectsQuotationWithNoLineItems() {
        Quotation draft = Quotation.builder()
                .id(52L).booking(booking).status(QuotationStatus.DRAFT).totalAmount(BigDecimal.ZERO).build();
        when(quotationRepository.findByBookingId(10L)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> quotationService.send(2L, 10L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sendRejectsNonDraftQuotation() {
        Quotation alreadySent = Quotation.builder()
                .id(53L).booking(booking).status(QuotationStatus.SENT).totalAmount(new BigDecimal("1000")).build();
        alreadySent.getLineItems().add(
                com.velora.backend.entity.quotation.QuotationLineItem.builder()
                        .quotation(alreadySent).description("x").amount(new BigDecimal("1000")).build());
        when(quotationRepository.findByBookingId(10L)).thenReturn(Optional.of(alreadySent));

        assertThatThrownBy(() -> quotationService.send(2L, 10L))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void sendSucceedsForValidDraft() {
        Quotation draft = Quotation.builder()
                .id(54L).booking(booking).status(QuotationStatus.DRAFT).totalAmount(new BigDecimal("1000")).build();
        draft.getLineItems().add(
                com.velora.backend.entity.quotation.QuotationLineItem.builder()
                        .quotation(draft).description("x").amount(new BigDecimal("1000")).build());
        when(quotationRepository.findByBookingId(10L)).thenReturn(Optional.of(draft));
        when(quotationRepository.save(any(Quotation.class))).thenAnswer(inv -> inv.getArgument(0));

        QuotationResponse response = quotationService.send(2L, 10L);

        assertThat(response.status()).isEqualTo(QuotationStatus.SENT);
    }

    @Test
    void acceptRejectsNonOwnerCustomer() {
        Quotation sent = Quotation.builder()
                .id(55L).booking(booking).status(QuotationStatus.SENT).totalAmount(BigDecimal.ZERO).build();
        when(quotationRepository.findByBookingId(10L)).thenReturn(Optional.of(sent));

        assertThatThrownBy(() -> quotationService.accept(999L, 10L))
                .isInstanceOf(UnauthorizedActionException.class);
    }

    @Test
    void acceptRejectsQuotationNotYetSent() {
        Quotation draft = Quotation.builder()
                .id(56L).booking(booking).status(QuotationStatus.DRAFT).totalAmount(BigDecimal.ZERO).build();
        when(quotationRepository.findByBookingId(10L)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> quotationService.accept(1L, 10L))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void acceptSucceedsForOwnerCustomerOnSentQuotation() {
        Quotation sent = Quotation.builder()
                .id(57L).booking(booking).status(QuotationStatus.SENT).totalAmount(new BigDecimal("2000")).build();
        when(quotationRepository.findByBookingId(10L)).thenReturn(Optional.of(sent));
        when(quotationRepository.save(any(Quotation.class))).thenAnswer(inv -> inv.getArgument(0));

        QuotationResponse response = quotationService.accept(1L, 10L);

        assertThat(response.status()).isEqualTo(QuotationStatus.ACCEPTED);
    }

    @Test
    void saveDraftRejectsBookingWithNoAssignedProfessional() {
        Booking unassigned = Booking.builder()
                .id(20L).customer(customer).professional(null)
                .status(BookingStatus.PENDING_ASSIGNMENT).scheduledAt(Instant.now()).build();
        when(bookingRepository.findById(20L)).thenReturn(Optional.of(unassigned));

        SaveQuotationRequest request = new SaveQuotationRequest(null, List.of());

        assertThatThrownBy(() -> quotationService.saveDraft(2L, 20L, request))
                .isInstanceOf(UnauthorizedActionException.class);
    }

    @Test
    void getThrowsNotFoundWhenNoQuotationExistsForBooking() {
        when(quotationRepository.findByBookingId(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> quotationService.get(1L, 10L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
