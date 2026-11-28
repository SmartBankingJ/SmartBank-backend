USE bankdirect;

-- Client de test (Karim Alami)
INSERT INTO customers (id, first_name, last_name, cin, email, phone, address)
VALUES (1, 'Karim', 'Alami', 'BE123456', 'karim.alami@email.ma', '0661001122', 'Casablanca, Bd d''Anfa')
ON DUPLICATE KEY UPDATE id=id;

INSERT INTO advisors (id, full_name, phone, email)
VALUES (1, 'Mme Malika Ezzakri', '+212 522-001122', 'm.ezzakri@bmcedirect.ma')
ON DUPLICATE KEY UPDATE id=id;

UPDATE customers SET advisor_id=1 WHERE id=1 AND advisor_id IS NULL;

-- Authentication user is created explicitly by DatabaseSetup --init-demo.
-- Its temporary password comes from SMARTBANK_DEMO_PASSWORD and is hashed in Java.
-- Never insert a plaintext password into password_hash.

-- 2 Comptes pour Karim (Compte Chèque + Épargne)
INSERT INTO accounts (id, customer_id, rib, type, currency, balance, status, opened_at)
VALUES
 (1, 1, '011780000012345678901234', 'CURRENT', 'MAD', 24500.00, 'ACTIVE', NOW()),
(2, 1, '011780000098765432109876', 'SAVINGS', 'MAD', 120000.00, 'ACTIVE', NOW())
ON DUPLICATE KEY UPDATE id=id;

-- Historical demo activity shown on the customer dashboard.
INSERT INTO transactions (id, account_id, type, direction, amount, balance_after, label, reference, counterpart_rib, status, created_at)
VALUES
    (1, 1, 'BENEFICIARY_TRANSFER', 'DEBIT', 500.00, 24500.00, 'Virement vers Fatima Zahra Benali', 'DEMO-VIR-0001', '225780000055443322110099', 'COMPLETED', NOW()),
    (2, 1, 'BENEFICIARY_TRANSFER', 'DEBIT', 1200.00, 25000.00, 'Virement vers bénéficiaire', 'DEMO-VIR-0002', '225780000055443322110099', 'COMPLETED', DATE_SUB(NOW(), INTERVAL 1 HOUR)),
    (3, 1, 'BILL_PAYMENT', 'DEBIT', 350.00, 26200.00, 'Paiement par carte 47791', 'DEMO-CARD-0003', NULL, 'COMPLETED', DATE_SUB(NOW(), INTERVAL 1 DAY))
ON DUPLICATE KEY UPDATE id=id;

-- Bénéficiaire enregistré
INSERT INTO beneficiaries (id, customer_id, full_name, rib, bank_name, created_at)
VALUES (1, 1, 'Fatima Zahra Benali', '225780000055443322110099', 'Attijariwafa Bank', NOW())
ON DUPLICATE KEY UPDATE id=id;
