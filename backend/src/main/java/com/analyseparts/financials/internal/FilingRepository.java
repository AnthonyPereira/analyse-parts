package com.analyseparts.financials.internal;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FilingRepository extends JpaRepository<FilingEntity, Long> {

    boolean existsBySourceId(String sourceId);

    boolean existsByCompanyId(Long companyId);
}
