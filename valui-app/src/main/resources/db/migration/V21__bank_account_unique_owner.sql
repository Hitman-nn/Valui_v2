-- V21: one bank account per person
ALTER TABLE bank_accounts
    ADD CONSTRAINT uq_bank_accounts_owner UNIQUE (owner_telegram_id);
