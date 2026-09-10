package com.velora.backend.service.favorites;

import com.velora.backend.dto.portfolio.PortfolioItemSummaryResponse;
import com.velora.backend.dto.professional.ProfessionalSummaryResponse;
import com.velora.backend.entity.portfolio.PortfolioItem;
import com.velora.backend.entity.professional.ProfessionalProfile;
import com.velora.backend.entity.user.Role;
import com.velora.backend.entity.favorites.SavedPortfolioItem;
import com.velora.backend.entity.favorites.SavedProfessional;
import com.velora.backend.entity.user.User;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.mapper.portfolio.PortfolioItemMapper;
import com.velora.backend.mapper.professional.ProfessionalMapper;
import com.velora.backend.repository.portfolio.PortfolioItemRepository;
import com.velora.backend.repository.professional.ProfessionalProfileRepository;
import com.velora.backend.repository.favorites.SavedPortfolioItemRepository;
import com.velora.backend.repository.favorites.SavedProfessionalRepository;
import com.velora.backend.repository.user.UserRepository;
import com.velora.backend.util.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FavoritesService {

    private final SavedProfessionalRepository savedProfessionalRepository;
    private final SavedPortfolioItemRepository savedPortfolioItemRepository;
    private final UserRepository userRepository;
    private final PortfolioItemRepository portfolioItemRepository;
    private final ProfessionalProfileRepository professionalProfileRepository;
    private final ProfessionalMapper professionalMapper;
    private final PortfolioItemMapper portfolioItemMapper;

    @Transactional
    public void saveProfessional(Long customerId, Long professionalId) {
        if (savedProfessionalRepository.findByCustomerIdAndProfessionalId(customerId, professionalId).isPresent()) {
            return;
        }
        User customer = userRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + customerId));
        User professional = userRepository.findById(professionalId)
                .orElseThrow(() -> new ResourceNotFoundException("Professional not found: " + professionalId));
        if (professional.getRole() != Role.PROFESSIONAL) {
            throw new IllegalArgumentException("Selected user is not a professional");
        }
        savedProfessionalRepository.save(SavedProfessional.builder()
                .customer(customer)
                .professional(professional)
                .build());
    }

    @Transactional
    public void unsaveProfessional(Long customerId, Long professionalId) {
        savedProfessionalRepository.deleteByCustomerIdAndProfessionalId(customerId, professionalId);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProfessionalSummaryResponse> listSavedProfessionals(Long customerId, Pageable pageable) {
        Page<SavedProfessional> saved = savedProfessionalRepository.findByCustomerId(customerId, pageable);
        return PageResponse.from(saved.map(entry -> {
            ProfessionalProfile profile = professionalProfileRepository.findByUserId(entry.getProfessional().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Professional profile not found"));
            return professionalMapper.toSummaryResponse(profile);
        }));
    }

    @Transactional
    public void savePortfolioItem(Long customerId, Long portfolioItemId) {
        if (savedPortfolioItemRepository.findByCustomerIdAndPortfolioItemId(customerId, portfolioItemId).isPresent()) {
            return;
        }
        User customer = userRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + customerId));
        PortfolioItem portfolioItem = portfolioItemRepository.findById(portfolioItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Portfolio item not found: " + portfolioItemId));
        savedPortfolioItemRepository.save(SavedPortfolioItem.builder()
                .customer(customer)
                .portfolioItem(portfolioItem)
                .build());
    }

    @Transactional
    public void unsavePortfolioItem(Long customerId, Long portfolioItemId) {
        savedPortfolioItemRepository.deleteByCustomerIdAndPortfolioItemId(customerId, portfolioItemId);
    }

    @Transactional(readOnly = true)
    public PageResponse<PortfolioItemSummaryResponse> listSavedPortfolioItems(Long customerId, Pageable pageable) {
        Page<SavedPortfolioItem> saved = savedPortfolioItemRepository.findByCustomerId(customerId, pageable);
        return PageResponse.from(saved.map(entry -> portfolioItemMapper.toSummaryResponse(entry.getPortfolioItem())));
    }
}
