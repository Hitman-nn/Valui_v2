package com.valui.common.entity.audit;

import java.time.OffsetDateTime;

public interface HasCreatedAt {
    OffsetDateTime getCreatedAt();
    void setCreatedAt(OffsetDateTime createdAt);
}
