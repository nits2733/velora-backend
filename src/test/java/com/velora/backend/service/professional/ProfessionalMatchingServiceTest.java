package com.velora.backend.service.professional;

import com.velora.backend.dto.booking.ProfessionalMatchResponse;
import com.velora.backend.entity.professional.AvailabilityStatus;
import com.velora.backend.entity.booking.Booking;
import com.velora.backend.entity.booking.BookingStatus;
import com.velora.backend.entity.portfolio.Category;
import com.velora.backend.entity.professional.ProfessionalProfile;
import com.velora.backend.entity.user.Role;
import com.velora.backend.entity.user.User;
import com.velora.backend.exception.InvalidStateTransitionException;
import com.velora.backend.repository.booking.BookingRepository;
import com.velora.backend.repository.portfolio.PortfolioItemRepository;
import com.velora.backend.repository.professional.ProfessionalProfileRepository;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfessionalMatchingServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private ProfessionalProfileRepository professionalProfileRepository;
    @Mock
    private PortfolioItemRepository portfolioItemRepository;

    private ProfessionalMatchingService matchingService;

    private User customer;
    private Category kitchenCategory;

    @BeforeEach
    void setUp() {
        matchingService = new ProfessionalMatchingService(bookingRepository, professionalProfileRepository, portfolioItemRepository);

        customer = User.builder().id(1L).fullName("Cust").role(Role.CUSTOMER).build();
        kitchenCategory = Category.builder().id(3L).name("Kitchen").build();
    }

    @Test
    void recommendRejectsBookingNotAwaitingAssignment() {
        Booking booking = Booking.builder()
                .id(50L).customer(customer).professional(null)
                .status(BookingStatus.PENDING).scheduledAt(Instant.now()).build();
        when(bookingRepository.findById(50L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> matchingService.recommend(50L))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void recommendRanksMatchingProfessionalAboveNonMatchingOne() {
        Booking booking = Booking.builder()
                .id(51L).customer(customer).professional(null)
                .status(BookingStatus.PENDING_ASSIGNMENT).scheduledAt(Instant.now())
                .category(kitchenCategory).location("Mumbai").build();
        when(bookingRepository.findById(51L)).thenReturn(Optional.of(booking));

        User matchingUser = User.builder().id(10L).fullName("Kitchen Expert").role(Role.PROFESSIONAL).build();
        ProfessionalProfile matchingProfile = ProfessionalProfile.builder()
                .id(100L).user(matchingUser).specialization("Kitchen Renovations")
                .city("Mumbai").yearsExperience(8)
                .availabilityStatus(AvailabilityStatus.AVAILABLE).build();

        User nonMatchingUser = User.builder().id(11L).fullName("Bedroom Only").role(Role.PROFESSIONAL).build();
        ProfessionalProfile nonMatchingProfile = ProfessionalProfile.builder()
                .id(101L).user(nonMatchingUser).specialization("Bedroom Design")
                .city("Delhi").yearsExperience(2)
                .availabilityStatus(AvailabilityStatus.AVAILABLE).build();

        when(professionalProfileRepository.findByAvailabilityStatus(AvailabilityStatus.AVAILABLE))
                .thenReturn(List.of(nonMatchingProfile, matchingProfile));
        when(portfolioItemRepository.countByProfessionalIdAndCategoryId(anyLong(), any())).thenReturn(0L);

        List<ProfessionalMatchResponse> results = matchingService.recommend(51L);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).professionalId()).isEqualTo(10L);
        assertThat(results.get(0).score()).isGreaterThan(results.get(1).score());
    }

    @Test
    void recommendGivesUnratedProfessionalNeutralScoreNotZero() {
        Booking booking = Booking.builder()
                .id(52L).customer(customer).professional(null)
                .status(BookingStatus.PENDING_ASSIGNMENT).scheduledAt(Instant.now()).build();
        when(bookingRepository.findById(52L)).thenReturn(Optional.of(booking));

        User unratedUser = User.builder().id(12L).fullName("New Professional").role(Role.PROFESSIONAL).build();
        ProfessionalProfile unratedProfile = ProfessionalProfile.builder()
                .id(102L).user(unratedUser).availabilityStatus(AvailabilityStatus.AVAILABLE)
                .averageRating(null).ratingCount(0).build();

        when(professionalProfileRepository.findByAvailabilityStatus(AvailabilityStatus.AVAILABLE))
                .thenReturn(List.of(unratedProfile));

        List<ProfessionalMatchResponse> results = matchingService.recommend(52L);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isGreaterThan(0);
    }

    @Test
    void recommendBreaksTiesInFavorOfLessBusyProfessional() {
        Booking booking = Booking.builder()
                .id(53L).customer(customer).professional(null)
                .status(BookingStatus.PENDING_ASSIGNMENT).scheduledAt(Instant.now()).build();
        when(bookingRepository.findById(53L)).thenReturn(Optional.of(booking));

        User busyUser = User.builder().id(13L).fullName("Busy Professional").role(Role.PROFESSIONAL).build();
        ProfessionalProfile busyProfile = ProfessionalProfile.builder()
                .id(103L).user(busyUser).availabilityStatus(AvailabilityStatus.AVAILABLE).build();

        User freeUser = User.builder().id(14L).fullName("Free Professional").role(Role.PROFESSIONAL).build();
        ProfessionalProfile freeProfile = ProfessionalProfile.builder()
                .id(104L).user(freeUser).availabilityStatus(AvailabilityStatus.AVAILABLE).build();

        when(professionalProfileRepository.findByAvailabilityStatus(AvailabilityStatus.AVAILABLE))
                .thenReturn(List.of(busyProfile, freeProfile));
        when(bookingRepository.countByProfessionalIdAndStatusIn(eq(13L), any())).thenReturn(3L);
        when(bookingRepository.countByProfessionalIdAndStatusIn(eq(14L), any())).thenReturn(0L);

        List<ProfessionalMatchResponse> results = matchingService.recommend(53L);

        assertThat(results.get(0).professionalId()).isEqualTo(14L);
    }
}
