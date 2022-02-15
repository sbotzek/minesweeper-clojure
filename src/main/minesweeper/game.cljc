(ns minesweeper.game
  (:require
    [clojure.set :as set]))

(defn- cell-adjacent? [{:keys [row col] :as cell} other]
  (let [min-row (- row 1)
        max-row (+ row 1)
        min-col (- col 1)
        max-col (+ col 1)]
    (and
     (or (not= row (:row other)) (not= col (:col other)))
     (<= min-row (:row other) max-row)
     (<= min-col (:col other) max-col))))

(defn- adjacent-cells [{:keys [row col] :as cell} board]
  (for [rd (range -1 2)
        cd (range -1 2)
        :let [adj {:row (+ rd row) :col (+ cd col)}]
        :when (and (or (not= rd 0) (not= cd 0))
                   (contains? board adj))]
    adj))

(defn- adjacent-mine-count [{:keys [col, row] :as cell} mine-cells]
  (->> mine-cells
       (filter #(cell-adjacent? cell %1))
       count))

(defn- update-game-status [{:keys [board mine-count] :as game}]
  (cond
    ; lost
    (some #(= :exploded %) (vals board))
    (assoc game :status :lost)

    ; won
    (= mine-count
       (->> board vals (filter (fn [v] (#{:hidden :flagged} v))) count))
    (assoc game :status :won)

    ; still playing
    :else
    game))


(defn- mine-count-satisfied? [cell board]
  {:pre [(number? (get board cell))]}
  (let [mines (get board cell)
        adjacent-flags (->> board
                            (adjacent-cells cell)
                            (filter #(= :flagged (get board %)))
                            (count))]
    (<= mines adjacent-flags)))

(defn- all-adjacent-need-flag? [cell board]
  (let [mines (get board cell)
        potential-flags (->> board
                             (adjacent-cells cell)
                             (filter (fn [cell] (#{:flagged :hidden} (get board cell))))
                             (count))]
    (= mines potential-flags)))

(defn- next-step-for-cell [cell board]
  (if (= :hidden (get board cell))
    (let [adjacent-cells (adjacent-cells cell board)]
      (cond
        (some #(all-adjacent-need-flag? % board)
              (filter #(number? (get board %)) adjacent-cells))
        [:flag cell]

        (some #(mine-count-satisfied? % board)
              (filter #(number? (get board %)) adjacent-cells))
        [:reveal cell]))
    nil))

(defn reveal [{:keys [board status mines mine-count mine-generator] :as game} cell]
  {:pre [(= :hidden (get board cell))
         (= status :playing)]}
  (letfn [(reveal-fn [{:keys [board mines status] :as game} cell]
            (let [state (if (contains? mines cell)
                          :exploded
                          (adjacent-mine-count cell mines))
                  new-game (-> game
                               (assoc-in [:board cell] state)
                               (update-game-status))]
              (if (= 0 state)
                (let [adjacent-cells (filter #(cell-adjacent? % cell)
                                             (-> new-game :board keys))
                      reduce-fn (fn [fn-game fn-cell]
                                  (if (= :hidden (-> fn-game :board (get fn-cell)))
                                    (reveal-fn fn-game fn-cell)
                                    fn-game))]
                  (reduce reduce-fn new-game adjacent-cells))
                new-game)))]
    (if (= mine-count (count mines))
      (reveal-fn game cell)
      (reveal-fn (mine-generator game cell) cell))))

(defn toggle-flag [{:keys [board status] :as game} cell]
  {:pre [(#{:hidden :flagged} (get board cell))
         (= status :playing)]}
  (let [state (get board cell)]
    (case state
      :flagged
      (if (some #(= 0 (get board %)) (adjacent-cells cell board))
        (-> game
         (assoc-in [:board cell] :hidden)
         (reveal cell))
        (assoc-in game [:board cell] :hidden))

      :hidden
      (assoc-in game [:board cell] :flagged))))

(defn next-step [{:keys [status board] :as game}]
  {:pre [(= :playing status)]}
  (first (filter some? (map #(next-step-for-cell % board)
                            (keys board)))))

(defn take-step [game]
  (let [step (next-step game)]
    (case (first step)
      :flag
      (toggle-flag game (second step))

      :reveal
      (reveal game (second step))

      game)))

(def mine-generator-standard
  (fn [{:keys [board mine-count] :as game} cell]
    (let [cells (into #{} (keys board))
          free-cells (into #{cell} (adjacent-cells cell board))
          candidate-cells (set/difference cells free-cells)
          mines (into #{} (take mine-count (shuffle candidate-cells)))]
      (assoc game :mines mines))))

(def mine-generator-impossible
  (fn [{:keys [board mine-count] :as game} cell]
    (let [cells (into #{} (keys board))
          candidate-cells (disj cells cell)
          mines (into #{cell} (take (- mine-count 1) (shuffle candidate-cells)))]
      (assoc game :mines mines))))

(def difficulties
  (array-map
   :trivial {:name "Trivial" :rows 9 :cols 9 :mines 5}
   :beginner {:name "Beginner" :rows 9 :cols 9 :mines 10}
   :intermediate {:name "Intermediate" :rows 16 :cols 16 :mines 40}
   :expert {:name "Expert" :rows 16 :cols 30 :mines 99}))

(def mine-generators
  (array-map
   :standard {:name "Standard" :generator mine-generator-standard}
   :impossible {:name "Impossible" :generator mine-generator-impossible}))

(defn make-game
  ([{:keys [rows cols mines]} mine-generator]
   (make-game rows cols mines mine-generator))
  ([rows cols mine-count mine-generator]
   (let [cells (for [r (range rows)
                     c (range cols)]
                 {:row r :col c})
         board (zipmap cells (repeat :hidden))]
     {:mine-count mine-count
      :mine-generator (or mine-generator mine-generator-standard)
      :rows rows
      :cols cols
      :board board
      :status :playing})))