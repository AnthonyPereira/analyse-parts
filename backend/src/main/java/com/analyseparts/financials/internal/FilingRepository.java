package com.analyseparts.financials.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface FilingRepository extends JpaRepository<FilingEntity, Long> {

    boolean existsBySourceId(String sourceId);

    boolean existsByCompanyId(Long companyId);

    /** Date de clôture du rapport le plus récent, par société. */
    interface LatestPeriodEnd {
        Long getCompanyId();

        LocalDate getLatest();
    }

    @Query("select f.periodEnd from FilingEntity f where f.companyId = :companyId")
    List<LocalDate> findPeriodEnds(Long companyId);

    @Query("select f.companyId as companyId, max(f.periodEnd) as latest from FilingEntity f group by f.companyId")
    List<LatestPeriodEnd> findLatestPeriodEnds();
}
