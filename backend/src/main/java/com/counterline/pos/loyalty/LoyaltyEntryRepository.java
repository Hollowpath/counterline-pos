package com.counterline.pos.loyalty;

import org.springframework.data.jpa.repository.JpaRepository;

interface LoyaltyEntryRepository extends JpaRepository<LoyaltyEntry, Long> {
}
