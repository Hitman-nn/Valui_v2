CREATE TABLE bet_account_transactions (
    id         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID          NOT NULL REFERENCES bet_accounts(id) ON DELETE CASCADE,
    person_id  UUID          NOT NULL REFERENCES bet_persons(id)  ON DELETE CASCADE,
    amount     NUMERIC(19,2) NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_bat_account_person_date
    ON bet_account_transactions(account_id, person_id, created_at DESC);
