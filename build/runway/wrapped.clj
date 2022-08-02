(ns runway.wrapped
  (:require [com.stuartsierra.component :as component]
            [com.stuartsierra.component.platform :as platform]
            [runway.build :as build]
            [runway.core :as run]
            [taoensso.timbre :as log]))

;; First we stub out some lifecycle and error methods simulating some
;; other implementation framework. In this case, the "framework" is
;; just a simple map.

(defn- impl-throw-e
  [sys lifecycle]
  (throw (ex-info "Impl system error"
                  {:impl-failed-id ::impl-component
                   :impl-lifecycle lifecycle
                   :impl-system    sys})))

(defn- impl-start-fn
  [sys]
  ;; Simulate a lifecycle error in the impl system here
  ;; (impl-throw-e sys #'impl-start-fn)
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
  [e]
  (:impl-failed-id (ex-data e)))

(defn- get-failed-system
  [e]
  (:impl-system (ex-data e)))

(defn- get-error-msg
  [e]
  (let [{k   :impl-failed-id
         f   :impl-lifecycle
         sys :impl-system} (ex-data e)
        name               (platform/type-name sys)]
    (-> "Wrapped error in component %s in system %s calling %s"
        (format k name f))))

(declare ->ComponentWrapper)

(defn- impl-e->component-e
  "Unpack data from impl system exception and convert to a component
  exception."
  [e lifecycle]
  (let [id  (get-failed-id e)
        sys (get-failed-system e)]
    (ex-info (get-error-msg e)
             ;; Must wrap failed impl system in component
             {:system-key id
              :system     (->ComponentWrapper sys)
              :function   lifecycle}
             e)))

(defn- impl-recoverable-subsystem
  "Given a failed impl system, we return a subsystem that can be
  recovered by calling the impl stop lifecycle method."
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
      (catch Throwable impl-e
        (-> impl-e
            (impl-e->component-e #'component/start)
            (throw)))))

  (stop [_]
    (try
      (->ComponentWrapper (impl-stop-fn impl-system))
      (catch Throwable impl-e
        (-> impl-e
            (impl-e->component-e #'component/stop)
            (throw)))))

  run/Recover
  (recoverable-system [_ failed-id]
    (log/info "Wrapped recover")
    (-> impl-system
        (impl-recoverable-subsystem)
        (->ComponentWrapper))))

(defn wrapped-app
  "Passed to runway.core/go"
  []
  (->ComponentWrapper
   ;; Replace run/assemble-system with impl framework's constructor.
   (impl-system-constructor)))
