package com.velora.backend.repository.quotation;

import com.velora.backend.entity.booking.BookingStatus;
import com.velora.backend.entity.quotation.Quotation;
import com.velora.backend.entity.quotation.QuotationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface QuotationRepository extends JpaRepository<Quotation, Long> {
    Optional<Quotation> findByBookingId(Long bookingId);

    /** Quotations for a whole page of bookings in one query. */
    List<Quotation> findByBookingIdIn(Collection<Long> bookingIds);

    /** How many of a customer's bookings in {@code bookingStatuses} have a quotation in {@code status}. */
    @Query("""
            select count(q) from Quotation q
            where q.booking.customer.id = :customerId
              and q.status = :status
              and q.booking.status in :bookingStatuses
            """)
    long countForCustomer(@Param("customerId") Long customerId,
                          @Param("status") QuotationStatus status,
                          @Param("bookingStatuses") Collection<BookingStatus> bookingStatuses);

    Page<Quotation> findByBooking_CustomerId(Long customerId, Pageable pageable);

    Page<Quotation> findByBooking_CustomerIdAndStatusNot(Long customerId, QuotationStatus status, Pageable pageable);

    Page<Quotation> findByBooking_ProfessionalId(Long professionalId, Pageable pageable);
}
