package com.analyseparts;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Les tâches planifiées (collectes, cours, historique des notes). Coupées d'un seul coup par
 * {@code analyse-parts.scheduling.enabled=false} : le lecteur local (profil {@code reader},
 * ADR 0019) ne fait que lire les rapports, la collecte reste celle du serveur en ligne.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "analyse-parts.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {
}
