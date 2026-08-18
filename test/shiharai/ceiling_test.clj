(ns shiharai.ceiling-test
  "The ceiling, as a check rather than a claim.

  README and CLAUDE.md both assert that nothing in this repository can move
  money: no network call, no credential, no bank client, and specifically
  not `kotoba.banking.api`'s payment-initiation request builder. Until this
  file, the evidence offered for that was a `grep -r \"http|fetch|slurp\"`
  in prose — and running it finds two hits: a statute's citation URL in
  `shiharai.law` and the sentence in `shiharai.actor` saying the API builder
  is not used. Both are harmless, which is the problem: a check whose stated
  form does not actually pass is a check nobody runs.

  So it is done twice over, precisely:

  1. **What the source DEPENDS on.** Every `ns` form under `src/` is read
     (with reader conditionals allowed) and its requires compared against an
     allow-list. A network reach almost always arrives as a dependency, and
     an allow-list makes adding one a decision somebody has to write down
     here.
  2. **What the source CALLS.** A token scan for the host escapes that need
     no dependency at all — `js/fetch`, `slurp`, and the rest.

  Neither test can pass by finding nothing: both assert a floor on how much
  they actually read, because a scanner pointed at an empty directory
  reports the same clean result as a clean repository."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io PushbackReader StringReader)))

(def ^:private source-root "src")

(defn- source-files []
  (->> (file-seq (io/file source-root))
       (filter #(.isFile %))
       (filter #(re-find #"\.clj[cs]?$" (.getName %)))
       (sort-by #(.getPath %))))

(defn- ns-form
  "The first form of `f`, read with reader conditionals allowed so a `.cljc`
  ns form containing `#?(:clj … :cljs …)` reads as itself rather than
  throwing. `read` and not `eval`."
  [f]
  (with-open [r (PushbackReader. (StringReader. (slurp f)))]
    (read {:read-cond :allow :eof nil} r)))

(defn- required-namespaces [form]
  (->> form
       (filter list?)
       (filter #(= :require (first %)))
       (mapcat rest)
       (map #(if (sequential? %) (first %) %))
       (filter symbol?)
       set))

(def ^:private permitted-requires
  "Every namespace this repository's source is allowed to depend on, and why
  it cannot reach the network.

  `kotoba.banking` is here; **`kotoba.banking.api` is not, and that is the
  entry that matters.** The two are one character apart and the second one
  builds Berlin Group payment-initiation requests. Adding it would not look
  like a change of policy in a diff — it would look like a typo."
  '#{;; this repo
     shiharai.actor shiharai.advisor shiharai.governor shiharai.law
     shiharai.shiwake shiharai.store
     ;; the fleet's shared decision layers — pure, no I/O
     governor.core kotoba.banking kotoba.taxlaw
     ;; the graph and its checkpoint — in-process
     langgraph.graph langgraph.checkpoint
     ;; the store seam. langchain.db is a dependency-free in-memory EAV
     ;; store; its persistence port is two functions the CALLER supplies,
     ;; so it opens nothing itself.
     langchain.db langchain-store.core
     ;; reading data, evaluating nothing
     clojure.edn cljs.reader
     clojure.string clojure.set})

(deftest the-source-depends-on-nothing-that-can-reach-a-network
  (let [files (source-files)]
    (testing "the scan actually read something — an empty src/ must not pass"
      (is (>= (count files) 5)
          (str "scanned " (count files) " source files under " source-root)))
    (doseq [f files]
      (let [required (required-namespaces (ns-form f))
            unexpected (remove permitted-requires required)]
        (is (empty? unexpected)
            (str (.getPath f) " requires " (pr-str (vec unexpected))
                 " — not on the permitted list. If it genuinely cannot reach"
                 " the network or a credential, add it here with the sentence"
                 " that says why."))))))

(deftest kotoba-bankings-payment-initiation-builder-is-not-required-anywhere
  (testing "the one dependency that would turn this actor into a payer"
    (is (not (contains? permitted-requires 'kotoba.banking.api))
        "it must not even be permitted")
    (doseq [f (source-files)]
      (is (not (contains? (required-namespaces (ns-form f)) 'kotoba.banking.api))
          (str (.getPath f) " requires kotoba.banking.api")))))

(def ^:private forbidden-tokens
  "Host escapes that need no dependency, so the allow-list above would not
  catch them."
  ["js/fetch" "js/XMLHttpRequest" "js/WebSocket" "js/Request" "js/Response"
   "XMLHttpRequest." "(slurp" "(spit" "java.net" "clojure.java.shell"
   "clj-http" "http-kit" "httpkit" "org.httpkit"])

(deftest the-source-calls-nothing-that-can-reach-a-network-or-a-file
  (let [files (source-files)
        scanned (atom 0)]
    (doseq [f files]
      (swap! scanned inc)
      (let [src (slurp f)]
        (doseq [t forbidden-tokens]
          (is (not (str/includes? src t))
              (str (.getPath f) " contains " (pr-str t))))))
    (testing "SCANNED — nothing found is only meaningful if something was read"
      (is (>= @scanned 5) (str "scanned " @scanned " files")))))

(deftest the-only-http-literal-in-the-source-is-a-statute-citation
  (testing "README said this grep finds NOTHING. It finds one line — the
            e-Gov URL that 取適法 第三条 was retrieved from, which is a
            citation and not a call. Naming the one hit is what makes a
            SECOND one fail the build instead of quietly joining a claim
            that was already off by one."
    (let [hits (for [f (source-files)
                     [n line] (map-indexed vector (str/split-lines (slurp f)))
                     :when (str/includes? line "http")]
                 [(.getPath f) (inc n) (str/trim line)])]
      (is (= 1 (count hits)) (str "http occurrences: " (pr-str (vec hits))))
      (is (some (fn [[path _ line]]
                  (and (str/includes? path "law.cljc")
                       (str/includes? line "laws.e-gov.go.jp")))
                hits)))))
