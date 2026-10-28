package com.velora.backend.service.quotation;

import com.velora.backend.service.booking.BookingEventRecorder;

import com.velora.backend.dto.quotation.QuotationLineItemRequest;
import com.velora.backend.dto.quotation.QuotationResponse;
import com.velora.backend.dto.quotation.SaveQuotationRequest;
import com.velora.backend.entity.booking.Booking;
import com.velora.backend.entity.quotation.Quotation;
import com.velora.backend.entity.quotation.QuotationLineItem;
import com.velora.backend.entity.quotation.QuotationStatus;
import com.velora.backend.entity.user.Role;
import com.velora.backend.exception.InvalidStateTransitionException;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.mapper.quotation.QuotationMapper;
import com.velora.backend.repository.booking.BookingRepository;
import com.velora.backend.repository.quotation.QuotationRepository;
import com.velora.backend.util.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class QuotationService {

    private final QuotationRepository quotationRepository;
    private final BookingRepository bookingRepository;
    private final QuotationMapper quotationMapper;
    private final BookingEventRecorder eventRecorder;

    @Transactional
    public QuotationResponse saveDraft(Long professionalId, Long bookingId, SaveQuotationRequest request) {
        Booking booking = findBooking(bookingId);
        if (!isAssignedProfessional(professionalId, booking)) {
            throw bookingNotFound(bookingId);
        }

        Quotation quotation = quotationRepository.findByBookingId(bookingId)
                .orElseGet(() -> Quotation.builder()
                        .booking(booking)
                        .status(QuotationStatus.DRAFT)
                        .totalAmount(BigDecimal.ZERO)
                        .build());

        if (quotation.getId() != null && quotation.getStatus() != QuotationStatus.DRAFT) {
            throw new InvalidStateTransitionException(
                    "Cannot edit a quotation once it has been sent, accepted, or rejected");
        }

        quotation.setNotes(request.notes());
        replaceLineItems(quotation, request.lineItems());

        return quotationMapper.toResponse(quotationRepository.save(quotation));
    }

    @Transactional
    public QuotationResponse send(Long professionalId, Long bookingId) {
        Quotation quotation = findQuotationByBooking(bookingId);
        if (!isAssignedProfessional(professionalId, quotation.getBooking())) {
            throw quotationNotFound(bookingId);
        }

        if (quotation.getStatus() != QuotationStatus.DRAFT) {
            throw new InvalidStateTransitionException("Only a draft quotation can be sent");
        }
        if (quotation.getLineItems().isEmpty()) {
            throw new IllegalArgumentException("Cannot send a quotation with no line items");
        }

        quotation.setStatus(QuotationStatus.SENT);
        QuotationResponse response = quotationMapper.toResponse(quotationRepository.save(quotation));
        eventRecorder.recordQuotationSent(quotation.getBooking());
        return response;
    }

    @Transactional
    public QuotationResponse accept(Long customerId, Long bookingId) {
        return respond(customerId, bookingId, QuotationStatus.ACCEPTED);
    }

    @Transactional
    public QuotationResponse reject(Long customerId, Long bookingId) {
        return respond(customerId, bookingId, QuotationStatus.REJECTED);
    }

    /**
     * A customer never sees a {@code DRAFT}: the professional is still working on it and has not
     * offered it yet. Professionals see their own drafts.
     */
    @Transactional(readOnly = true)
    public PageResponse<QuotationResponse> getQuotationsForUser(Long userId, Role role, Pageable pageable) {
        Page<Quotation> quotations = role == Role.PROFESSIONAL
                ? quotationRepository.findByBooking_ProfessionalId(userId, pageable)
                : quotationRepository.findByBooking_CustomerIdAndStatusNot(userId, QuotationStatus.DRAFT, pageable);

        return PageResponse.from(quotations.map(quotationMapper::toResponse));
    }

    @Transactional(readOnly = true)
    public QuotationResponse get(Long userId, Long bookingId) {
        Quotation quotation = findQuotationByBooking(bookingId);
        if (!isParticipant(userId, quotation.getBooking())) {
            throw quotationNotFound(bookingId);
        }
        if (quotation.getStatus() == QuotationStatus.DRAFT && quotation.getBooking().getCustomer().getId().equals(userId)) {
            // Same error as "no quotation yet", so a customer cannot tell a draft exists.
            throw quotationNotFound(bookingId);
        }
        return quotationMapper.toResponse(quotation);
    }

    private QuotationResponse respond(Long customerId, Long bookingId, QuotationStatus newStatus) {
        Quotation quotation = findQuotationByBooking(bookingId);

        if (!quotation.getBooking().getCustomer().getId().equals(customerId)) {
            throw quotationNotFound(bookingId);
        }
        if (quotation.getStatus() != QuotationStatus.SENT) {
            throw new InvalidStateTransitionException("Only a sent quotation can be accepted or rejected");
        }

        quotation.setStatus(newStatus);
        QuotationResponse response = quotationMapper.toResponse(quotationRepository.save(quotation));
        if (newStatus == QuotationStatus.ACCEPTED) {
            eventRecorder.recordQuotationAccepted(quotation.getBooking());
        } else if (newStatus == QuotationStatus.REJECTED) {
            eventRecorder.recordQuotationRejected(quotation.getBooking());
        }
        return response;
    }

    private void replaceLineItems(Quotation quotation, List<QuotationLineItemRequest> lineItemRequests) {
        quotation.getLineItems().clear();

        BigDecimal total = BigDecimal.ZERO;
        int sortOrder = 0;
        for (QuotationLineItemRequest itemRequest : lineItemRequests) {
            QuotationLineItem lineItem = QuotationLineItem.builder()
                    .quotation(quotation)
                    .description(itemRequest.description())
                    .quantity(itemRequest.quantity())
                    .unit(itemRequest.unit())
                    .unitPrice(itemRequest.unitPrice())
                    .amount(itemRequest.amount())
                    .sortOrder(sortOrder++)
                    .build();
            quotation.getLineItems().add(lineItem);
            total = total.add(itemRequest.amount());
        }
        quotation.setTotalAmount(total);
    }

    private boolean isAssignedProfessional(Long professionalId, Booking booking) {
        return booking.getProfessional() != null && booking.getProfessional().getId().equals(professionalId);
    }

    private boolean isParticipant(Long userId, Booking booking) {
        return booking.getCustomer().getId().equals(userId) || isAssignedProfessional(userId, booking);
    }

    /*
     * Non-participants get the same "not found" their request would get if the booking or
     * quotation did not exist, using the message of the lookup that endpoint performs first, so
     * sequential ids cannot be used to discover which bookings exist.
     */
    private ResourceNotFoundException bookingNotFound(Long bookingId) {
        return new ResourceNotFoundException("Booking not found: " + bookingId);
    }

    private Booking findBooking(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> bookingNotFound(bookingId));
    }

    private Quotation findQuotationByBooking(Long bookingId) {
        return quotationRepository.findByBookingId(bookingId)
                .orElseThrow(() -> quotationNotFound(bookingId));
    }

    private ResourceNotFoundException quotationNotFound(Long bookingId) {
        return new ResourceNotFoundException("No quotation found for booking: " + bookingId);
    }
}
