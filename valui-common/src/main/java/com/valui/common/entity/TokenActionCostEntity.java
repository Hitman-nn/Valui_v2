package com.valui.common.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "token_action_cost")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenActionCostEntity {

    @Id
    @Column(name = "action_code", length = 64)
    private String actionCode;

    @Column(name = "cost_tokens", nullable = false)
    private Integer costTokens;

    @Column(name = "description", length = 255)
    private String description;
}
