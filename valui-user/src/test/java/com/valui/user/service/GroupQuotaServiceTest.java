package com.valui.user.service;

import com.valui.common.entity.*;
import com.valui.common.exception.SubscriptionLimitExceededException;
import com.valui.common.exception.ValuiException;
import com.valui.user.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GroupQuotaService — unit tests")
class GroupQuotaServiceTest {

    @Mock GroupChatQuotaRepository quotaRepository;
    @Mock GroupTokenContributionRepository contributionRepository;
    @Mock ControllerRepository controllerRepository;
    @Mock UserRepository userRepository;

    @InjectMocks GroupQuotaService service;

    private static final Long GROUP_ID  = -100_123_456L;
    private static final Long TELEGRAM_ID = 42L;
    private static final UUID USER_UUID   = UUID.randomUUID();

    // ── getMaxControllers ────────────────────────────────────────────────────

    @Test
    @DisplayName("getMaxControllers: no quota row → returns free baseline (3)")
    void getMaxControllers_noRow_returnsBaseline() {
        given(quotaRepository.findById(GROUP_ID)).willReturn(Optional.empty());
        assertThat(service.getMaxControllers(GROUP_ID)).isEqualTo(3);
    }

    @Test
    @DisplayName("getMaxControllers: quota row exists → returns stored value")
    void getMaxControllers_rowExists_returnsStored() {
        GroupChatQuotaEntity quota = GroupChatQuotaEntity.builder()
                .chatId(GROUP_ID).maxControllers(7).build();
        given(quotaRepository.findById(GROUP_ID)).willReturn(Optional.of(quota));
        assertThat(service.getMaxControllers(GROUP_ID)).isEqualTo(7);
    }

    // ── hasCapacity ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("hasCapacity: active < max → true")
    void hasCapacity_notFull_returnsTrue() {
        given(quotaRepository.findById(GROUP_ID)).willReturn(Optional.empty()); // max = 3
        given(controllerRepository.countByNotificationChatIdAndIsActiveTrue(GROUP_ID)).willReturn(2);
        assertThat(service.hasCapacity(GROUP_ID)).isTrue();
    }

    @Test
    @DisplayName("hasCapacity: active == max → false")
    void hasCapacity_atLimit_returnsFalse() {
        given(quotaRepository.findById(GROUP_ID)).willReturn(Optional.empty()); // max = 3
        given(controllerRepository.countByNotificationChatIdAndIsActiveTrue(GROUP_ID)).willReturn(3);
        assertThat(service.hasCapacity(GROUP_ID)).isFalse();
    }

    // ── checkGroupCapacity ───────────────────────────────────────────────────

    @Test
    @DisplayName("checkGroupCapacity: at limit → throws SubscriptionLimitExceededException")
    void checkGroupCapacity_atLimit_throws() {
        given(quotaRepository.findById(GROUP_ID)).willReturn(Optional.empty()); // max = 3
        given(controllerRepository.countByNotificationChatIdAndIsActiveTrue(GROUP_ID)).willReturn(3);
        assertThatThrownBy(() -> service.checkGroupCapacity(GROUP_ID))
                .isInstanceOf(SubscriptionLimitExceededException.class);
    }

    // ── contributeTokens ─────────────────────────────────────────────────────

    @Test
    @DisplayName("contributeTokens: sufficient balance → spends tokens, expands quota")
    void contributeTokens_success() {
        UserEntity user = user(5);
        given(userRepository.findByTelegramId(TELEGRAM_ID)).willReturn(Optional.of(user));
        given(contributionRepository.findByChatIdAndUserId(GROUP_ID, user.getId()))
                .willReturn(Optional.empty());
        given(quotaRepository.findById(GROUP_ID)).willReturn(Optional.empty()); // max = 3

        service.contributeTokens(TELEGRAM_ID, GROUP_ID, 2);

        assertThat(user.getTokenBalance()).isEqualTo(3);
        verify(contributionRepository).save(argThat(c -> c.getTokensCommitted() == 2));
        verify(quotaRepository).save(argThat(q -> q.getMaxControllers() == 5)); // 3 + 2
    }

    @Test
    @DisplayName("contributeTokens: insufficient balance → throws ValuiException 402")
    void contributeTokens_insufficientBalance_throws() {
        UserEntity user = user(1);
        given(userRepository.findByTelegramId(TELEGRAM_ID)).willReturn(Optional.of(user));

        assertThatThrownBy(() -> service.contributeTokens(TELEGRAM_ID, GROUP_ID, 3))
                .isInstanceOf(ValuiException.class)
                .hasMessageContaining("токенов");
    }

