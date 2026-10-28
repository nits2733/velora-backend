package com.velora.backend.repository.quotation;

import com.velora.backend.entity.quotation.Quotation;
import com.velora.backend.entity.quotation.QuotationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface QuotationRepository extends JpaRepository<Quotation, Long> {
    Optional<Quotation> findByBookingId(Long bookingId);

    Page<Quotation> findByBooking_CustomerId(Long customerId, Pageable pageable);

    Page<Quotation> findByBooking_CustomerIdAndStatusNot(Long customerId, QuotationStatus status, Pageable pageable);

    Page<Quotation> findByBooking_ProfessionalId(Long professionalId, Pageable pageable);
}
