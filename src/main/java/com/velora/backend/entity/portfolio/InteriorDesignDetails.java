package com.velora.backend.entity.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Optional 1-to-1 satellite off PortfolioItem, populated only when a portfolio item
 * is interior-design work (style + a price estimate). A painter/plumber/electrician
 * portfolio item simply has no row here - PortfolioItem stays generic across every
 * trade, this holds only what's specific to interior design.
 */
@Entity
@Table(name = "interior_design_details")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InteriorDesignDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portfolio_item_id", nullable = false, unique = true)
    private PortfolioItem portfolioItem;

    @Column(name = "style_tag")
    private String styleTag;

    @Column(name = "price_estimate", precision = 12, scale = 2)
    private BigDecimal priceEstimate;
}
