package com.velora.backend.service;

import com.velora.backend.dto.booking.ProfessionalMatchResponse;
import com.velora.backend.entity.AvailabilityStatus;
import com.velora.backend.entity.Booking;
import com.velora.backend.entity.BookingStatus;
import com.velora.backend.entity.InteriorDesignDetails;
import com.velora.backend.entity.PortfolioItem;
import com.velora.backend.entity.ProfessionalProfile;
import com.velora.backend.exception.InvalidStateTransitionException;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.repository.BookingRepository;
import com.velora.backend.repository.PortfolioItemRepository;
import com.velora.backend.repository.ProfessionalProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProfessionalMatchingService {

    private static final int MAX_RESULTS = 10;
    private static final Set<BookingStatus> ACTIVE_STATUSES = Set.of(BookingStatus.PENDING, BookingStatus.CONFIRMED);
    private static final BigDecimal NEUTRAL_RATING = new BigDecimal("3.0");
    private static final BigDecimal BUDGET_FIT_RATIO = new BigDecimal("0.7");

    private final BookingRepository bookingRepository;
    private final ProfessionalProfileRepository professionalProfileRepository;
    private final PortfolioItemRepository portfolioItemRepository;

    @Transactional(readOnly = true)
    public List<ProfessionalMatchResponse> recommend(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));

        if (booking.getStatus() != BookingStatus.PENDING_ASSIGNMENT) {
            throw new InvalidStateTransitionException("This booking is not awaiting professional assignment");
        }

        List<ProfessionalProfile> candidates = professionalProfileRepository.findByAvailabilityStatus(AvailabilityStatus.AVAILABLE);

        return candidates.stream()
                .map(profile -> score(profile, booking))
                .sorted(Comparator.comparingInt(ProfessionalMatchResponse::score).reversed()
                        .thenComparingLong(match -> activeBookingCount(match.professionalId())))
                .limit(MAX_RESULTS)
                .toList();
    }

    private ProfessionalMatchResponse score(ProfessionalProfile profile, Booking booking) {
        Long professionalId = profile.getUser().getId();
        int total = 0;

        total += specializationScore(profile, booking);
        total += portfolioScore(professionalId, booking);
        total += experienceScore(profile);
        total += locationScore(profile, booking);
        total += ratingScore(profile);
        total += budgetScore(professionalId, booking);

        return new ProfessionalMatchResponse(
                professionalId,
                profile.getUser().getFullName(),
                profile.getSpecialization(),
                profile.getCity(),
                profile.getYearsExperience(),
                profile.getAverageRating(),
                profile.getRatingCount(),
                profile.getAvailabilityStatus(),
                total
        );
    }

    private int specializationScore(ProfessionalProfile profile, Booking booking) {
        String specialization = profile.getSpecialization();
        if (specialization == null || specialization.isBlank()) {
            return 0;
        }
        String lowerSpecialization = specialization.toLowerCase();

        boolean matchesCategory = booking.getCategory() != null
                && lowerSpecialization.contains(booking.getCategory().getName().toLowerCase());
        boolean matchesStyle = booking.getPreferredStyle() != null && !booking.getPreferredStyle().isBlank()
                && lowerSpecialization.contains(booking.getPreferredStyle().toLowerCase());

        return (matchesCategory || matchesStyle) ? 30 : 0;
    }

    private int portfolioScore(Long professionalId, Booking booking) {
        int score = 0;
        if (booking.getCategory() != null
                && portfolioItemRepository.countByProfessionalIdAndCategoryId(professionalId, booking.getCategory().getId()) > 0) {
            score += 10;
        }
        if (booking.getPreferredStyle() != null && !booking.getPreferredStyle().isBlank()
                && portfolioItemRepository.countByProfessionalIdAndInteriorDesignDetailsStyleTagIgnoreCase(professionalId, booking.getPreferredStyle()) > 0) {
            score += 10;
        }
        return score;
    }

    private int experienceScore(ProfessionalProfile profile) {
        Integer years = profile.getYearsExperience();
        if (years == null) {
            return 0;
        }
        int cappedYears = Math.min(years, 10);
        return (int) Math.round(cappedYears / 10.0 * 15);
    }

    private int locationScore(ProfessionalProfile profile, Booking booking) {
        if (profile.getCity() == null || booking.getLocation() == null) {
            return 0;
        }
        return profile.getCity().equalsIgnoreCase(booking.getLocation()) ? 15 : 0;
    }

    private int ratingScore(ProfessionalProfile profile) {
        BigDecimal rating = profile.getAverageRating() != null ? profile.getAverageRating() : NEUTRAL_RATING;
        return rating.multiply(new BigDecimal("15"))
                .divide(new BigDecimal("5"), 0, RoundingMode.HALF_UP)
                .intValue();
    }

    private int budgetScore(Long professionalId, Booking booking) {
        BigDecimal budget = booking.getBudgetMax() != null ? booking.getBudgetMax() : booking.getBudgetMin();
        if (budget == null) {
            return 0;
        }
        List<PortfolioItem> items = portfolioItemRepository.findByProfessionalId(professionalId);
        List<BigDecimal> prices = items.stream()
                .map(PortfolioItem::getInteriorDesignDetails)
                .filter(details -> details != null)
                .map(InteriorDesignDetails::getPriceEstimate)
                .filter(price -> price != null)
                .toList();
        if (prices.isEmpty()) {
            return 0;
        }
        BigDecimal average = prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(prices.size()), 2, RoundingMode.HALF_UP);
        BigDecimal affordableThreshold = average.multiply(BUDGET_FIT_RATIO);
        return budget.compareTo(affordableThreshold) >= 0 ? 5 : 0;
    }

    private long activeBookingCount(Long professionalId) {
        return bookingRepository.countByProfessionalIdAndStatusIn(professionalId, ACTIVE_STATUSES);
    }
}
