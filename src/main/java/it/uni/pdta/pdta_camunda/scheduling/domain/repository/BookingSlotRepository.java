package it.uni.pdta.pdta_camunda.scheduling.domain.repository;

import it.uni.pdta.pdta_camunda.scheduling.domain.entity.BookingSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface BookingSlotRepository extends JpaRepository<BookingSlot, Long> {

    /**
     * Trova gli slot attivi sovrapposti all'intervallo [start, end) per la risorsa data.
     * Esclude gli slot CANCELED e NO_SHOW perché non occupano più la risorsa.
     */
    @Query("""
        SELECT s FROM BookingSlot s
        WHERE s.resource.id = :resourceId
          AND s.status NOT IN (
              it.uni.pdta.pdta_camunda.scheduling.domain.enums.SlotStatus.CANCELED,
              it.uni.pdta.pdta_camunda.scheduling.domain.enums.SlotStatus.NO_SHOW
          )
          AND s.startTime < :end
          AND s.endTime > :start
        """)
    List<BookingSlot> findOverlapping(
        @Param("resourceId") Long resourceId,
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end
    );

    List<BookingSlot> findByProcessInstanceKey(Long processInstanceKey);
}
