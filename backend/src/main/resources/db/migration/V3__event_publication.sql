-- Registre des événements de Spring Modulith (schéma "v2" fourni par spring-modulith-events-jdbc 2.1).
-- Chaque événement publié entre modules est d'abord écrit ici, dans la même transaction que
-- l'action qui le publie. Il est marqué terminé quand le listener a réussi. Les événements
-- non terminés (crash, erreur) peuvent être rejoués au redémarrage.
CREATE TABLE IF NOT EXISTS event_publication
(
  id                     UUID NOT NULL,
  listener_id            TEXT NOT NULL,
  event_type             TEXT NOT NULL,
  serialized_event       TEXT NOT NULL,
  publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date        TIMESTAMP WITH TIME ZONE,
  status                 TEXT,
  completion_attempts    INT,
  last_resubmission_date TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON event_publication (completion_date);
