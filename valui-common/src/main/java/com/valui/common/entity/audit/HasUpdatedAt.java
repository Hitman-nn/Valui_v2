package com.valui.common.entity.audit;

import java.time.OffsetDateTime;

public interface HasUpdatedAt extends HasCreatedAt {
    OffsetDateTime getUpdatedAt();
    void setUpdatedAt(OffsetDateTime updatedAt);
}
