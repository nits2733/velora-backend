package com.velora.backend.mapper;

import com.velora.backend.dto.booking.BookingResponse;
import com.velora.backend.dto.portfolio.PortfolioItemSummaryResponse;
import com.velora.backend.dto.professional.ProfessionalSummaryResponse;
import com.velora.backend.entity.Booking;
import com.velora.backend.entity.BookingInspirationImage;
import com.velora.backend.entity.ProfessionalProfile;
import com.velora.backend.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BookingMapper {

    private final ProfessionalMapper professionalMapper;
    private final PortfolioItemMapper portfolioItemMapper;

    public BookingResponse toResponse(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getRequestType(),
                booking.getCustomer().getId(),
                booking.getCustomer().getFullName(),
                toProfessionalSummary(booking.getProfessional()),
                booking.getPortfolioItem() != null ? portfolioItemMapper.toSummaryResponse(booking.getPortfolioItem()) : null,
                booking.getCategory() != null ? booking.getCategory().getId() : null,
                booking.getCategory() != null ? booking.getCategory().getName() : null,
                booking.getPreferredStyle(),
                booking.getBudgetMin(),
                booking.getBudgetMax(),
                booking.getPreferredTimeline(),
                booking.getLocation(),
                booking.getScheduledAt(),
                booking.getStatus(),
                booking.getNotes(),
                booking.getInspirationImages().stream().map(BookingInspirationImage::getImageUrl).toList(),
                booking.getCreatedAt()
        );
    }

    private ProfessionalSummaryResponse toProfessionalSummary(User professional) {
        if (professional == null) {
            return null;
        }
        ProfessionalProfile profile = professional.getProfessionalProfile();
        return profile != null ? professionalMapper.toSummaryResponse(profile) : null;
    }
}
