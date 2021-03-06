(ns starfreighter.cards
  (:require [starfreighter.cards.bar :as bar]
            [starfreighter.cards.gambling :as gambling]
            [starfreighter.cards.loans :as loans]
            [starfreighter.cards.port :as port]
            [starfreighter.cards.space :as space]
            [starfreighter.db :as db]
            [starfreighter.rand :as rand]
            [starfreighter.util :as util]))

(let [port-cards
      (for [card (concat port/cards bar/cards gambling/cards loans/cards)]
        (update card :prereq #(or (some-> % (every-pred :docked?)) :docked?)))
      space-cards
      (for [card space/cards]
        (update card :prereq #(or (some-> % (every-pred (complement :docked?))) (complement :docked?))))]
  (def all-cards
    (into (vec port-cards) space-cards)))

(defn interruptible?
  "Returns whether a given `card` is interruptible – i.e. whether it's OK (both
  gameplay- and narrative-wise) for the player to \"just walk away\" from the
  situation the card represents, e.g. by initiating travel to another place."
  [card]
  (if (contains? card :interruptible?)
    (:interruptible? card)
    (case (:type card)
      :game-over false
      :info (empty? (:ok card))
      :yes-no (or (empty? (:yes card))
                  (empty? (:no card))))))

(defn prepare-to-depart [state]
  (let [dest (:info-target state)]
    (assoc state :card
      {:type :yes-no
       :interruptible? false
       :speaker (db/some* state db/crew)
       :text ["Oh, we’re leaving for " (:name dest) " already? Guess I’ll go fire up the engine!"]
       :yes [[:depart-for dest]]
       :no []})))

(defn applicable-game-over-if-any [state]
  (cond
    (and (zero? (:crew state)) (:docked? state))
      {:type :game-over
       :text ["The crew, fed up with your leadership, steal your ship and depart, "
              "leaving you stranded on " (:location state) "."]}
    (and (zero? (:ship state)) (not (:docked? state)))
      {:type :game-over
       :deadly? true
       :text ["With a horrific creak, your ship’s hull gives way, wrenching itself apart. "
              "A torrential rush of air sucks you nigh instantaneously into the vacuum of space."]}
    :else
      nil))

(defn applicable-arrival-if-any [state]
  (cond
    ;; drop off (and cash in) cargo you're supposed to deliver
    (db/has-cargo-to-drop? state)
      (let [[dropping keeping]
            (util/sift #(and (not (:passenger? %))
                             (= (:destination %) (:location state)))
                       (:cargo state))]
        {:type :info
         :speaker (db/some* state db/crew)
         :text "I’ll go drop off the goods we’re supposed to deliver."
         :ok (into [[:call #(assoc % :cargo (vec keeping))]
                    [:earn (reduce + (map :pay dropping))]]
                   (for [{:keys [merchant]} dropping :when merchant]
                     [:add-memory merchant :completed-delivery]))})
    ;; drop off passengers you're supposed to deliver
    (db/has-passengers-to-drop? state)
      (let [[dropping keeping]
            (util/sift #(and (:passenger? %)
                             (= (:destination %) (:location state)))
                       (:cargo state))]
        {:type :info
         :speaker (rand-nth dropping)
         :text "Thanks for the ride, Captain! It’ll be good to get a fresh start here."
         :ok [[:call #(assoc % :cargo (vec keeping))]]})))

(defn try-pick [state metacard]
  (when (and (or (:repeatable? metacard)
                 (not (contains? (:recent-picks state) (:id metacard))))
             ((:prereq metacard (constantly true)) state))
    (loop [pairs (:bind metacard)
           bindings {}]
      (if-let [[k f] (first pairs)]
        (let [v (f (assoc state :bound bindings))]
          (when-not (nil? v)
            (recur (rest pairs) (assoc bindings k v))))
        (assoc metacard :bound bindings)))))

(defn draw-next-card [state]
  (or (applicable-game-over-if-any state)
      (applicable-arrival-if-any state)
      (:next-card state)
      (let [deck     (or (:deck state) all-cards)
            pickable (filter identity (map (partial try-pick state) deck))
            {:keys [bound gen id]}
            (rand/weighted-choice
              (->> pickable
                   (map #((:weight %) (assoc state :bound (:bound %))))
                   (zipmap pickable)))]
        (prn id)
        (assoc (gen (assoc state :bound bound))
          :advance-time? (not (:deck state)) ; generally, only "top-level" cards should advance time
          :id id))))
