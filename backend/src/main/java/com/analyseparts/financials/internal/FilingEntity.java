package com.analyseparts.financials.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "filing")
public class FilingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long companyId;

    @Column(nullable = false, unique = true, length = 200)
    private String sourceId;

    @Column(nullable = false)
    private LocalDate periodEnd;

    @Column(length = 2)
    private String country;

    @Column(nullable = false)
    private String jsonUrl;

    private Integer errorCount;

    @Column(nullable = false)
    private int factCount;

    @Column(nullable = false)
    private Instant importedAt;

    protected FilingEntity() {
    }

    public FilingEntity(Long companyId, String sourceId, LocalDate periodEnd, String country,
                        String jsonUrl, Integer errorCount, int factCount) {
        this.companyId = companyId;
        this.sourceId = sourceId;
        this.periodEnd = periodEnd;
        this.country = country;
        this.jsonUrl = jsonUrl;
        this.errorCount = errorCount;
        this.factCount = factCount;
        this.importedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getSourceId() { return sourceId; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public String getJsonUrl() { return jsonUrl; }
}
