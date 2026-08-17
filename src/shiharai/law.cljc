(ns shiharai.law
  "The one statute this actor enforces on the PAYMENT TERM, and the calendar
  arithmetic it needs.

  取適法（中小受託取引適正化法）第三条 — the payment due date for 製造委託等代金
  must be set within 60 days of the day the ordering party received the
  supplier's 給付.

  ## The text was read, not cited

  Retrieved 2026-08-17 from the e-Gov law API v2,
  `GET /api/2/law_data/331AC0000000120?law_full_text_format=json`, revision
  `331AC0000000120_20260101_507AC0000000041` (promulgated 2025-05-23, in force
  from 2026-01-01 — the amendment that renamed 下請代金支払遅延等防止法 to
  製造委託等に係る中小受託事業者に対する代金の支払の遅延等の防止に関する法律 and
  renumbered this rule from 第二条の二 to 第三条). Both paragraphs are quoted
  verbatim in `subcontract-payment-term` below.

  `kotoba.taxlaw` sets the standard this follows: a rule is enforceable here
  only if its text is in this file. A URL is not a rule.

  ## What is deliberately NOT decided here

  **The 起算 boundary.** 第一項 says the due date must fall within 60 days
  「受領した日から起算して」 — counting from, and including, the day of receipt.
  第二項 then deems a violating term to be 「受領した日から起算して六十日を経過
  した日の前日」. Whether 「六十日を経過した日」 is the 60th day or the 61st is a
  question of Japanese legal counting convention that this file does not
  resolve, because resolving it wrongly moves a hold by one day in either
  direction.

  So `payment-term` answers in three values on that axis, and the ambiguous
  one is its own value:

    :conforming     strictly fewer than 60 days separate receipt and due date
    :boundary       exactly 60 — beyond the limit on one reading and inside it
                    on the other. NOT reported as a violation, and NOT reported
                    as compliant. The governor escalates it to a human.
    :exceeded       more than 60 — beyond the limit on EVERY reading

  Only `:exceeded` is a HARD violation. An engineer who wanted a single
  boolean here would have to pick a convention, and would then be enforcing a
  reading of the statute rather than the statute.

  ## Scope is not widened

  第三条 binds a 委託事業者 paying a 中小受託事業者 for 製造委託等. It is not a
  general rule about invoices. `payment-term` returns `:not-declared` unless
  the payable says it arises from 製造委託等 AND the supplier is recorded as a
  中小受託事業者 — and `:not-declared` is neither a pass nor a refusal, exactly
  as `kotoba.taxlaw/record-preservation` treats an undeclared origin."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Calendar — pure, portable, and it refuses rather than guesses
;; ---------------------------------------------------------------------------

(defn- civil->epoch-day
  "Days since 1970-01-01 for a proleptic Gregorian y-m-d. Howard Hinnant's
  algorithm; integer arithmetic only, so it is identical on JVM and JS."
  [y m d]
  (let [y (if (<= m 2) (dec y) y)
        era (quot (if (>= y 0) y (- y 399)) 400)
        yoe (- y (* era 400))
        doy (+ (quot (+ (* 153 (+ m (if (> m 2) -3 9))) 2) 5) (dec d))
        doe (+ (* yoe 365) (quot yoe 4) (- (quot yoe 100)) doy)]
    (+ (* era 146097) doe -719468)))

(defn- epoch-day->civil
  "Inverse of `civil->epoch-day`, used to reject dates like 2026-02-30 by
  round-trip rather than by a month-length table."
  [z]
  (let [z (+ z 719468)
        era (quot (if (>= z 0) z (- z 146096)) 146097)
        doe (- z (* era 146097))
        yoe (quot (- doe (quot doe 1460) (- (quot doe 36524)) (quot doe 146096)) 365)
        y (+ yoe (* era 400))
        doy (- doe (+ (* 365 yoe) (quot yoe 4) (- (quot yoe 100))))
        mp (quot (+ (* 5 doy) 2) 153)
        d (inc (- doy (quot (+ (* 153 mp) 2) 5)))
        m (+ mp (if (< mp 10) 3 -9))]
    [(if (<= m 2) (inc y) y) m d]))

(def ^:private date-pattern #"(\d{4})-(\d{2})-(\d{2})")

(defn epoch-day
  "`\"YYYY-MM-DD\"` -> days since the epoch, or **nil**.

  nil for a non-string, a malformed string, and for a well-formed string
  naming a day that does not exist (2026-02-30, 2026-13-01). A caller cannot
  read nil as 0 — day 0 is 1970-01-01, a real date — which is why every
  consumer below branches on nil explicitly instead of arithmetic on it."
  [s]
  (when (string? s)
    (when-let [[_ y m d] (re-matches date-pattern (str/trim s))]
      (let [y (parse-long y) m (parse-long m) d (parse-long d)]
        (when (and (<= 1 m 12) (<= 1 d 31))
          (let [z (civil->epoch-day y m d)]
            ;; round-trip: 2026-02-30 normalises to 2026-03-02 and is rejected
            (when (= [y m d] (epoch-day->civil z)) z)))))))

(defn date-string
  "Inverse of `epoch-day`, for reporting a deemed date back to a human."
  [z]
  (let [[y m d] (epoch-day->civil z)]
    (str y "-" (when (< m 10) "0") m "-" (when (< d 10) "0") d)))

(defn days-between
  "Calendar days from `from` to `to`, or nil when either date is unreadable.
  Negative when `to` precedes `from` — this function does not decide whether
  that is an error, because for a prepayment it is not."
  [from to]
  (let [a (epoch-day from) b (epoch-day to)]
    (when (and a b) (- b a))))

;; ---------------------------------------------------------------------------
;; 取適法 第三条
;; ---------------------------------------------------------------------------

(def subcontract-payment-term
  "The instrument, quoted. `:law/quote` and `:law/quote-2` are the two
  paragraphs as returned by the e-Gov API, byte for byte."
  {:law/id "331AC0000000120"
   :law/revision-id "331AC0000000120_20260101_507AC0000000041"
   :law/num "昭和三十一年法律第百二十号"
   :law/title "製造委託等に係る中小受託事業者に対する代金の支払の遅延等の防止に関する法律"
   :law/abbrev "中小受託取引適正化法（取適法）"
   :law/provision "第三条（製造委託等代金の支払期日）"
   :law/authority "日本国 / e-Gov 法令検索"
   :law/url "https://laws.e-gov.go.jp/law/331AC0000000120"
   :law/retrieved-at "2026-08-17"
   :law/retrieved-via
   "e-Gov law API v2 GET /api/2/law_data/331AC0000000120?law_full_text_format=json"
   :law/enforcement-date "2026-01-01"
   :law/max-days 60
   :law/quote
   (str "製造委託等代金の支払期日は、委託事業者が中小受託事業者の給付の内容について"
        "検査をするかどうかを問わず、委託事業者が中小受託事業者の給付を受領した日"
        "（役務提供委託又は特定運送委託の場合にあつては、中小受託事業者からその委託に"
        "係る役務の提供を受けた日。以下同じ。）から起算して、六十日の期間内において、"
        "かつ、できる限り短い期間内において、定められなければならない。")
   :law/quote-2
   (str "製造委託等代金の支払期日が定められなかつたときは委託事業者が中小受託事業者の"
        "給付を受領した日が、前項の規定に違反して製造委託等代金の支払期日が定められた"
        "ときは委託事業者が中小受託事業者の給付を受領した日から起算して六十日を経過"
        "した日の前日が、それぞれ製造委託等代金の支払期日と定められたものとみなす。")
   :law/scope
   (str "委託事業者 が 中小受託事業者 に対して負う 製造委託等代金 に限る。"
        "一般の買掛金に拡張しない。")
   :law/not-decided
   (str "「六十日を経過した日」の起算解釈（60日目か61日目か）はここで決めない。"
        "ちょうど60日は :boundary として人に上げる。")})

(defn in-scope?
  "Does 第三条 reach this payable at all?

  Both sides must be asserted: the payable must say it arises from 製造委託等
  (`:payable/mandate :manufacturing-subcontract`) and the supplier must be
  recorded as a 中小受託事業者. Nobody's silence puts a transaction in scope,
  and nobody's silence takes it out — an undeclared transaction is simply one
  this rule was not asked about."
  [payable supplier]
  (boolean (and (= :manufacturing-subcontract (:payable/mandate payable))
                (true? (:supplier/sme-subcontractor? supplier)))))

(defn payment-term
  "Is this payable's due date a term 第三条 permits?

  Three-shaped, like `kotoba.taxlaw/record-preservation`, and for the same
  reason — `:not-declared` is neither a pass nor a refusal:

    {:law/coverage :not-declared}   the transaction is not asserted to be one
                                    第三条 reaches
    {:law/coverage :undeterminable} it IS asserted to be one, and a date
                                    needed to check it is missing or
                                    unreadable. This is the case a caller must
                                    NOT read as compliance: the rule applies
                                    and could not be evaluated.
    {:law/coverage :checked ...}    `:law/term` is :conforming | :boundary |
                                    :exceeded, with `:law/days` the plain
                                    calendar difference receipt -> due date

  `:law/deemed-due-date` is present only on `:exceeded`, and only as the
  LATEST date 第一項 could permit on the more generous reading (receipt + 60).
  第二項's deemed date turns on the 起算 boundary this file refuses to resolve,
  so what is reported is a bound, labelled as one."
  [payable supplier]
  (cond
    (not (in-scope? payable supplier))
    {:law/coverage :not-declared
     :law/why (str "取適法 第三条 の適用は主張されていない"
                   "（:payable/mandate と :supplier/sme-subcontractor? の両方が要る）")}

    :else
    (let [received (:payable/received-date payable)
          due (:payable/due-date payable)
          n (days-between received due)]
      (if (nil? n)
        {:law/coverage :undeterminable
         :law/provision (:law/provision subcontract-payment-term)
         :law/received-date received
         :law/due-date due
         :law/why (str "適用対象だと主張しているが、受領日 " (pr-str received)
                       " と支払期日 " (pr-str due)
                       " から日数を出せない（未評価は適合ではない）")}
        (let [limit (:law/max-days subcontract-payment-term)]
          {:law/coverage :checked
           :law/provision (:law/provision subcontract-payment-term)
           :law/received-date received
           :law/due-date due
           :law/days n
           :law/max-days limit
           :law/term (cond (> n limit) :exceeded
                           (= n limit) :boundary
                           :else :conforming)
           :law/latest-permissible-due-date
           (when-let [r (epoch-day received)] (date-string (+ r limit)))
           :law/deemed-due-date
           (when (> n limit)
             (when-let [r (epoch-day received)] (date-string (+ r limit))))
           :law/deemed-due-date-note
           (when (> n limit)
             (str "第二項のみなし支払期日は「六十日を経過した日の前日」であり、"
                  "その起算解釈はこの library では決めない。ここに出るのは"
                  "第一項が許しうる最も遅い日（受領日 + " limit " 日）という上界。"))
           :law/quote (:law/quote subcontract-payment-term)})))))

(defn exceeded?
  "True only for the unambiguous violation. `:boundary`, `:undeterminable`,
  `:not-declared` are all false here — this predicate answers exactly one
  question, and the caller must handle the others explicitly."
  [term]
  (= :exceeded (:law/term term)))

(defn boundary?
  "True for exactly-60-days, the reading-dependent case."
  [term]
  (= :boundary (:law/term term)))
