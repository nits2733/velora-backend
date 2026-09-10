package com.velora.backend.repository.favorites;

import com.velora.backend.entity.favorites.SavedPortfolioItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SavedPortfolioItemRepository extends JpaRepository<SavedPortfolioItem, Long> {
    Page<SavedPortfolioItem> findByCustomerId(Long customerId, Pageable pageable);

    Optional<SavedPortfolioItem> findByCustomerIdAndPortfolioItemId(Long customerId, Long portfolioItemId);

    void deleteByCustomerIdAndPortfolioItemId(Long customerId, Long portfolioItemId);
}
