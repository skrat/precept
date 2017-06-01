(ns precept.draw.rules
  (:require-macros [precept.dsl :refer [<- entity entities]])
  (:require [precept.accumulators :as acc]
            [precept.spec.error :as err]
            [crate.core :refer [html]]
            [precept.util :refer [insert! insert-unconditional! retract! guid] :as util]
            [precept.rules :refer-macros [define defsub session rule]]
            [precept.draw.schema :as schema]
            [precept.draw.facts :refer [todo entry done-count active-count visibility-filter]]))

(defn by-id [id]
  (.getElementById js/document id))

(defn append-new [target new]
  (.appendChild (by-id target) new))

(defn set-style!
  [elem m]
  (let [style (.-style elem)]
    (doseq [[k v] m]
      (.setProperty style (name k) v))
    elem))

(defn attrs [entity-avs]
  (reduce
    (fn [acc [a v]]
      (if (clojure.string/includes? (str a) "attr")
        (conj acc
          (vector
            (-> a
              (clojure.string/split "/")
              (second)
              (keyword))
            v))
        acc))
    []
    entity-avs))

(defn hit-node [event]
  "Takes .path property of a DOM event and returns first element with an id"
  (first (filter #(not (clojure.string/blank? (.-id %))) (.-path event))))

(rule intercept-mouse-down
  {:group :action}
  [[_ :mouse/down ?event]]
  =>
  (.log js/console "Mouse down event" (hit-node ?event)))

(rule detect-hit
  {:group :action}
  [[_ :mouse/down ?event]]
  [:test (not (nil? (hit-node ?event)))]
  =>
  (insert! [:transient :hit/node (hit-node ?event)]))

(rule make-hit-nodes-red
  [[_ :hit/node ?node]]
  =>
  (set-style! ?node {:background-color "red"}))

(rule intercept-mouse-up
  {:group :action}
  [[_ :mouse/up ?event]]
  =>
  (println "Mouse up event" ?event))

(rule append-element
  {:group :action}
  [[:transient :command :create-element]]
  [[?e :elem/tag ?tag]]
  [[?container :contains ?e]]
  [(<- ?ent (entity ?e))]
  =>
  (let [avs (mapv (juxt :a :v) ?ent)]
    (append-new
      ?container
      (html [?tag (apply hash-map (flatten (attrs avs)))]))))

(rule save-edit
  {:group :action}
  [[_ :todo/save-edit ?e]]
  [?edit <- [?e :todo/edit ?v]]
  =>
  (retract! ?edit)
  (insert-unconditional! [?e :todo/title ?v]))

(rule clear-completed
  {:group :action}
  [[_ :clear-completed]]
  [[?e :todo/done true]]
  [(<- ?done-entity (entity ?e))]
  =>
  (retract! ?done-entity))

(rule complete-all
  {:group :action}
  [[_ :mark-all-done]]
  [[?e :todo/done false]]
  =>
  (insert-unconditional! [?e :todo/done true]))

(rule save-edit-when-enter-pressed
  {:group :action}
  [[_ :input/key-code 13]]
  [[?e :todo/edit]]
  =>
  (insert! [:transient :todo/save-edit ?e]))

(rule create-todo-when-enter-pressed
  {:group :action}
  [[_ :input/key-code 13]]
  [[_ :entry/title]]
  =>
  (insert! [:transient :todo/create :tag]))

(rule create-todo
  {:group :action}
  [[_ :todo/create]]
  [?entry <- [_ :entry/title ?v]]
  =>
  (retract! ?entry)
  (insert-unconditional! (todo ?v)))

(define [?e :todo/visible true] :-
  [:or [:and [_ :visibility-filter :all] [?e :todo/title]]
       [:and [_ :visibility-filter :done] [?e :todo/done true]]
       [:and [_ :visibility-filter :active] [?e :todo/done false]]])

(rule insert-done-count
  [?n <- (acc/count) :from [_ :todo/done true]]
  =>
  (insert-unconditional! (done-count ?n)))

(rule insert-active-count
  [[_ :done-count ?done]]
  [?total <- (acc/count) :from [:todo/title]]
  =>
  (insert-unconditional! (active-count (- ?total ?done))))

(defsub :task-list
  [?eids <- (acc/by-fact-id :e) :from [:todo/visible]]
  [(<- ?visible-todos (entities ?eids))]
  [[_ :active-count ?active-count]]
  =>
  {:visible-todos ?visible-todos
   :all-complete? (= 0 ?active-count)})

(defsub :task-entry
  [[?e :entry/title ?v]]
  =>
  {:db/id ?e :entry/title ?v})

(defsub :footer
  [[_ :done-count ?done-count]]
  [[_ :active-count ?active-count]]
  [[_ :visibility-filter ?visibility-filter]]
  =>
  {:active-count ?active-count
   :done-count ?done-count
   :visibility-filter ?visibility-filter})

(rule remove-orphaned-when-unique-conflict
  [[?e ::err/type :unique-conflict]]
  [[?e ::err/failed-insert ?v]]
  [?orphaned <- [(:e ?v) :all]]
  =>
  (retract! ?orphaned))

(session app-session
  'precept.draw.rules
  :db-schema schema/db-schema
  :client-schema schema/client-schema)
