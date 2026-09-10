package com.velora.backend.service.portfolio;

import com.velora.backend.dto.portfolio.PortfolioItemResponse;
import com.velora.backend.dto.portfolio.PortfolioItemSummaryResponse;
import com.velora.backend.dto.portfolio.SavePortfolioItemRequest;
import com.velora.backend.entity.portfolio.Category;
import com.velora.backend.entity.portfolio.InteriorDesignDetails;
import com.velora.backend.entity.portfolio.PortfolioItem;
import com.velora.backend.entity.user.User;
import com.velora.backend.exception.InvalidStateTransitionException;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.exception.UnauthorizedActionException;
import com.velora.backend.mapper.portfolio.PortfolioItemMapper;
import com.velora.backend.repository.booking.BookingRepository;
import com.velora.backend.repository.portfolio.CategoryRepository;
import com.velora.backend.repository.portfolio.PortfolioItemRepository;
import com.velora.backend.repository.portfolio.PortfolioItemSpecifications;
import com.velora.backend.repository.user.UserRepository;
import com.velora.backend.util.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PortfolioItemService {

    private final PortfolioItemRepository portfolioItemRepository;
    private final PortfolioItemMapper portfolioItemMapper;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final BookingRepository bookingRepository;

    @Cacheable(value = "portfolioSearch",
            key = "#categoryId + '|' + #search + '|' + #style + '|' + #pageable.pageNumber + '|' + #pageable.pageSize + '|' + #pageable.sort")
    @Transactional(readOnly = true)
    public PageResponse<PortfolioItemSummaryResponse> search(Long categoryId, String search, String style, Pageable pageable) {
        Specification<PortfolioItem> spec = Specification
                .where(PortfolioItemSpecifications.hasCategory(categoryId))
                .and(PortfolioItemSpecifications.matchesSearch(search))
                .and(PortfolioItemSpecifications.hasStyle(style))
                .and(PortfolioItemSpecifications.fetchCardAssociations());

        Page<PortfolioItemSummaryResponse> page = portfolioItemRepository.findAll(spec, pageable)
                .map(portfolioItemMapper::toSummaryResponse);

        return PageResponse.from(page);
    }

    @Transactional(readOnly = true)
    public PortfolioItemResponse getById(Long id) {
        PortfolioItem item = portfolioItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Portfolio item not found: " + id));
        return portfolioItemMapper.toResponse(item);
    }

    @CacheEvict(value = "portfolioSearch", allEntries = true)
    @Transactional
    public PortfolioItemResponse create(Long professionalId, SavePortfolioItemRequest request) {
        User professional = userRepository.findById(professionalId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + professionalId));

        PortfolioItem item = PortfolioItem.builder()
                .professional(professional)
                .category(loadCategory(request.categoryId()))
                .title(request.title())
                .description(request.description())
                .coverImageUrl(request.coverImageUrl())
                .build();

        applyInteriorDesignDetails(item, request);

        return portfolioItemMapper.toResponse(portfolioItemRepository.save(item));
    }

    /**
     * Full replacement, not a patch - every field is overwritten with what was sent,
     * including dropping the interior-design details when neither style nor price is
     * supplied this time.
     */
    @CacheEvict(value = "portfolioSearch", allEntries = true)
    @Transactional
    public PortfolioItemResponse update(Long professionalId, Long itemId, SavePortfolioItemRequest request) {
        PortfolioItem item = loadOwnItem(professionalId, itemId);

        item.setTitle(request.title());
        item.setDescription(request.description());
        item.setCoverImageUrl(request.coverImageUrl());
        item.setCategory(loadCategory(request.categoryId()));
        applyInteriorDesignDetails(item, request);

        return portfolioItemMapper.toResponse(portfolioItemRepository.save(item));
    }

    /**
     * Hard delete. Blocked while any booking still references the item - a booking
     * points at the work sample the customer was interested in, and that reference is
     * part of the booking's own history, so the item outlives the professional's wish
     * to remove it.
     */
    @CacheEvict(value = "portfolioSearch", allEntries = true)
    @Transactional
    public void delete(Long professionalId, Long itemId) {
        PortfolioItem item = loadOwnItem(professionalId, itemId);

        if (bookingRepository.existsByPortfolioItemId(itemId)) {
            throw new InvalidStateTransitionException(
                    "This portfolio item is referenced by an existing booking and cannot be deleted");
        }

        portfolioItemRepository.delete(item);
    }

    private PortfolioItem loadOwnItem(Long professionalId, Long itemId) {
        PortfolioItem item = portfolioItemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Portfolio item not found: " + itemId));

        if (!item.getProfessional().getId().equals(professionalId)) {
            throw new UnauthorizedActionException("You can only modify your own portfolio items");
        }

        return item;
    }

    private Category loadCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + categoryId));
    }

    private void applyInteriorDesignDetails(PortfolioItem item, SavePortfolioItemRequest request) {
        InteriorDesignDetails existing = item.getInteriorDesignDetails();

        if (request.styleTag() == null && request.priceEstimate() == null) {
            item.setInteriorDesignDetails(null);
        } else if (existing == null) {
            item.setInteriorDesignDetails(InteriorDesignDetails.builder()
                    .portfolioItem(item)
                    .styleTag(request.styleTag())
                    .priceEstimate(request.priceEstimate())
                    .build());
        } else {
            existing.setStyleTag(request.styleTag());
            existing.setPriceEstimate(request.priceEstimate());
        }
    }
}
