package com.valui.user.scheduler;

import com.valui.common.domain.UserStatus;
import com.valui.common.entity.UserEntity;
import com.valui.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * Ежемесячный биллинг токенов (1-е число месяца в 01:00).
 *
 * Правило «второго месяца»: если BK-слот, глобальный фильтр или фильтр контроллера
 * были оплачены в текущем календарном месяце (при добавлении), планировщик их пропускает —
 * первый, возможно неполный, месяц уже оплачен. Следующее списание будет на 1-е следующего месяца.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyTokenBillingScheduler {

    private final UserRepository     userRepository;
    private final UserBillingProcessor billingProcessor;

    @Scheduled(cron = "0 0 1 1 * *")
    public void runMonthlyBilling() {
        log.info("[BILLING] Запуск ежемесячного биллинга токенов");
        YearMonth billingMonth = YearMonth.now();

        int totalUsers = 0, totalGranted = 0, totalBkCharged = 0, totalFilterCharged = 0;
        int pageNum = 0;
        Page<UserEntity> batch;
        do {
            batch = userRepository.findAllByStatus(UserStatus.ACTIVE, PageRequest.of(pageNum, 100));
            for (UserEntity user : batch.getContent()) {
                try {
                    BillingResult result = billingProcessor.process(user, billingMonth);
                    totalGranted       += result.granted();
                    totalBkCharged     += result.bkCharged();
                    totalFilterCharged += result.filterCharged();
                    totalUsers++;
                } catch (Exception e) {
                    log.error("[BILLING] Ошибка биллинга userId={}", user.getId(), e);
                }
            }
            pageNum++;
        } while (batch.hasNext());

        log.info("[BILLING] Завершён: users={} granted={} bkCharged={} filterCharged={}",
            totalUsers, totalGranted, totalBkCharged, totalFilterCharged);
    }

    public record BillingResult(int granted, int bkCharged, int filterCharged) {}
}
