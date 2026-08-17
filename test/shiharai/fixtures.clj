(ns shiharai.fixtures
  "One store every suite builds on, so a rule that only fires because its
  fixture was built around it is visible as such."
  (:require [kotoba.banking :as bank]
            [shiharai.store :as store]))

;; A real IBAN (Berlin Group / kotoba-lang/banking's own example) and the same
;; string with its last digit changed, so the mod-97 check has something to
;; refuse that is not merely malformed.
(def valid-iban "DE89370400440532013000")
(def bad-iban "DE89370400440532013001")

(defn fresh-store []
  (let [st (store/mem-store)]
    ;; --- accounts -----------------------------------------------------
    (store/register-account!
     st (assoc (bank/account valid-iban "EUR" :holder "Acme GmbH")
               :account/scheme :iban))
    (store/register-account!
     st (assoc (bank/account bad-iban "EUR" :holder "Typo GmbH")
               :account/scheme :iban))
    ;; 全銀 domestic — this repo has no validator for it, on purpose.
    (store/register-account!
     st (assoc (bank/account "0001-123-4567890" "JPY" :holder "受託工業")
               :account/scheme :zengin))
    (store/register-account!
     st (assoc (bank/account "FUND-EUR" "EUR" :holder "自社") :account/scheme :internal))
    (store/register-account!
     st (assoc (bank/account "FUND-JPY" "JPY" :holder "自社") :account/scheme :internal))

    ;; --- suppliers ----------------------------------------------------
    (store/register-supplier! st {:supplier/id "s-1"
                                  :supplier/name "Acme GmbH"
                                  :supplier/account valid-iban
                                  :supplier/sme-subcontractor? false})
    (store/register-supplier! st {:supplier/id "s-2"
                                  :supplier/name "受託工業"
                                  :supplier/account "0001-123-4567890"
                                  :supplier/sme-subcontractor? true})
    (store/register-supplier! st {:supplier/id "s-3"
                                  :supplier/name "Typo GmbH"
                                  :supplier/account bad-iban
                                  :supplier/sme-subcontractor? false})
    (store/register-supplier! st {:supplier/id "s-4"
                                  :supplier/name "口座未登録"
                                  :supplier/sme-subcontractor? false})

    ;; --- payables -----------------------------------------------------
    ;; The clean one: EUR, IBAN destination, claims no input-tax credit.
    (store/register-payable! st {:payable/id "pbl-1"
                                 :payable/supplier "s-1"
                                 :payable/amount-minor 120000
                                 :payable/currency "EUR"
                                 :payable/received-date "2026-01-01"
                                 :payable/due-date "2026-02-01"
                                 :payable/jurisdiction [:jp]
                                 :payable/claims-input-tax-credit? false})
    ;; Domestic, in scope for 取適法 第三条, claims 仕入税額控除 with a
    ;; well-formed 登録番号, electronic origin preserved electronically.
    (store/register-payable! st {:payable/id "pbl-2"
                                 :payable/supplier "s-2"
                                 :payable/amount-minor 500000
                                 :payable/currency "JPY"
                                 :payable/received-date "2026-01-01"
                                 :payable/due-date "2026-02-01"
                                 :payable/mandate :manufacturing-subcontract
                                 :payable/jurisdiction [:jp]
                                 :payable/claims-input-tax-credit? true
                                 :payable/registration-number "T1234567890123"
                                 :payable/origin :electronic-transaction
                                 :payable/preservation :electronic})
    ;; Amount nobody recorded. Not 0, not unlimited.
    (store/register-payable! st {:payable/id "pbl-unknown"
                                 :payable/supplier "s-1"
                                 :payable/currency "EUR"
                                 :payable/received-date "2026-01-01"
                                 :payable/due-date "2026-02-01"})
    st))

(defn payment [& {:as kv}]
  (merge {:payment/id "pay-1"
          :payment/payable "pbl-1"
          :payment/amount-minor 120000
          :payment/currency "EUR"
          :payment/from-account "FUND-EUR"
          :payment/date "2026-01-20"}
         kv))

(defn proposal [& {:as kv}]
  (merge {:op :schedule-payment
          :effect :propose
          :payable "pbl-1"
          :payment (payment)
          :confidence 0.9}
         kv))

(def request {:supplier-id "s-1"})
