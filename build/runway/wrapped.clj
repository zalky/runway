(ns runway.wrapped
  (:require [com.stuartsierra.component :as component]
            [com.stuartsierra.component.platform :as platform]
            [runway.build :as build]
            [runway.core :as run]
            [taoensso.timbre :as log]))

;; First we stub out some lifecycle and error methods simulating some
;; other implementation framework. In this case, the "framework" is
;; just a simple map.

(defn- impl-throw-ex
  [sys lifecycle]
  (throw (ex-info "Impl system error"
                  {:impl-failed-id ::impl-component
                   :impl-lifecycle lifecycle
                   :impl-system    sys})))

(defn- impl-start-fn
  [sys]
  ;; Simulate a lifecycle error in the impl system here by
  ;; un-commenting the following line:
  ;; (impl-throw-ex sys #'impl-start-fn)
  (log/info "Impl system started")
  (assoc sys :state ::started))

(defn- impl-stop-fn
  [sys]
  (log/info "Impl system stopped")
  (assoc sys :state ::stopped))

(defn- impl-system-constructor
  "Plain map system. Note that this system does not implement
  com.stuartsierra.component/Lifecycle and we cannot use it with
  Runway as is."
  []
  {:state ::initialized})

;; So we make a component wrapper

(defn- get-failed-id
  [ex]
  (:impl-failed-id (ex-data ex)))

(defn- get-failed-system
  [ex]
  (:impl-system (ex-data ex)))

(defn- get-error-msg
  [ex]
  (let [{k   :impl-failed-id
         f   :impl-lifecycle
         sys :impl-system} (ex-data ex)
        name               (platform/type-name sys)]
    (-> "Wrapped error in component %s in system %s calling %s"
        (format k name f))))

(declare ->ComponentWrapper)

(defn- impl-ex->component-ex
  "Unpack data from impl system exception and convert to a component
  exception."
  [ex lifecycle]
  (let [id  (get-failed-id ex)
        sys (get-failed-system ex)]
    (ex-info (get-error-msg ex)
             ;; Must wrap failed impl system in component
             {:system-key id
              :system     (->ComponentWrapper sys)
              :function   lifecycle}
             ex)))

(defn- impl-recoverable-subsystem
  "Given a failed impl system, we return a subsystem that can be
  recovered by calling the impl stop lifecycle method. Here you are
  resolving some problem, or possibly excising the parts of the system
  that have failed and are unrecoverable from the parts that can be
  cleanly stopped."
  [sys]
  (assoc sys :recoverable true))

(defrecord ComponentWrapper [impl-system]
  ;; Note that whenever we delegate back to Runway, we always wrap the
  ;; impl-system. This includes the return values of each protocol
  ;; method, the system in the thrown exceptions, and also the
  ;; constructor fn passed to runway.core/go.
  component/Lifecycle
  (start [_]
    (try
      (->ComponentWrapper (impl-start-fn impl-system))
      (catch Throwable impl-ex
        (-> impl-ex
            (impl-ex->component-ex #'component/start)
            (throw)))))

  (stop [_]
    (try
      (->ComponentWrapper (impl-stop-fn impl-system))
      (catch Throwable impl-ex
        (-> impl-ex
            (impl-ex->component-ex #'component/stop)
            (throw)))))

  run/Recover
  (recoverable-system [_ failed-id]
    (log/info "Wrapped recover")
    (-> impl-system
        (impl-recoverable-subsystem)
        (->ComponentWrapper))))

(defn wrapped-app
  "Passed to runway.core/go. See the deps.edn file for where that is."
  []
  (->ComponentWrapper
   ;; Replace run/assemble-system with impl framework's constructor.
   (impl-system-constructor)))
