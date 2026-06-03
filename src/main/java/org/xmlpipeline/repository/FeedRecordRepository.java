package org.xmlpipeline.repository;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.xmlpipeline.entity.FeedRecord;

import java.util.UUID;

@Repository
public interface FeedRecordRepository extends JpaRepository<FeedRecord, UUID> {
}