    @Test
    @DisplayName("contributeTokens: existing contribution is additive")
    void contributeTokens_addsToExisting() {
        UserEntity user = user(5);
        given(userRepository.findByTelegramId(TELEGRAM_ID)).willReturn(Optional.of(user));

        GroupTokenContributionEntity existing = GroupTokenContributionEntity.builder()
                .chatId(GROUP_ID).user(user).tokensCommitted(2).build();
        given(contributionRepository.findByChatIdAndUserId(GROUP_ID, user.getId()))
                .willReturn(Optional.of(existing));
        given(quotaRepository.findById(GROUP_ID)).willReturn(Optional.empty());

        service.contributeTokens(TELEGRAM_ID, GROUP_ID, 3);

        assertThat(existing.getTokensCommitted()).isEqualTo(5);
    }

    // ── revokeAllContributions ───────────────────────────────────────────────

    @Test
    @DisplayName("revokeAllContributions: reduces group max, zeros out contribution, zeros user balance")
    void revokeAllContributions_reducesQuota() {
        UserEntity user = user(0);
        user.setTokenBalance(0); // already expired
        given(userRepository.findById(USER_UUID)).willReturn(Optional.of(user));

        GroupTokenContributionEntity contribution = GroupTokenContributionEntity.builder()
                .chatId(GROUP_ID).user(user).tokensCommitted(4).build();
        given(contributionRepository.findAllByUserId(USER_UUID))
                .willReturn(List.of(contribution));

        GroupChatQuotaEntity quota = GroupChatQuotaEntity.builder()
                .chatId(GROUP_ID).maxControllers(7).build(); // 3 + 4 contributed
        given(quotaRepository.findById(GROUP_ID)).willReturn(Optional.of(quota));
        given(controllerRepository.findAllByNotificationChatIdAndIsActiveTrue(GROUP_ID))
                .willReturn(List.of());

        service.revokeAllContributions(USER_UUID);

        assertThat(quota.getMaxControllers()).isEqualTo(3); // back to baseline
        assertThat(contribution.getTokensCommitted()).isEqualTo(0);
        assertThat(user.getTokenBalance()).isEqualTo(0);
    }

    @Test
    @DisplayName("revokeAllContributions: deactivates overflow controllers when quota shrinks")
    void revokeAllContributions_deactivatesOverflow() {
        UserEntity user = user(0);
        given(userRepository.findById(USER_UUID)).willReturn(Optional.of(user));

        GroupTokenContributionEntity contribution = GroupTokenContributionEntity.builder()
                .chatId(GROUP_ID).user(user).tokensCommitted(2).build();
        given(contributionRepository.findAllByUserId(USER_UUID))
                .willReturn(List.of(contribution));

        GroupChatQuotaEntity quota = GroupChatQuotaEntity.builder()
                .chatId(GROUP_ID).maxControllers(5).build(); // 3 free + 2 tokens
        given(quotaRepository.findById(GROUP_ID)).willReturn(Optional.of(quota));

        ControllerEntity c1 = controller();
        ControllerEntity c2 = controller();
        ControllerEntity c3 = controller();
        ControllerEntity c4 = controller(); // two over the new limit of 3
        ControllerEntity c5 = controller();
        given(controllerRepository.findAllByNotificationChatIdAndIsActiveTrue(GROUP_ID))
                .willReturn(List.of(c1, c2, c3, c4, c5));

        service.revokeAllContributions(USER_UUID);

        // After revoke: max = 3, active = 5 → deactivate 2
        verify(controllerRepository, times(2)).save(argThat(c -> !c.getIsActive()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UserEntity user(int tokenBalance) {
        UserEntity u = new UserEntity();
        u.setId(USER_UUID);
        u.setTelegramId(TELEGRAM_ID);
        u.setTokenBalance(tokenBalance);
        return u;
    }

    private ControllerEntity controller() {
        return controller(UUID.randomUUID());
    }

    private ControllerEntity controller(UUID ownerId) {
        UserEntity owner = new UserEntity();
        owner.setId(ownerId);
        ControllerEntity c = new ControllerEntity();
        c.setId(UUID.randomUUID());
        c.setIsActive(true);
        c.setNotificationChatId(GROUP_ID);
        c.setUser(owner);
        return c;
    }
}
