package com.velora.backend.repository.booking;

import com.velora.backend.entity.booking.Booking;
import com.velora.backend.entity.booking.BookingStatus;
import com.velora.backend.entity.quotation.QuotationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    Page<Booking> findByCustomerId(Long customerId, Pageable pageable);

    Page<Booking> findByCustomerIdAndStatusIn(Long customerId, Collection<BookingStatus> statuses, Pageable pageable);

    Page<Booking> findByProfessionalId(Long professionalId, Pageable pageable);

    Page<Booking> findByProfessionalIdAndStatusIn(Long professionalId, Collection<BookingStatus> statuses, Pageable pageable);

    /** A customer's bookings that have a quotation waiting for their answer, in one of {@code statuses}. */
    @Query("""
            select b from Booking b
            where b.customer.id = :customerId
              and b.status in :statuses
              and exists (select 1 from Quotation q where q.booking = b and q.status = :quotationStatus)
            """)
    Page<Booking> findAwaitingQuotationResponse(@Param("customerId") Long customerId,
                                                @Param("statuses") Collection<BookingStatus> statuses,
                                                @Param("quotationStatus") QuotationStatus quotationStatus,
                                                Pageable pageable);

    @Query("select b.status as status, count(b) as total from Booking b where b.customer.id = :userId group by b.status")
    List<StatusCount> countByCustomerGroupedByStatus(@Param("userId") Long userId);

    @Query("select b.status as status, count(b) as total from Booking b where b.professional.id = :userId group by b.status")
    List<StatusCount> countByProfessionalGroupedByStatus(@Param("userId") Long userId);

    /** One row of {@code GROUP BY status}. */
    interface StatusCount {
        BookingStatus getStatus();

        long getTotal();
    }

    Page<Booking> findByStatus(BookingStatus status, Pageable pageable);

    long countByProfessionalIdAndStatusIn(Long professionalId, Collection<BookingStatus> statuses);

    boolean existsByCategoryId(Long categoryId);

    boolean existsByPortfolioItemId(Long portfolioItemId);
}
