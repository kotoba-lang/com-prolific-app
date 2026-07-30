(ns prolific.browser
  "Drive a browser form through agent-browser (CDP), verifying every write.

  Why this channel and not the desktop
  ------------------------------------
  Choosing a DOM-level channel does not merely make failures *detectable*,
  it makes two of them *impossible*. Measured against a fixture form on
  2026-07-30, in the same session where synthetic keystrokes had just
  corrupted the real one:

    fill \"Research Operations\" -> read back \"Research Operations\"
      (the keystroke channel lost the last 4 characters)
    fill \"User Research\" over it -> read back \"User Research\"
      (the keystroke channel had produced \"Research OperationsUser Research\")

  `fill` clears then writes atomically, so `:truncated` and `:appended` from
  `prolific.evidence` cannot occur here. `get value` supplies the read-back
  those verdicts are computed from. That is the entire argument for this
  namespace.

  What this deliberately cannot do
  --------------------------------
  There is no function here that clicks Continue, Next or Submit, and that
  is not an omission. Advancing the signup is what creates the account, and
  the credentials step is a password, a terms checkbox and a CAPTCHA — all
  three permanent boundaries in `prolific.signup`. This namespace fills the
  fields of the step in front of it and stops; a person advances it.

  nbb / Node only. The decisions live in the pure namespaces."
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [prolific.channel :as channel]
            [prolific.evidence :as ev]
            [prolific.signup :as signup]))

(def ^:private exe "agent-browser")

