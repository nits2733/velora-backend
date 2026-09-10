package com.velora.backend.service.portfolio;

import com.velora.backend.dto.portfolio.SavePortfolioItemRequest;
import com.velora.backend.dto.portfolio.PortfolioItemResponse;
import com.velora.backend.entity.portfolio.Category;
import com.velora.backend.entity.portfolio.PortfolioItem;
import com.velora.backend.entity.user.Role;
import com.velora.backend.entity.user.User;
import com.velora.backend.entity.portfolio.InteriorDesignDetails;
import com.velora.backend.exception.InvalidStateTransitionException;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.exception.UnauthorizedActionException;
import com.velora.backend.mapper.portfolio.PortfolioItemMapper;
import com.velora.backend.repository.booking.BookingRepository;
import com.velora.backend.repository.portfolio.CategoryRepository;
import com.velora.backend.repository.portfolio.PortfolioItemRepository;
import com.velora.backend.repository.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioItemServiceTest {

    @Mock
    private PortfolioItemRepository portfolioItemRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private BookingRepository bookingRepository;

    private final PortfolioItemMapper portfolioItemMapper = new PortfolioItemMapper();

    private PortfolioItemService portfolioItemService;

    private User professional;
    private Category paintingCategory;

    @BeforeEach
    void setUp() {
        portfolioItemService = new PortfolioItemService(
                portfolioItemRepository, portfolioItemMapper, userRepository, categoryRepository, bookingRepository);

        professional = User.builder().id(3L).fullName("Rebrand Plumber").role(Role.PROFESSIONAL).build();
        paintingCategory = Category.builder().id(8L).name("Painting").build();
    }

    @Test
    void createWithoutStyleOrPriceOmitsInteriorDesignDetails() {
        when(userRepository.findById(3L)).thenReturn(Optional.of(professional));
        when(categoryRepository.findById(8L)).thenReturn(Optional.of(paintingCategory));
        when(portfolioItemRepository.save(any(PortfolioItem.class))).thenAnswer(inv -> {
            PortfolioItem item = inv.getArgument(0);
            item.setId(50L);
            return item;
        });

        SavePortfolioItemRequest request = new SavePortfolioItemRequest(
                "Before/After Living Room Repaint", "Two-coat repaint", 8L, "https://img/1.jpg", null, null);

        PortfolioItemResponse response = portfolioItemService.create(3L, request);

        assertThat(response.id()).isEqualTo(50L);
        assertThat(response.title()).isEqualTo("Before/After Living Room Repaint");
        assertThat(response.styleTag()).isNull();
        assertThat(response.priceEstimate()).isNull();

        ArgumentCaptor<PortfolioItem> captor = ArgumentCaptor.forClass(PortfolioItem.class);
        verify(portfolioItemRepository).save(captor.capture());
        assertThat(captor.getValue().getInteriorDesignDetails()).isNull();
    }

    @Test
    void createWithStyleAndPriceAttachesInteriorDesignDetails() {
        when(userRepository.findById(3L)).thenReturn(Optional.of(professional));
        when(categoryRepository.findById(8L)).thenReturn(Optional.of(paintingCategory));
        when(portfolioItemRepository.save(any(PortfolioItem.class))).thenAnswer(inv -> {
            PortfolioItem item = inv.getArgument(0);
            item.setId(51L);
            return item;
        });

        SavePortfolioItemRequest request = new SavePortfolioItemRequest(
                "Minimalist Living Room Concept", null, 8L, null, "minimalist", new BigDecimal("42000"));

        PortfolioItemResponse response = portfolioItemService.create(3L, request);

        assertThat(response.styleTag()).isEqualTo("minimalist");
        assertThat(response.priceEstimate()).isEqualByComparingTo("42000");
    }

    @Test
    void createRejectsUnknownProfessional() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        SavePortfolioItemRequest request = new SavePortfolioItemRequest(
                "Title", null, 8L, null, null, null);

        assertThatThrownBy(() -> portfolioItemService.create(99L, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createRejectsUnknownCategory() {
        when(userRepository.findById(3L)).thenReturn(Optional.of(professional));
        when(categoryRepository.findById(404L)).thenReturn(Optional.empty());

        SavePortfolioItemRequest request = new SavePortfolioItemRequest(
                "Title", null, 404L, null, null, null);

        assertThatThrownBy(() -> portfolioItemService.create(3L, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateReplacesEveryFieldOnYourOwnItem() {
        PortfolioItem existing = ownedItem(60L);
        when(portfolioItemRepository.findById(60L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(8L)).thenReturn(Optional.of(paintingCategory));
        when(portfolioItemRepository.save(any(PortfolioItem.class))).thenAnswer(inv -> inv.getArgument(0));

        SavePortfolioItemRequest request = new SavePortfolioItemRequest(
                "Corrected Title", "Corrected description", 8L, "https://img/new.jpg", null, null);

        PortfolioItemResponse response = portfolioItemService.update(3L, 60L, request);

        assertThat(response.title()).isEqualTo("Corrected Title");
        assertThat(response.description()).isEqualTo("Corrected description");
        assertThat(response.coverImageUrl()).isEqualTo("https://img/new.jpg");
    }

    @Test
    void updateAttachesInteriorDesignDetailsWhenNoneExistedBefore() {
        PortfolioItem existing = ownedItem(61L);
        when(portfolioItemRepository.findById(61L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(8L)).thenReturn(Optional.of(paintingCategory));
        when(portfolioItemRepository.save(any(PortfolioItem.class))).thenAnswer(inv -> inv.getArgument(0));

        SavePortfolioItemRequest request = new SavePortfolioItemRequest(
                "Now Interior Work", null, 8L, null, "scandi", new BigDecimal("55000"));

        PortfolioItemResponse response = portfolioItemService.update(3L, 61L, request);

        assertThat(response.styleTag()).isEqualTo("scandi");
        assertThat(response.priceEstimate()).isEqualByComparingTo("55000");
    }

    @Test
    void updateDropsInteriorDesignDetailsWhenStyleAndPriceAreBothOmitted() {
        PortfolioItem existing = ownedItem(62L);
        existing.setInteriorDesignDetails(InteriorDesignDetails.builder()
                .portfolioItem(existing)
                .styleTag("minimalist")
                .priceEstimate(new BigDecimal("42000"))
                .build());
        when(portfolioItemRepository.findById(62L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(8L)).thenReturn(Optional.of(paintingCategory));
        when(portfolioItemRepository.save(any(PortfolioItem.class))).thenAnswer(inv -> inv.getArgument(0));

        SavePortfolioItemRequest request = new SavePortfolioItemRequest(
                "Actually A Repaint", null, 8L, null, null, null);

        PortfolioItemResponse response = portfolioItemService.update(3L, 62L, request);

        assertThat(response.styleTag()).isNull();
        assertThat(response.priceEstimate()).isNull();
        assertThat(existing.getInteriorDesignDetails()).isNull();
    }

    @Test
    void updateRejectsSomeoneElsesItem() {
        when(portfolioItemRepository.findById(63L)).thenReturn(Optional.of(ownedItem(63L)));

        SavePortfolioItemRequest request = new SavePortfolioItemRequest(
                "Hijacked", null, 8L, null, null, null);

        assertThatThrownBy(() -> portfolioItemService.update(99L, 63L, request))
                .isInstanceOf(UnauthorizedActionException.class);
    }

    @Test
    void updateRejectsUnknownItem() {
        when(portfolioItemRepository.findById(404L)).thenReturn(Optional.empty());

        SavePortfolioItemRequest request = new SavePortfolioItemRequest(
                "Title", null, 8L, null, null, null);

        assertThatThrownBy(() -> portfolioItemService.update(3L, 404L, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteRemovesAnUnreferencedItem() {
        PortfolioItem existing = ownedItem(70L);
        when(portfolioItemRepository.findById(70L)).thenReturn(Optional.of(existing));
        when(bookingRepository.existsByPortfolioItemId(70L)).thenReturn(false);

        portfolioItemService.delete(3L, 70L);

        verify(portfolioItemRepository).delete(existing);
    }

    @Test
    void deleteIsBlockedWhileABookingStillReferencesTheItem() {
        when(portfolioItemRepository.findById(71L)).thenReturn(Optional.of(ownedItem(71L)));
        when(bookingRepository.existsByPortfolioItemId(71L)).thenReturn(true);

        assertThatThrownBy(() -> portfolioItemService.delete(3L, 71L))
                .isInstanceOf(InvalidStateTransitionException.class);

        verify(portfolioItemRepository, never()).delete(any(PortfolioItem.class));
    }

    @Test
    void deleteRejectsSomeoneElsesItem() {
        when(portfolioItemRepository.findById(72L)).thenReturn(Optional.of(ownedItem(72L)));

        assertThatThrownBy(() -> portfolioItemService.delete(99L, 72L))
                .isInstanceOf(UnauthorizedActionException.class);

        verify(portfolioItemRepository, never()).delete(any(PortfolioItem.class));
    }

    private PortfolioItem ownedItem(Long id) {
        return PortfolioItem.builder()
                .id(id)
                .professional(professional)
                .category(paintingCategory)
                .title("Original Title")
                .description("Original description")
                .coverImageUrl("https://img/old.jpg")
                .build();
    }
}
