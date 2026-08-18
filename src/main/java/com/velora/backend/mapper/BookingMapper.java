package com.velora.backend.mapper;

import com.velora.backend.dto.booking.BookingResponse;
import com.velora.backend.entity.Booking;
import org.springframework.stereotype.Component;

@Component
public class BookingMapper {

    public BookingResponse toResponse(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getRequestType(),
                booking.getCustomer().getId(),
                booking.getCustomer().getFullName(),
                booking.getProfessional() != null ? booking.getProfessional().getId() : null,
                booking.getProfessional() != null ? booking.getProfessional().getFullName() : null,
                booking.getPortfolioItem() != null ? booking.getPortfolioItem().getId() : null,
                booking.getPortfolioItem() != null ? booking.getPortfolioItem().getTitle() : null,
                booking.getCategory() != null ? booking.getCategory().getId() : null,
                booking.getCategory() != null ? booking.getCategory().getName() : null,
                booking.getPreferredStyle(),
                booking.getBudget(),
                booking.getLocation(),
                booking.getScheduledAt(),
                booking.getStatus(),
                booking.getNotes(),
                booking.getCreatedAt()
        );
    }
}