(defn- ab
  "Run agent-browser. Returns {:code :out :err}. Never throws — a missing
  binary is a capability fact, not an exception."
  [args]
  (let [r (.spawnSync cp exe (clj->js args)
                      #js {:encoding "utf8" :timeout 120000})]
    {:code (or (.-status r) 1)
     :out (or (.-stdout r) "")
     :err (or (.-stderr r) (some-> (.-error r) .-message) "")}))

(defn- ab-json
  "Run with --json and return the parsed `data`, or nil.

  nil on any parse failure rather than a guess: an unreadable response is
  exactly the case where inventing a value would manufacture false
  evidence."
  [args]
  (let [{:keys [out]} (ab (conj (vec args) "--json"))]
    (try
      (let [m (js->clj (js/JSON.parse out) :keywordize-keys true)]
        (when (:success m) (:data m)))
      (catch :default _ nil))))

;; ------------------------------------------------------------- capabilities

(defn probe
  "Observed capabilities, for `prolific.channel/choose`.

  Only probes what it can establish. The desktop channels are reported as
  unavailable rather than unknown because this process has no way to check
  them, and `channel/assess` treats an absent key as false — a capability
  nobody probed is not a capability."
  []
  {:agent-browser-installed (zero? (:code (ab ["--version"])))})

;; ------------------------------------------------------------------- refs

(defn snapshot
  "Ref table from the live page: {\"e3\" {:name \"Job Role\" :role \"textbox\"}}."
  []
  (:refs (ab-json ["snapshot"])))

(defn ref-for
  "The `@eN` selector for a field, matched on accessible name.

  Exact match first, then a unique case-insensitive substring. A substring
  matching more than one element returns nil instead of the first hit —
  filling the wrong field is worse than reporting that the label is
  ambiguous."
  ([refs name] (ref-for refs name nil))
  ([refs name role]
   (let [candidates (cond->> (seq refs)
                      role (filter #(= role (:role (val %)))))
         exact (filter #(= name (:name (val %))) candidates)
         fuzzy (filter #(str/includes? (str/lower-case (str (:name (val %))))
                                       (str/lower-case (str name)))
                       candidates)
         pick (cond
                (= 1 (count exact)) (first exact)
                (seq exact) nil
                (= 1 (count fuzzy)) (first fuzzy)
                :else nil)]
     (when pick (str "@" (clj->js (key pick)))))))

(defn value
  "Current value of a field, or nil if it cannot be read."
  [sel]
  (:value (ab-json ["get" "value" sel])))

(defn options
  "Visible label -> option value, for a select. This is how a sector list
  gets reported instead of guessed: the desktop channel could not read the
  options at all, which is why today's run stalled on Sector."
  [refs]
  (into {}
        (keep (fn [[r m]]
                (when (= "option" (:role m))
                  (let [sel (str "@" (clj->js r))
                        v (:value (ab-json ["get" "attr" sel "value"]))]
                    [(:name m) v])))
              refs)))

;; ------------------------------------------------------------------ writes

(defn fill-verified!
  "Write `intent` into `sel`, then prove it landed.

  The repair is chosen from `evidence/verdict`, never fixed: a verdict whose
  `:retry-same-channel?` is false ends the loop immediately, because
  repeating a call that reported success and changed nothing is superstition
  rather than a retry."
  ([sel intent] (fill-verified! sel intent 2))
  ([sel intent attempts]
   (loop [n 1]
     (let [before (value sel)
           _ (ab ["fill" sel intent])
           observed (value sel)
           r (assoc (ev/verdict {:intent intent :before before :observed observed})
                    :attempt n :selector sel)]
       (cond
         (ev/complete? r) r
         (not (:retry-same-channel? r)) r
         (>= n attempts) (assoc r :exhausted? true)
         :else (recur (inc n)))))))

(defn select-verified!
  "Choose an option by its VISIBLE label.

  agent-browser selects by option value, and a form's values are rarely its
  labels (`industry` behind \"Industry\"). Resolving the label through the
  snapshot means the profile can name what a person sees, and an unknown
  label is reported with the list of real ones instead of silently leaving
  the field empty."
  [refs sel label]
  (let [opts (options refs)]
    (if-let [v (get opts label)]
      (let [before (value sel)
            _ (ab ["select" sel v])
            observed (value sel)]
        (assoc (ev/verdict {:intent v :before before :observed observed})
               :selector sel :label label))
      {:ok? false :verdict :absent :selector sel :label label
       :available (vec (sort (keys opts)))
       :note "no option with that label"})))

;; -------------------------------------------------------------------- step

(defn run-step!
  "Fill every machine-fillable field of one step, verifying each.

  Re-snapshots once when a field comes back `:absent`, which is the repair
  that verdict asks for. Returns a report; performs no navigation."
  ([profile step-id] (run-step! profile step-id true))
  ([profile step-id retry-absent?]
   (let [plan (first (filter #(= step-id (:step %)) (signup/plan profile)))
         refs (snapshot)
         results
         (mapv (fn [{:keys [field label kind value]}]
                 (if-let [sel (ref-for refs label)]
                   (assoc (if (= :select kind)
                            (select-verified! refs sel value)
                            (fill-verified! sel value))
                          :field field)
                   {:field field :label label :ok? false :verdict :absent
                    :note "no element with that label in the snapshot"}))
               (:fill plan))
         absent? (some #(= :absent (:verdict %)) results)]
     (if (and absent? retry-absent?)
       (run-step! profile step-id false)
       {:step step-id
        :heading (:heading plan)
        :results (ev/summarize results)
        :ok? (every? ev/complete? results)
        :blocked (:blocked plan)}))))

;; -------------------------------------------------------------------- main

(defn- die [msg]
  (js/console.error msg)
  (.exit js/process 1))

(defn -main
  "nbb -m prolific.browser <profile.edn> <step-id> [url]

  Refuses to act at all when no verifiable channel is available, which is
  the check whose absence let a half-filled form happen."
  [& [profile-path step url]]
  (when-not (and profile-path step)
    (die "usage: nbb -m prolific.browser <profile.edn> <step-id> [url]"))
  (let [pick (channel/choose (probe))]
    (when-not (:channel pick)
      (die (channel/explain pick)))
    (let [profile (edn/read-string (.readFileSync fs profile-path "utf8"))
          cov (signup/coverage profile)]
      (when url (ab ["open" url]))
      (let [report (run-step! profile (keyword step))]
        (println (str "channel: " (name (:channel pick))))
        (println (str "coverage: " (:machine-filled cov) "/" (:fields cov)
                      " fields fillable now"
                      (when (seq (:closable-gaps cov))
                        (str "; closable gaps " (pr-str (:closable-gaps cov))))))
        (println (str "step " (name (:step report)) " — " (:heading report)))
        (doseq [r (:results report)]
          (println (str "  " (name (:verdict r)) "  " (name (:field r))
                        "  " (pr-str (:observed r)))))
        (doseq [b (:blocked report)]
          (println (str "  BLOCKED " (name (:field b)) " — " (name (:reason b))
                        (when (:recommend b) (str " (" (:recommend b) ")")))))
        (.exit js/process (if (:ok? report) 0 1))))))
