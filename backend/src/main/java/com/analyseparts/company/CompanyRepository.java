package com.analyseparts.company;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    Optional<Company> findByIsin(String isin);

    Optional<Company> findByLei(String lei);

    /** Sociétés jamais passées par GLEIF. */
    List<Company> findByLeiCheckedAtIsNullOrderByName();
}
