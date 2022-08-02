(ns runway.build
  (:require [com.stuartsierra.component :as component]
            [runway.core :as run]
            [taoensso.timbre :as log]))

(log/merge-config! {:ns-whitelist ["runway.*"]})

(defrecord Singleton []
  component/Lifecycle
  (start [c]
    (log/info "Singleton started")
    c)
  (stop [c]
    (log/info "Singleton stopped")
    c))

(defrecord TransitiveDependency []
  component/Lifecycle
  (start [c]
    (log/info "Transitive dependency started")
    c)
  (stop [c]
    (log/info "Transitive dependency stopped")
    c))

(defrecord Dependency []
  component/Lifecycle
  (start [c]
    (log/info "Dependency started")
    c)
  (stop [c]
    (log/info "Dependency stopped")
    c))

(defrecord Dependent []
  component/Lifecycle
  (start [c]
    (log/info "Dependent component started")
    c)
  (stop [c]
    (log/info "Dependent component stopped")
    c))

(def components
  {:singleton             [->Singleton]
   :transitive-dependency [->TransitiveDependency]
   :dependency            [->Dependency]
   :dependent             [->Dependent]})

(def dependencies
  {:dependency [:transitive-dependency]
   :dependent  [:dependency]})

(defn example-app
  []
  (run/assemble-system components dependencies))
